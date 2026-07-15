package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two backends behind CaptureBackend, auto-picked by hardware level (override via api pref):
 *   CameraX (global default) — ImageAnalysis YUV -> encoder + ImageCapture full-res stills.
 *   Camera1 (LEGACY HALs)    — preview -> GL pipe -> surface encoder; true 1920×1080 + video snapshot.
 * Full-res still from the live camera is concurrent; the other camera is captured by switching and
 * back (single HAL → one camera open at a time). Resolution is user-selectable.
 */
@Suppress("DEPRECATION")
class StreamingService : LifecycleService() {

    private val binder = LocalBinder()
    var streamingServerHelper: StreamingServerHelper? = null

    // Streaming encoders (initialized when server is created)
    private var h264StreamingEncoder: H264StreamingEncoder? = null
    private var mjpegStreamingEncoder: MjpegStreamingEncoder? = null
    private val encoders: List<StreamingEncoder>
        get() = listOfNotNull(h264StreamingEncoder, mjpegStreamingEncoder)

    @Volatile private var glPipe: CameraGlPipe? = null
    @Volatile private var backend: CaptureBackend? = null   // CameraXCapture (default) or Camera1Capture (legacy)
    @Volatile private var captureRunning = false

    @Volatile private var frontFacing = false             // false = back camera (read across threads)
    @Volatile private var selectedCameraId: String? = null

    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private val snapCache = ConcurrentHashMap<String, ByteArray>()  // latest JPEG per camera (RAM)
    private val snapLock = Any()
    private val cameraMutex = Mutex()

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val notificationChannelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                intent?.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED) {
                val channelId = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID)
                if (channelId == CHANNEL_ID) {
                    val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
                    if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) handleStopService()
                }
            }
        }
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val STREAM_PORT = 4444
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "streaming_service_channel"
        private const val PREF_CAMERA_ID = "camera_id"
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
        const val ACTION_RESTART_SERVER = "com.github.digitallyrefined.androidipcamera.RESTART_SERVER"
    }

    inner class LocalBinder : Binder() { fun getService(): StreamingService = this@StreamingService }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> { handleStopService(); return START_NOT_STICKY }
            ACTION_RESTART_NOTIFICATION -> startForegroundService()
            ACTION_RESTART_SERVER -> restartServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleStopService() {
        sendBroadcast(Intent("com.github.digitallyrefined.androidipcamera.CLOSE_APP").setPackage(packageName))
        stopForeground(true); stopSelf()
    }

    private fun restartServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopServer()
            kotlinx.coroutines.delay(500)
            streamingServerHelper?.startStreamingServer()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedCameraId = prefs.getString(PREF_CAMERA_ID, null)
        if (savedCameraId != null) {
            // resolveCamera maps "1:3" → the openable camera ID (e.g. "3") and correct facing
            val resolved = resolveCamera(savedCameraId)
            if (resolved != null) {
                frontFacing = resolved.first
                selectedCameraId = resolved.second
            } else {
                frontFacing = cameraIdMatchesFacing(savedCameraId, true) == true
                selectedCameraId = savedCameraId.takeIf { cameraIdMatchesFacing(it, frontFacing) == true }
            }
        } else {
            frontFacing = false
            selectedCameraId = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerReceiver(notificationChannelReceiver,
                IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startNotificationChannelCheckFallback()
        }
    }

    private fun startNotificationChannelCheckFallback() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) { handleStopService(); break }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            try { unregisterReceiver(notificationChannelReceiver) } catch (_: Exception) {}
        stopCamera()
        lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.stopStreamingServer() }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW))
        }
        val contentPI = PendingIntent.getActivity(this, 0,
            Intent(this, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP_SERVICE), PendingIntent.FLAG_IMMUTABLE)
        val restartPI = PendingIntent.getService(this, 2,
            Intent(this, StreamingService::class.java).setAction(ACTION_RESTART_NOTIFICATION), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android IP Camera Streaming")
            .setContentText("Camera server is running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(contentPI)
            .addAction(R.drawable.ic_notification, "Exit App", stopPI)
            .setDeleteIntent(restartPI)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    // MainActivity attaches PreviewView here; camera restarts when a surface is set while streaming.
    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        val shouldRun = encoders.any { it.hasClients() } || currentSurfaceProvider != null
        if (shouldRun) {
            launchMain { startCamera() }
        } else {
            launchMain { stopCamera() }
        }
    }
    fun isCameraRunning() = captureRunning
    fun switchCamera() {
        selectCamera(!frontFacing, null)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_CAMERA_ID, camId())
            .apply()
        if (captureRunning) launchMain { startCamera() }
    }

    fun getLocalIpAddress(): String {
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                ni.inetAddresses.toList().forEach { a ->
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "unknown"
                }
            }
        } catch (_: Exception) {}
        return "unknown"
    }

    // ---------------- server lifecycle (TLS cert + on-demand camera) ----------------

    fun startStreamingServer() {
        try {
            val secureStorage = SecureStorage(this)
            if (CertificateHelper.certificateExists(this)) {
                if (secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null).isNullOrEmpty()) {
                    File(filesDir, "personal_certificate.p12").let { if (it.exists()) it.delete() }
                    generateCertificateAndStart()
                } else initServer()
            } else generateCertificateAndStart()
        } catch (e: Exception) { Log.e(TAG, "start server: ${e.message}") }
    }

    private fun generateCertificateAndStart() {
        val pw = generateRandomPassword()
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@StreamingService, pw)
            if (certFile != null) {
                SecureStorage(this@StreamingService).putSecureString(SecureStorage.KEY_CERT_PASSWORD, pw)
                PreferenceManager.getDefaultSharedPreferences(this@StreamingService).edit().remove("certificate_path").apply()
                kotlinx.coroutines.delay(100)
                initServer()
            } else launch(Dispatchers.Main) {
                Toast.makeText(this@StreamingService, "Failed to generate certificate", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initServer() {
        if (streamingServerHelper == null) {
            streamingServerHelper = StreamingServerHelper(
                this,
                onLog = { Log.i(TAG, "Server: $it"); onLog?.invoke(it) },
                onClientConnected = {
                    launchMain {
                        onClientConnected?.invoke()
                        if (captureRunning) {
                            startCamera()
                        } else {
                            startCameraIfNeeded()
                        }
                    }
                },
                onClientDisconnected = {
                    launchMain {
                        onClientDisconnected?.invoke()
                        if (!encoders.any { it.hasClients() }) {
                            if (currentSurfaceProvider != null) {
                                startCamera()
                            } else {
                                stopCamera()
                            }
                        } else if (captureRunning) {
                            startCamera()
                        }
                    }
                },
                onControlCommand = { key, value, ts -> handleRemoteControl(key, value, ts) },
                onSnapshot = { id -> snapshot(id) }
            )
            // Initialize encoders with the streaming server helper
            h264StreamingEncoder = H264StreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "H264: $it"); onLog?.invoke(it) }
            mjpegStreamingEncoder = MjpegStreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "MJPEG: $it"); onLog?.invoke(it) }
        }
        streamingServerHelper?.startStreamingServer()
    }

    private fun launchMain(block: suspend () -> Unit) { lifecycleScope.launch(Dispatchers.Main) { block() } }

    // ---------------- camera ----------------

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun camId(): String = selectedCameraId ?: if (frontFacing) "front" else "back"

    private data class CameraToken(val logicalId: String, val physicalId: String?)

    private fun parseCameraToken(cameraId: String): CameraToken {
        val logicalId = cameraId.substringBefore(':')
        val physicalId = cameraId.substringAfter(':', "").takeIf { it.isNotBlank() }
        return CameraToken(logicalId, physicalId)
    }

    private fun cameraIdMatchesFacing(cameraId: String, front: Boolean): Boolean? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val token = parseCameraToken(cameraId)

        // If the logical ID is not in the camera list, it might be a physical ID
        // Try to find which logical camera it belongs to
        val actualLogicalId = if (cm.cameraIdList.contains(token.logicalId)) {
            token.logicalId
        } else {
            // Search for the logical camera that has this physical ID
            cm.cameraIdList.firstOrNull { logicalId ->
                try {
                    cm.getCameraCharacteristics(logicalId).physicalCameraIds.contains(token.logicalId)
                } catch (_: Exception) { false }
            } ?: token.logicalId
        }

        if (!cm.cameraIdList.contains(actualLogicalId)) {
            null
        } else {
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val ch = cm.getCameraCharacteristics(actualLogicalId)
            val actualPhysicalId = if (token.physicalId != null) {
                token.physicalId
            } else if (actualLogicalId != token.logicalId) {
                // The original value was a physical ID, use it as the physical ID
                token.logicalId
            } else {
                null
            }
            // On older devices, physical IDs might be valid camera IDs themselves
            val physicalMatches = actualPhysicalId == null ||
                ch.physicalCameraIds.contains(actualPhysicalId) ||
                cm.cameraIdList.contains(actualPhysicalId)
            physicalMatches && ch.get(CameraCharacteristics.LENS_FACING) == want
        }
    } catch (_: Exception) {
        null
    }

    private fun firstCameraIdForFacing(front: Boolean): String? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
    } catch (_: Exception) {
        null
    }

    private fun selectCamera(front: Boolean, cameraId: String?) {
        frontFacing = front
        selectedCameraId = cameraId
            ?.takeIf { cameraIdMatchesFacing(it, front) == true }
            ?: selectedCameraId?.takeIf { cameraIdMatchesFacing(it, front) == true }
            ?: firstCameraIdForFacing(front)
    }

    private fun camera1IndexForFacing(front: Boolean): Int {
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) { Camera.getCameraInfo(i, info); if (info.facing == want) return i }
        return 0
    }

    private fun camera1IndexForSelectedOrFacing(front: Boolean): Int {
        val selected = selectedCameraId?.toIntOrNull()
        return if (selected != null && selected in 0 until Camera.getNumberOfCameras()) {
            selected
        } else {
            camera1IndexForFacing(front)
        }
    }

    /** Is the current camera a LEGACY Camera2 HAL? (CameraX caps low there → use Camera1 for 1080p.) */
    private fun isLegacy(): Boolean = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = selectedCameraId
            ?.let { parseCameraToken(it).logicalId }
            ?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cameraId ->
                try {
                    cm.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == want
                } catch (_: Exception) {
                    false
                }
            }
            ?: cm.cameraIdList.first()
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    } catch (e: Exception) {
        Log.w(TAG, "isLegacy check failed, defaulting to false (camerax)", e)
        false
    }

    /** auto | camerax | camera1. auto → Camera1 on LEGACY HALs (true 1080p), CameraX everywhere else. */
    private fun chooseApi(): String =
        when (val pref = PreferenceManager.getDefaultSharedPreferences(this).getString("capture_api", "auto") ?: "auto") {
            "camerax", "camera1" -> pref
            else -> if (isLegacy()) "camera1" else "camerax"
        }

    /** Desired stream size from the resolution pref; "auto" → 1080p target (the device gives its best ≤ that). */
    private fun desiredSize(): Size {
        val caps = H264HardwareEncoder.caps()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val streamRes = prefs.getString("stream_res", "auto") ?: "auto"
        if (streamRes == "auto") {
            val quality = prefs.getString("camera_resolution", "low") ?: "low"
            val target = when (quality) {
                "high" -> Size(1280, 720)
                "medium" -> Size(960, 720)
                "low" -> Size(800, 600)
                else -> Size(800, 600)
            }
            return Size(minOf(target.width, caps.maxW), minOf(target.height, caps.maxH))
        }
        val m = Regex("(\\d+)x(\\d+)").find(streamRes)
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 1080
        return Size(minOf(w, caps.maxW), minOf(h, caps.maxH))
    }

    private suspend fun startCameraIfNeeded() { if (!captureRunning) startCamera() }

    private suspend fun startCamera(force: Boolean = false) = cameraMutex.withLock {
        Log.i(TAG, "startCamera() called. force=$force, selectedCameraId=$selectedCameraId, frontFacing=$frontFacing, currentSurfaceProvider=$currentSurfaceProvider, captureRunning=$captureRunning")
        if (!allPermissionsGranted()) {
            Log.w(TAG, "startCamera bypassed: permissions not granted")
            return@withLock
        }
        val hasClients = encoders.any { it.hasClients() }
        if (!force && !hasClients && currentSurfaceProvider == null) {
            Log.i(TAG, "startCamera bypassed: no force, no clients, and preview surface is null")
            return@withLock
        }
        stopCamera()
        h264StreamingEncoder?.awaitRelease()
        try {
            if (chooseApi() == "camera1") {
                // Camera1 → GL pipe → surface encoder (true 1920×1080 on legacy HALs).
                val want = desiredSize()
                val cap = Camera1Capture(camera1IndexForSelectedOrFacing(frontFacing), want.width, want.height)
                val pipe = newPipe(Size(cap.chosenW, cap.chosenH))
                cap.start(pipe.surfaceTexture)
                mjpegStreamingEncoder?.takeIf { it.hasClients() }?.let { mjpeg ->
                    cap.setPreviewFrameCallback { nv21 ->
                        mjpeg.processNv21Frame(nv21, cap.chosenW, cap.chosenH, cap.previewRotation)
                    }
                }
                backend = cap
                Log.i(TAG, "stream ${camId()} api=camera1 ${cap.chosenW}x${cap.chosenH}")
            } else {
                // CameraX → ImageAnalysis YUV → encoders + optional Preview on the phone.
                val want = desiredSize()
                val h264 = h264StreamingEncoder
                var h264SurfaceProvider: Preview.SurfaceProvider? = null

                if (h264 != null && h264.hasClients()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
                    val fpsCoerced = fps.coerceIn(1, 60)
                    val enc = H264HardwareEncoder(
                        want.width,
                        want.height,
                        fpsCoerced,
                        H264HardwareEncoder.bitrateFor(want.width, want.height),
                        true // useSurface = true
                    ) { d, k -> h264.broadcastH264(d, k) }

                    h264.setEncoder(enc)
                    streamingServerHelper?.resetH264Wait()

                    h264SurfaceProvider = Preview.SurfaceProvider { request ->
                        val surface = enc.inputSurface
                        if (surface != null && surface.isValid) {
                            Log.i(TAG, "Providing surface for H.264 encoder: $enc")
                            enc.hasSurfaceBeenProvided = true
                            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { result ->
                                Log.i(TAG, "H.264 encoder surface released by CameraX (result code: ${result.resultCode}) for: $enc")
                                enc.release()
                            }
                        } else {
                            request.willNotProvideSurface()
                        }
                    }
                }

                val showScreenPreview = !hasClients && currentSurfaceProvider != null
                backend = CameraXCapture(
                    this, this, frontFacing, selectedCameraId, want,
                    if (showScreenPreview) currentSurfaceProvider else null,
                    h264SurfaceProvider
                ) { img ->
                    try {
                        val activeEncoders = encoders.filter { it.hasClients() }
                        if (activeEncoders.isEmpty()) return@CameraXCapture
                        activeEncoders.forEach { it.processFrame(img) }
                    } finally {
                        img.close()
                    }
                }.also { it.start() }
                Log.i(TAG, "stream ${camId()} api=camerax")
            }
            // Persist the active camera id so encoders and helpers can read per-camera prefs
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@StreamingService)
                prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
            } catch (_: Exception) {}

            captureRunning = true
            encoders.forEach { it.start() }
            // Apply stored camera-level controls (exposure/zoom/focus) to the backend.
            // CameraX binding is asynchronous; retry in a background coroutine until ready.
            lifecycleScope.launch(Dispatchers.IO) {
                var attempts = 0
                while (attempts < 40) {
                    val b = backend
                    if (b == null) break
                    try {
                        if (b.ready) {
                            launchMain { try { applyStored(b) } catch (_: Exception) {} }
                            break
                        }
                    } catch (_: Exception) {}
                    attempts++
                    try { kotlinx.coroutines.delay(200) } catch (_: Exception) { break }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCamera failed", e)
            stopCamera()
        }
    }

    private fun stopCamera() {
        backend?.stop(); backend = null
        glPipe?.stop(); glPipe = null
        encoders.forEach { it.stop() }
        captureRunning = false
    }

    /** Surface-mode encoder + GL pipe, shared by both backends (CameraX Preview / Camera1 preview). */
    private fun newPipe(sz: Size): CameraGlPipe {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
        val fpsCoerced = fps.coerceIn(1, 60)
        val enc = H264HardwareEncoder(sz.width, sz.height, fpsCoerced, H264HardwareEncoder.bitrateFor(sz.width, sz.height), true) { d, k -> h264StreamingEncoder?.broadcastH264(d, k) }
        h264StreamingEncoder?.setEncoder(enc)
        streamingServerHelper?.resetH264Wait()
        return CameraGlPipe(enc.inputSurface!!, sz.width, sz.height, fpsCoerced).also { it.start(); glPipe = it }
    }

    private fun applyStored(b: CaptureBackend) {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId()
        if (id == null) return
        val phys = id.substringAfter(':', id)
        // Prefer token-specific prefs, fallback to physical id prefs
        val exposure = when {
            p.contains("exposure_$id") -> p.getString("exposure_$id", null)
            p.contains("exposure_$phys") -> p.getString("exposure_$phys", null)
            else -> null
        }
        exposure?.toIntOrNull()?.let { b.setExposure(it) }

        val zoom = when {
            p.contains("zoom_$id") -> p.getString("zoom_$id", null)
            p.contains("zoom_$phys") -> p.getString("zoom_$phys", null)
            else -> null
        }
        zoom?.toFloatOrNull()?.let { b.setZoom(it) }

        val focus = when {
            p.contains("focus_$id") -> p.getString("focus_$id", null)
            p.contains("focus_$phys") -> p.getString("focus_$phys", null)
            else -> null
        }
        focus?.toFloatOrNull()?.let { b.setManualFocus(it) }
    }

    // ---------------- snapshot (full resolution) ----------------

    /**
     * Full-resolution JPEG into RAM. [cameraId] is a Camera2 id ("0","1",…) or "front"/"back".
     *  - already live on that camera → video snapshot (takePicture during the stream, no interruption).
     *  - other camera, or camera off → start it (even with no live viewers), capture, then restore the
     *    original stream / stop (single HAL: one camera open at a time).
     */
    fun snapshot(cameraId: String): ByteArray? {
        synchronized(snapLock) {
            val target = resolveCamera(cameraId) ?: (frontFacing to selectedCameraId)
            val targetFront = target.first
            val targetCameraId = target.second
            val key = if (targetFront) "front" else "back"
            val hadViewers = streamingServerHelper?.getH264Clients()?.isNotEmpty() == true

            val live = backend
            if (live != null && targetFront == frontFacing && targetCameraId == selectedCameraId) {
                // "stream": reuse the last streamed frame (no camera rebind); "max": full-res capture.
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val idForPref = targetCameraId ?: key
                val res = prefs.getString("snapshot_res_$idForPref", "max")
                if (res == "stream") mjpegStreamingEncoder?.lastFrame()?.let { return it }
                return captureFrom(live, key) ?: snapCache[key]
            }

            val orig = frontFacing
            val origCameraId = selectedCameraId
            selectCamera(targetFront, targetCameraId)
            switchAndWait(force = true)                 // starts the camera even with no live viewers
            val jpeg = backend?.let { captureFrom(it, key) }
            frontFacing = orig
            selectedCameraId = origCameraId
            launchMain { if (hadViewers) startCamera() else stopCamera() }
            return jpeg ?: snapCache[key]
        }
    }

    /** camera= argument. Accepts "front"/"back"/"toggle" or a Camera2 id ("0","1",…). */
    private fun resolveCamera(value: String): Pair<Boolean, String?>? = when {
        value.equals("front", true) -> true to firstCameraIdForFacing(true)
        value.equals("back", true) -> false to firstCameraIdForFacing(false)
        value.equals("toggle", true) -> {
            val front = !frontFacing
            front to firstCameraIdForFacing(front)
        }
        else -> try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val token = parseCameraToken(value)

            // If the logical ID is not in the camera list, it might be a physical ID
            // Try to find which logical camera it belongs to
            val actualLogicalId = if (cm.cameraIdList.contains(token.logicalId)) {
                token.logicalId
            } else {
                // Search for the logical camera that has this physical ID
                cm.cameraIdList.firstOrNull { logicalId ->
                    try {
                        cm.getCameraCharacteristics(logicalId).physicalCameraIds.contains(token.logicalId)
                    } catch (_: Exception) { false }
                } ?: token.logicalId
            }

            if (cm.cameraIdList.contains(actualLogicalId)) {
                val ch = cm.getCameraCharacteristics(actualLogicalId)
                val actualPhysicalId = if (token.physicalId != null) {
                    token.physicalId
                } else if (actualLogicalId != token.logicalId) {
                    // The original value was a physical ID, use it as the physical ID
                    token.logicalId
                } else {
                    null
                }

                if (actualPhysicalId != null && !ch.physicalCameraIds.contains(actualPhysicalId)) {
                    null
                } else {
                    // On older devices, the physical camera is also a top-level camera ID;
                    // use it directly so CameraX can open it without physical-camera routing.
                    val finalCameraId = if (actualPhysicalId != null && cm.cameraIdList.contains(actualPhysicalId)) {
                        actualPhysicalId
                    } else if (actualPhysicalId != null) {
                        "$actualLogicalId:$actualPhysicalId"
                    } else {
                        actualLogicalId
                    }
                    when (ch.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> true to finalCameraId
                        CameraCharacteristics.LENS_FACING_BACK -> false to finalCameraId
                        else -> null
                    }
                }
            } else {
                null
            }
        } catch (_: Exception) { null }
    }

    /** Take one still from [b] (the backend autofocuses internally). Blocks the caller on a latch. */
    private fun captureFrom(b: CaptureBackend, key: String): ByteArray? {
        val latch = CountDownLatch(1); val out = arrayOfNulls<ByteArray>(1)
        launchMain { b.captureStill { out[0] = it; latch.countDown() } }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: Exception) {}
        out[0]?.let { snapCache[key] = it }
        return out[0]
    }

    /** Restart the camera on the new facing and wait until the backend is bound + 3A converges. */
    private fun switchAndWait(force: Boolean) {
        val l = CountDownLatch(1); launchMain { startCamera(force); l.countDown() }
        try { l.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}
        var n = 0; while (n < 40 && backend?.ready != true) { try { Thread.sleep(100) } catch (_: Exception) {}; n++ }
        // Let auto AE/AWB converge on the fresh camera. The front sensor is far slower in low light
        // (under-converged = blue/dark), so give it longer; the back locks quickly.
        try { Thread.sleep(if (frontFacing) 2500 else 700) } catch (_: Exception) {}
    }

    // ---------------- controls ----------------

    /**
     * GET /?<key>=<value> (proxied as /api/video/control):
     *   torch=on|off|toggle   focus_distance=<0..1|-1>
     *   exposure=<ev>   zoom=<ratio>
     *   camera=<id>|front|back|toggle   resolution=WxH   api=auto|camerax|camera1
     */
    /** Last accepted client timestamp per control key. */
    private val controlTimestamps = HashMap<String, Long>()

    /** True unless [ts] is older-or-equal to the last one accepted for [key] (0 = no ordering info). */
    private fun acceptControl(key: String, ts: Long): Boolean {
        if (ts == 0L) return true
        if (ts <= (controlTimestamps[key] ?: 0L)) return false
        controlTimestamps[key] = ts
        return true
    }

    // Synchronized so the timestamp check and the (async) dispatch stay ordered per request.
    @Synchronized
    private fun handleRemoteControl(key: String, value: String, ts: Long = 0L) {
        if (!acceptControl(key, ts)) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Use the stored camera preference key when persisting per-camera settings so
        // reads from getStreamSettings() (which uses the saved `camera_id`) match.
        val storedCameraPref = prefs.getString(PREF_CAMERA_ID, null)
        val id = storedCameraPref ?: camId()
        // physical id fallback (if id is "logical:physical" this extracts physical)
        val physicalId = id?.substringAfter(':', id ?: "") ?: ""
        when (key) {
            "torch" -> {
                val current = backend?.getTorch() ?: false
                val next = when (value.lowercase()) {
                    "on" -> true
                    "off" -> false
                    "toggle" -> !current
                    else -> return
                }
                launchMain { backend?.setTorch(next) }
            }
            "exposure" -> {
                val ev = value.toIntOrNull() ?: return
                prefs.edit().putString("exposure_$id", ev.toString()).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("exposure_$physicalId", ev.toString()).apply()
                launchMain { backend?.setExposure(ev) }
            }
            "zoom" -> {
                val z = value.toFloatOrNull() ?: return
                val zStr = String.format(Locale.US, "%.1f", z)
                prefs.edit().putString("zoom_$id", zStr).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("zoom_$physicalId", zStr).apply()
                launchMain { backend?.setZoom(z) }
            }
            "focus_distance" -> {
                val f = value.toFloatOrNull() ?: return
                if (f < 0f) {
                    prefs.edit().remove("focus_$id").apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().remove("focus_$physicalId").apply()
                } else {
                    prefs.edit().putString("focus_$id", f.coerceIn(0f, 1f).toString()).apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("focus_$physicalId", f.coerceIn(0f, 1f).toString()).apply()
                }
                launchMain { backend?.setManualFocus(f) }
            }
            "snapshot_res" -> {
                if (value == "max" || value == "stream") {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    val sid = camId()
                    if (sid != null) {
                        prefs.edit().putString("snapshot_res_$sid", value).apply()
                        val phys = sid.substringAfter(':', sid)
                        if (phys.isNotBlank() && phys != sid) prefs.edit().putString("snapshot_res_$phys", value).apply()
                    }
                }
            }
            "camera" -> {
                val keepTorchOn = backend?.getTorch() == true
                val target = resolveCamera(value) ?: return
                selectCamera(target.first, target.second)
                // Save the original value (e.g. "1:3") so the dropdown can match it;
                // selectedCameraId may be the bare physical ID (e.g. "3") used by CameraX.
                // Persist the canonical camera id (what camId() will return) so per-camera
                // preferences are stored/loaded under the same key format.
                prefs.edit()
                    .putString(PREF_CAMERA_ID, camId())
                    .apply()
                launchMain {
                    if (captureRunning) {
                        startCamera()
                        if (keepTorchOn) backend?.setTorch(true)
                    }
                }
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high")) {
                    prefs.edit().putString("camera_resolution", value).apply()
                    launchMain { if (captureRunning) startCamera() }
                } else if (value == "auto" || value == "max" || Regex("\\d+x\\d+").matches(value)) {
                    prefs.edit().putString("stream_res", if (value == "max") "auto" else value).apply()
                    launchMain { if (captureRunning) startCamera() }
                }
            }
            "rotate" -> {
                val angle = value.toIntOrNull() ?: return
                // normalize to 0..359
                val norm = ((angle % 360) + 360) % 360
                // Persist rotate per-camera
                prefs.edit().putInt("camera_rotate_$id", norm).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putInt("camera_rotate_$physicalId", norm).apply()
            }
            "scale" -> {
                // Persist scale per-camera (string like "1.0")
                prefs.edit().putString("stream_scale_$id", value).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("stream_scale_$physicalId", value).apply()
            }
            "contrast" -> {
                // Persist contrast per-camera
                prefs.edit().putString("camera_contrast_$id", value).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("camera_contrast_$physicalId", value).apply()
            }
            "api" -> {
                if (value in listOf("auto", "camerax", "camera1")) {
                    prefs.edit().putString("capture_api", value).apply()
                    launchMain { if (captureRunning) startCamera() }
                }
            }
        }

        // Delegate to encoders for codec-specific controls
        encoders.forEach { encoder ->
            if (encoder.handleRemoteControl(key, value)) {
                return // Command was handled by an encoder
            }
        }
    }


    private fun generateRandomPassword(): String {
        val r = SecureRandom()
        val all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { all[r.nextInt(all.length)] }.joinToString("")
    }
}

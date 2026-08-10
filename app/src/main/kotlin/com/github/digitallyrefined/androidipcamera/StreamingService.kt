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

/** (success, httpStatusLine, jsonBody) */
private typealias RecordingResponse = Triple<Boolean, String, String>

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

    @Volatile private var glPipe: CameraGlPipe? = null          // Camera1 H.264 GL pipe
    @Volatile private var cameraXGlPipe: CameraGlPipe? = null   // CameraX H.264 GL pipe
    @Volatile private var backend: CaptureBackend? = null   // CameraXCapture (default) or Camera1Capture (legacy)
    @Volatile private var captureRunning = false
    /**
     * True while the camera is open *only* to keep the torch lit. The camera is otherwise opened on
     * demand, so without this the torch would die the moment the last viewer left — and a blanket
     * "don't release while the torch is on" guard would leak a camera session that nothing closes.
     */
    @Volatile private var cameraHeldForTorch = false
    @Volatile private var localRecorder: LocalRecorder? = null

    @Volatile private var frontFacing = false             // false = back camera (read across threads)
    @Volatile private var selectedCameraId: String? = null

    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    /** True while a /video/snapshot request is running; lets startCamera() know frames are needed
     *  so a frame-less CameraX backend falls back to Camera1 before the capture attempt. */
    @Volatile private var snapshotInProgress = false

    /** True while /record/start is bringing the H.264 encoder up with no viewers connected. Makes
     *  startCamera() deliver frames and initialize the encoder so recording works without streaming.
     *  Cleared once localRecorder takes over (or the start fails), so the no-viewer baseline returns. */
    @Volatile private var recordingNeeded = false

    /** Latest good full-res capture per camera ("front"/"back"), with the time it was taken. */
    private data class CachedSnapshot(val jpeg: ByteArray, val atMs: Long)
    private val snapCache = ConcurrentHashMap<String, CachedSnapshot>()
    private val snapLock = Any()
    private val cameraMutex = Mutex()
    private var pendingStartJob: kotlinx.coroutines.Job? = null
    @Volatile private var cameraCloseStartTime: Long = 0L

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
        /** "on"/"off". Already surfaced by /info.json, which until now reported a value nobody wrote. */
        private const val PREF_CAMERA_TORCH = "camera_torch"
        /** Hard cap for a single /video/snapshot pipeline run (switch + capture). */
        private const val SNAPSHOT_DEADLINE_MS = 12_000L
        /** A cached capture older than this is treated as stale and never served. */
        private const val SNAPSHOT_CACHE_MAX_AGE_MS = 5_000L
        /** "Match stream" only reuses a streamed frame that is at most this old. */
        private const val SNAPSHOT_STREAM_FRESH_MS = 3_000L
        /** CameraX grace period for its first analysis frame; if none arrives the session never
         *  configured and the service falls back to the Camera1 backend. */
        private const val CAMERAX_FRAME_GRACE_MS = 8_000L
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
        const val ACTION_RESTART_SERVER = "com.github.digitallyrefined.androidipcamera.RESTART_SERVER"
        const val ACTION_START_SERVER = "com.github.digitallyrefined.androidipcamera.START_SERVER"
    }

    inner class LocalBinder : Binder() { fun getService(): StreamingService = this@StreamingService }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return binder }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> { handleStopService(); return START_NOT_STICKY }
            ACTION_RESTART_NOTIFICATION -> startForegroundService()
            ACTION_RESTART_SERVER -> restartServer()
            // Cold start with no activity bound (e.g. from BootReceiver): the activity normally
            // calls startStreamingServer() after binding, so start it here instead.
            ACTION_START_SERVER -> startStreamingServer()
        }
        // A null intent means the system re-created this START_STICKY service after killing the
        // process. Nothing starts the server in that path — no activity binds, and onCreate() only
        // posts the notification — so the service comes back looking healthy (foreground
        // notification, camera available) with nothing listening on the port. startStreamingServer()
        // early-returns when the socket is already open, so this is safe to reach on any path.
        if (intent == null) startStreamingServer()
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
        safeStopRecording()
        // The service is going away, so the torch has nothing left to keep the camera open for;
        // clearing this first stops the guard in stopCamera() from leaking the session.
        cameraHeldForTorch = false
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
        // Recording owns the camera session: stopping or restarting the camera now would tear
        // down the encoder and finalize the file, so leave the pipeline alone until it stops.
        if (localRecorder?.isRecording == true) return
        val shouldRun = encoders.any { it.hasClients() } || currentSurfaceProvider != null
        if (shouldRun) {
            debouncedStartCamera()
        } else {
            launchMain { stopCamera() }
        }
    }
    fun isCameraRunning() = captureRunning
    fun hasActiveClients() = encoders.any { it.hasClients() } || localRecorder?.isRecording == true
    fun switchCamera() {
        selectCamera(!frontFacing, null)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_CAMERA_ID, camId())
            .apply()
        if (captureRunning) debouncedStartCamera(force = true)
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
                        // While recording, the camera is already running and feeding the encoder; a
                        // restart would finalize the recording, and new viewers pick up the existing
                        // stream, so no reconfiguration is needed.
                        if (localRecorder?.isRecording == true) return@launchMain
                        if (captureRunning) {
                            debouncedStartCamera()
                        } else {
                            startCameraIfNeeded()
                        }
                    }
                },
                onClientDisconnected = {
                    launchMain {
                        onClientDisconnected?.invoke()
                        // Recording owns the camera: stopping or restarting it now would finalize
                        // the file, and remaining viewers keep their existing stream as-is.
                        if (localRecorder?.isRecording == true) return@launchMain
                        if (!hasActiveClients()) {
                            if (currentSurfaceProvider == null) {
                                // Last client gone and no on-screen preview: shed the H.264
                                // surface encoder / GL pipe and keep the camera warm in a plain,
                                // snapshot-friendly configuration. Force ignores the hasClients()
                                // bypass so the reconfiguration actually happens.
                                pendingStartJob?.cancel()
                                pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
                                    kotlinx.coroutines.delay(300)
                                    if (!captureRunning) return@launch   // camera stopped meanwhile
                                    if (encoders.any { it.hasClients() } || currentSurfaceProvider != null) return@launch
                                    startCamera(force = true)
                                }
                            } else {
                                debouncedStartCamera()
                            }
                        } else if (captureRunning) {
                            debouncedStartCamera()
                        }
                    }
                },
                onControlCommand = { key, value, ts -> handleRemoteControl(key, value, ts) },
                onSnapshot = { id -> snapshot(id) },
                onRecordStart = { startRecording() },
                onRecordStop = { stopRecording() },
                onRecordStatus = { getRecordingStatus() }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.getCameraCharacteristics(logicalId).physicalCameraIds.contains(token.logicalId)
                    } else false
                } catch (_: Throwable) { false }
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
            val hasPhysicalCameraIds = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ch.physicalCameraIds.contains(actualPhysicalId)
            val physicalMatches = actualPhysicalId == null ||
                hasPhysicalCameraIds ||
                cm.cameraIdList.contains(actualPhysicalId)
            physicalMatches && ch.get(CameraCharacteristics.LENS_FACING) == want
        }
    } catch (_: Throwable) {
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

    private fun cameraHardwareLevel(cameraId: String?): Int? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cameraId
            ?.let { parseCameraToken(it).logicalId }
            ?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.firstOrNull()
        id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }
    } catch (_: Exception) { null }

    private fun supportsSurfaceEncoder(): Boolean {
        if (frontFacing) return false
        val level = cameraHardwareLevel(selectedCameraId) ?: return false
        return level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
               level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
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

    /**
     * Debounced camera restart. Coalesces rapid [startCamera] calls (e.g. when a settings
     * change fires both a resolution and a camera-restart broadcast) into a single restart
     * after [delayMs]. Immediate restarts (force=true) bypass the debounce.
     */
    private fun debouncedStartCamera(force: Boolean = false, delayMs: Long = 300) {
        if (force) {
            pendingStartJob?.cancel()
            pendingStartJob = null
            launchMain { startCamera(force = true) }
            return
        }
        pendingStartJob?.cancel()
        pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(delayMs)
            startCamera()
        }
    }

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
            val want = desiredSize()
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val camxUnusable = prefs.getBoolean("camera2_unusable", false)
            // The on-phone preview is only wired to CameraX, so the idle/preview-only state is
            // ALWAYS CameraX — its Preview use case renders even on devices whose Camera2 session
            // never configures ImageAnalysis/ImageCapture. The backend choice only matters when
            // frames are actually needed (clients / recording / snapshot in flight): then honor the
            // API preference and the known-bad-Camera2 flag, which route to the Camera1 backend.
            val needsFrames = snapshotInProgress || recordingNeeded ||
                encoders.any { it.hasClients() } || localRecorder?.isRecording == true
            val useCamera1 = needsFrames && (chooseApi() == "camera1" || camxUnusable)
            if (useCamera1) {
                if (camxUnusable) {
                    Log.i(TAG, "Camera2 known unusable on this device and frames are needed — using Camera1 backend")
                }
                startCamera1Backend(want)
            } else {
                startCameraXBackend(want)
                // CameraX can bind without the session ever configuring (e.g. "Unable to configure
                // camera ... TimeoutException") — no analysis frames ever arrive. Detect that and fall
                // back to the Camera1 backend so streaming and snapshots keep working on such devices.
                if (needsFrames && !waitForCameraXFrame()) {
                    Log.w(TAG, "CameraX bound but no frames within ${CAMERAX_FRAME_GRACE_MS}ms — Camera2 session config failed on this device; falling back to Camera1")
                    stopCamera()
                    // Remember the device is Camera2-broken (not the API preference) so the CameraX
                    // on-phone preview can come back once frames are no longer needed, while future
                    // frame-needing starts go straight to Camera1 without another 8s wait.
                    prefs.edit().putBoolean("camera2_unusable", true).apply()
                    startCamera1Backend(want)
                }
            }
            // Persist the active camera id so encoders and helpers can read per-camera prefs
            try {
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

    /** True once the CameraX backend has delivered an analysis frame (i.e. the session configured —
     *  some HALs bind without ever configuring, which produces no frames at all). */
    private suspend fun waitForCameraXFrame(): Boolean {
        val b = backend
        if (b !is CameraXCapture) return true
        var waited = 0L
        while (!b.hasProducedFrame && waited < CAMERAX_FRAME_GRACE_MS) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        return b.hasProducedFrame
    }

    private fun startCamera1Backend(want: Size) {
        // Camera1 → GL pipe → surface encoder (true 1920×1080 on legacy HALs).
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
    }

    private fun startCameraXBackend(want: Size) {
        // CameraX → ImageAnalysis YUV → encoders + optional Preview on the phone.
        val h264 = h264StreamingEncoder
        var h264SurfaceProvider: Preview.SurfaceProvider? = null

        if (h264 != null && (h264.hasClients() || recordingNeeded || localRecorder?.isRecording == true) && supportsSurfaceEncoder()) {
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

            // Route CameraX frames through a GL pipe so mirror can be applied
            // without restarting the camera (the pipe reads mirror on each frame).
            val pipe = CameraGlPipe(enc.inputSurface!!, want.width, want.height, fpsCoerced, standardBuffer = true).also {
                it.mirror = readMirrorPref()
                it.start()
                cameraXGlPipe = it
            }

            h264SurfaceProvider = Preview.SurfaceProvider { request ->
                val surface = pipe.inputSurface
                if (surface != null && surface.isValid) {
                    Log.i(TAG, "Providing CameraX H.264 surface via GL pipe (mirror=${pipe.mirror})")
                    enc.hasSurfaceBeenProvided = true
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { result ->
                        Log.i(TAG, "CameraX H.264 surface released by CameraX (result code: ${result.resultCode})")
                    }
                } else {
                    request.willNotProvideSurface()
                }
            }
        } else if (h264 != null) {
            // Fall back to YUV mode immediately (previous method)
            h264.setEncoder(null)
        }

        val showScreenPreview = !encoders.any { it.hasClients() } && currentSurfaceProvider != null
        backend = CameraXCapture(
            this, this, frontFacing, selectedCameraId, want,
            if (showScreenPreview) currentSurfaceProvider else null,
            h264SurfaceProvider,
            onEncoderSurfaceFallback = {
                Log.i(TAG, "CameraX SurfaceProvider fallback triggered: switching H.264 to software YUV mode")
                h264?.setEncoder(null)
            }
        ) { img ->
            try {
                cameraXGlPipe?.let { pipe ->
                    pipe.frameWidth = img.width; pipe.frameHeight = img.height
                }
                val recording = localRecorder?.isRecording == true || recordingNeeded
                val activeEncoders = encoders.filter { it.hasClients() }
                if (recording && h264StreamingEncoder != null) {
                    h264StreamingEncoder?.processFrame(img)
                    activeEncoders.filter { it != h264StreamingEncoder }.forEach { it.processFrame(img) }
                } else if (activeEncoders.isNotEmpty()) {
                    activeEncoders.forEach { it.processFrame(img) }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "ImageAnalysis OOM — dropping frame: ${e.message}")
                DeviceMemoryHelper.updateMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                try { System.gc() } catch (_: Exception) {}
            } finally {
                img.close()
            }
        }.also { it.start() }
        Log.i(TAG, "stream ${camId()} api=camerax")
    }

    private fun stopCamera() {
        // Single choke point for every on-demand release path (last viewer left, preview hidden,
        // stream reconfigured). The torch-off handler clears the flag before calling in, so it is
        // the one caller that can still close a torch-held session.
        if (cameraHeldForTorch) return
        safeStopRecording()
        cameraCloseStartTime = System.currentTimeMillis()
        backend?.stop(); backend = null
        val closeDuration = System.currentTimeMillis() - cameraCloseStartTime
        if (closeDuration > 1000) {
            Log.w(TAG, "Camera close took ${closeDuration}ms — HAL is slow to release resources")
        }
        cameraXGlPipe?.stop(); cameraXGlPipe = null
        glPipe?.stop(); glPipe = null
        encoders.forEach { it.stop() }
        captureRunning = false
    }

    private fun readMirrorPref(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId()
        if (id == null) return false
        val phys = id.substringAfter(':', id)
        return when {
            prefs.contains("mirror_$id") -> prefs.getString("mirror_$id", null)?.toBoolean() ?: false
            prefs.contains("mirror_$phys") -> prefs.getString("mirror_$phys", null)?.toBoolean() ?: false
            else -> false
        }
    }

    /** Surface-mode encoder + GL pipe, used by Camera1 H.264. */
    private fun newPipe(sz: Size): CameraGlPipe {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
        val fpsCoerced = fps.coerceIn(1, 60)
        val enc = H264HardwareEncoder(sz.width, sz.height, fpsCoerced, H264HardwareEncoder.bitrateFor(sz.width, sz.height), true) { d, k -> h264StreamingEncoder?.broadcastH264(d, k) }
        h264StreamingEncoder?.setEncoder(enc)
        streamingServerHelper?.resetH264Wait()
        return CameraGlPipe(enc.inputSurface!!, sz.width, sz.height, fpsCoerced).also {
            it.mirror = readMirrorPref()
            it.start()
            glPipe = it
        }
    }

    private fun applyStored(b: CaptureBackend) {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        // The torch is device-level, not per-camera, so it is restored ahead of the early return
        // below — the per-camera settings need a resolved camera id, the torch does not.
        if (p.getString(PREF_CAMERA_TORCH, "off") == "on") b.setTorch(true)
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
     * The whole pipeline is bounded by [SNAPSHOT_DEADLINE_MS] so a request can never hang the caller.
     * A stale photo is never served: a failed capture only falls back to the last capture if that
     * capture is still fresh ([SNAPSHOT_CACHE_MAX_AGE_MS]).
     */
    fun snapshot(cameraId: String): ByteArray? {
        synchronized(snapLock) {
            snapshotInProgress = true
            try {
                val deadline = System.currentTimeMillis() + SNAPSHOT_DEADLINE_MS
                val target = resolveCamera(cameraId) ?: (frontFacing to selectedCameraId)
                val targetFront = target.first
                val targetCameraId = target.second
                val key = if (targetFront) "front" else "back"
                val hadViewers = hasActiveClients() || currentSurfaceProvider != null

                // Switch to the target camera, capture, then restore the original stream / stop.
                fun captureViaRebind(): ByteArray? {
                    val orig = frontFacing
                    val origCameraId = selectedCameraId
                    selectCamera(targetFront, targetCameraId)
                    switchAndWait(force = true, deadline)   // starts the camera even with no live viewers
                    val jpeg = backend?.let { captureFrom(it, key, deadline) }
                    frontFacing = orig
                    selectedCameraId = origCameraId
                    launchMain { if (hadViewers) startCamera() else stopCamera() }
                    return jpeg ?: freshCached(key)
                }

                val live = backend
                if (live != null && targetFront == frontFacing && targetCameraId == selectedCameraId) {
                    // "stream": reuse the last streamed frame (no camera rebind); "max": full-res capture.
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    val idForPref = targetCameraId ?: key
                    val res = prefs.getString("snapshot_res_$idForPref", "max")
                    if (res == "stream") {
                        // Only trust the cached stream frame while it's actually fresh — with no live
                        // MJPEG viewers no frames are being encoded, so it could be minutes old.
                        mjpegStreamingEncoder?.lastFrameFresh(SNAPSHOT_STREAM_FRESH_MS)?.let { return it }
                    }
                    // A CameraX backend bound but delivering no analysis frames can't take a still
                    // either (ImageCapture shares the same dead session). Give a fresh bind a moment
                    // for its first frame (healthy devices frame within ~1s); if none ever arrives the
                    // session is broken — remember it and rebind via startCamera() so it falls back to
                    // Camera1 before capturing.
                    if (live is CameraXCapture && !live.hasProducedFrame) {
                        if (prefs.getBoolean("camera2_unusable", false)) {
                            // Known-broken device: go straight to Camera1, no grace wait.
                            return captureViaRebind()
                        }
                        var waited = 0L
                        while (!live.hasProducedFrame && waited < CAMERAX_FRAME_GRACE_MS &&
                            System.currentTimeMillis() < deadline) {
                            try { Thread.sleep(100) } catch (_: Exception) {}
                            waited += 100
                        }
                        if (live.hasProducedFrame) {
                            return captureFrom(live, key, deadline) ?: freshCached(key)
                        }
                        prefs.edit().putBoolean("camera2_unusable", true).apply()
                        return captureViaRebind()
                    }
                    return captureFrom(live, key, deadline) ?: freshCached(key)
                }
                return captureViaRebind()
            } finally {
                snapshotInProgress = false
            }
        }
    }

    /** Last good capture for [key], but only if it is still fresh enough to count as "now". */
    private fun freshCached(key: String): ByteArray? =
        snapCache[key]?.takeIf { System.currentTimeMillis() - it.atMs <= SNAPSHOT_CACHE_MAX_AGE_MS }?.jpeg

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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            cm.getCameraCharacteristics(logicalId).physicalCameraIds.contains(token.logicalId)
                        } else false
                    } catch (_: Throwable) { false }
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

                val hasPhysicalCameraIds = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    ch.physicalCameraIds.contains(actualPhysicalId)
                if (actualPhysicalId != null && !hasPhysicalCameraIds) {
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
        } catch (_: Throwable) { null }
    }

    /** Take one still from [b] (the backend autofocuses internally). Blocks the caller on a latch. */
    private fun captureFrom(b: CaptureBackend, key: String, deadlineMs: Long): ByteArray? {
        // A capture can fail transiently (camera mid-restart, another still's restore rebind still
        // running, HAL briefly busy). Retry cheaply until the deadline rather than giving up on the
        // first miss, and always capture from the CURRENT backend — a restart/fallback can swap it
        // mid-snapshot, and a stale released instance can only fail.
        var result: ByteArray? = null
        while (System.currentTimeMillis() < deadlineMs) {
            val target = backend ?: b
            val latch = CountDownLatch(1)
            val out = arrayOfNulls<ByteArray>(1)
            launchMain { target.captureStill { out[0] = it; latch.countDown() } }
            val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(100L)
            try { latch.await(remaining, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            result = out[0]
            if (result != null) break
            try { Thread.sleep(150) } catch (_: Exception) {}
        }
        result?.let {
            val maxSize = DeviceMemoryHelper.maxSnapshotBytes(this@StreamingService)
            if (it.size <= maxSize) {
                snapCache[key] = CachedSnapshot(it, System.currentTimeMillis())
            } else {
                Log.w(TAG, "Snapshot too large to cache (${it.size} bytes > $maxSize), skipping cache")
            }
        }
        return result
    }

    /** Restart the camera on the new facing and wait until the backend is bound + 3A converges. */
    private fun switchAndWait(force: Boolean, deadlineMs: Long) {
        val l = CountDownLatch(1); launchMain { startCamera(force); l.countDown() }
        try { l.await((deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        var n = 0; while (n < 40 && backend?.ready != true && System.currentTimeMillis() < deadlineMs) {
            try { Thread.sleep(100) } catch (_: Exception) {}; n++
        }
        // Let auto AE/AWB converge on the fresh camera. The front sensor is far slower in low light
        // (under-converged = blue/dark), so give it longer; the back locks quickly. Never exceed the deadline.
        val convergeMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        if (convergeMs > 0) {
            try { Thread.sleep(minOf(convergeMs, if (frontFacing) 2500 else 700)) } catch (_: Exception) {}
        }
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
                val current = backend?.getTorch()
                    ?: (prefs.getString(PREF_CAMERA_TORCH, "off") == "on")
                val next = when (value.lowercase()) {
                    "on" -> true
                    "off" -> false
                    "toggle" -> !current
                    else -> return
                }
                // Persist before opening the camera: applyStored() reads this pref while the
                // backend is being set up, which is what makes the torch survive the camera
                // restarts that happen every time a stream starts or stops.
                prefs.edit().putString(PREF_CAMERA_TORCH, if (next) "on" else "off").apply()
                launchMain {
                    if (next && !captureRunning) {
                        // Nothing is streaming, so there is no backend to talk to. Open the camera
                        // just for the torch and record that this session is ours to close.
                        cameraHeldForTorch = true
                        startCamera(force = true)
                    }
                    backend?.setTorch(next)
                    if (!next && cameraHeldForTorch) {
                        cameraHeldForTorch = false
                        if (!hasActiveClients() && currentSurfaceProvider == null) stopCamera()
                    }
                }
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
                        debouncedStartCamera(force = true)
                        if (keepTorchOn) backend?.setTorch(true)
                    }
                }
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high")) {
                    prefs.edit().putString("camera_resolution", value).apply()
                    debouncedStartCamera()
                } else if (value == "auto" || value == "max" || Regex("\\d+x\\d+").matches(value)) {
                    prefs.edit().putString("stream_res", if (value == "max") "auto" else value).apply()
                    debouncedStartCamera()
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
            "mirror" -> {
                if (value in listOf("true", "false")) {
                    prefs.edit().putString("mirror_$id", value).apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("mirror_$physicalId", value).apply()
                    val enabled = value == "true"
                    glPipe?.mirror = enabled
                    cameraXGlPipe?.mirror = enabled
                }
            }
            "api" -> {
                if (value in listOf("auto", "camerax", "camera1")) {
                    prefs.edit().putString("capture_api", value).apply()
                    // Explicitly choosing CameraX again re-tests the device (clears the remembered
                    // "Camera2 session never configures" flag).
                    if (value != "camera1") prefs.edit().putBoolean("camera2_unusable", false).apply()
                    debouncedStartCamera()
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


    // ---------------- local recording ----------------

    fun startRecording(): RecordingResponse {
        if (localRecorder?.isRecording == true) return Triple(false, "409 Conflict",
            """{"error":"already_recording","message":"Recording is already in progress","uri":"${localRecorder?.recordingLocation() ?: ""}"}"""
        )

        // Make the camera deliver frames and initialize the H.264 encoder even with no viewers
        // connected; cleared once localRecorder takes over (or the start fails).
        recordingNeeded = true

        // Ensure camera and H.264 encoder are started on demand
        if (!captureRunning || !hasActiveClients()) {
            val latch = CountDownLatch(1)
            launchMain {
                startCamera(force = true)
                latch.countDown()
            }
            try { latch.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}
        }

        var enc = h264StreamingEncoder?.h264HardwareEncoder
        var attempts = 0
        while (enc == null && attempts < 20) {
            try { Thread.sleep(100) } catch (_: Exception) {}
            enc = h264StreamingEncoder?.h264HardwareEncoder
            attempts++
        }

        if (enc == null) {
            recordingNeeded = false
            restoreNoViewerCameraState()
            return Triple(false, "503 Service Unavailable",
                """{"error":"no_encoder","message":"Failed to initialize H.264 encoder"}"""
            )
        }

        return try {
            val recorder = LocalRecorder(this, enc.width, enc.height)
            recorder.start()
            enc.onRecordFrame = { bytes, isKey, pts -> recorder.feedFrame(bytes, isKey, pts) }
            enc.requestKeyFrame()
            localRecorder = recorder
            recordingNeeded = false
            Triple(true, "201 Created", recorder.statusJson())
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recordingNeeded = false
            restoreNoViewerCameraState()
            Triple(false, "500 Internal Server Error",
                """{"error":"start_failed","message":"${e.message?.replace("\"", "'") ?: "unknown error"}"}"""
            )
        }
    }

    fun stopRecording(): RecordingResponse {
        val recorder = localRecorder ?: return Triple(false, "409 Conflict",
            """{"error":"not_recording","message":"No recording is in progress"}"""
        )
        return try {
            h264StreamingEncoder?.h264HardwareEncoder?.onRecordFrame = null
            val finalStatus = recorder.statusJson()
            recorder.stop()
            localRecorder = null

            // If no remote network streaming clients are connected, update camera state
            if (!encoders.any { it.hasClients() }) {
                restoreNoViewerCameraState()
            }
            Triple(true, "200 OK", finalStatus)
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            localRecorder = null
            Triple(false, "500 Internal Server Error",
                """{"error":"stop_failed","message":"${e.message?.replace("\"", "'") ?: "unknown error"}"}"""
            )
        }
    }

    fun getRecordingStatus(): String {
        return localRecorder?.statusJson()
            ?: """{"recording":false}"""
    }

    /**
     * Bring the camera back to the no-viewer baseline: fully stop it when nothing else needs it,
     * or keep/restart it in preview-only mode when the on-phone preview surface is attached.
     * Does nothing while network streaming clients are still connected.
     */
    private fun restoreNoViewerCameraState() {
        if (encoders.any { it.hasClients() }) return
        launchMain {
            if (currentSurfaceProvider != null) {
                debouncedStartCamera()
            } else {
                stopCamera()
            }
        }
    }

    /** Safely stops any active recording (used in stopCamera/onDestroy safety nets). */
    private fun safeStopRecording() {
        try {
            h264StreamingEncoder?.h264HardwareEncoder?.onRecordFrame = null
            localRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "safeStopRecording: ${e.message}")
        } finally {
            localRecorder = null
        }
    }

    private fun generateRandomPassword(): String {
        val r = SecureRandom()
        val all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { all[r.nextInt(all.length)] }.joinToString("")
    }
}

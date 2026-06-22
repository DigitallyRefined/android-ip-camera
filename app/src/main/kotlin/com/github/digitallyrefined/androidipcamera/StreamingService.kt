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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
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

    private var h264Encoder: H264Encoder? = null
    @Volatile private var glPipe: CameraGlPipe? = null
    @Volatile private var backend: CaptureBackend? = null   // CameraXCapture (default) or Camera1Capture (legacy)
    @Volatile private var captureRunning = false

    @Volatile private var frontFacing = false             // false = back camera (read across threads)

    private val snapCache = ConcurrentHashMap<String, ByteArray>()  // latest JPEG per camera (RAM)
    private val snapLock = Any()

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
        private const val PREF_LAST_CAMERA_FACING = "last_camera_facing"
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
        frontFacing = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_LAST_CAMERA_FACING, "back") == "front"
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

    // MainActivity calls these; headless server is driven by client connections only.
    fun setPreviewSurface(@Suppress("UNUSED_PARAMETER") surfaceProvider: Any?) {}
    fun isCameraRunning() = captureRunning
    fun switchCamera() {
        frontFacing = !frontFacing
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_LAST_CAMERA_FACING, if (frontFacing) "front" else "back").apply()
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
                onClientConnected = { launchMain { onClientConnected?.invoke(); startCameraIfNeeded() } },
                onClientDisconnected = {
                    if (streamingServerHelper?.getH264Clients()?.isEmpty() == true)
                        launchMain { stopCamera(); onClientDisconnected?.invoke() }
                },
                onControlCommand = { key, value -> handleRemoteControl(key, value) },
                onSnapshot = { id -> snapshot(id) }
            )
        }
        streamingServerHelper?.startStreamingServer()
    }

    private fun launchMain(block: () -> Unit) { lifecycleScope.launch(Dispatchers.Main) { block() } }

    // ---------------- camera ----------------

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun camId(): String = if (frontFacing) "front" else "back"   // camera identity = facing

    private fun camera1IndexForFacing(front: Boolean): Int {
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) { Camera.getCameraInfo(i, info); if (info.facing == want) return i }
        return 0
    }

    /** Is the current camera a LEGACY Camera2 HAL? (CameraX caps low there → use Camera1 for 1080p.) */
    private fun isLegacy(): Boolean = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.first()
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    } catch (e: Exception) { true }

    /** auto | camerax | camera1. auto → Camera1 on LEGACY HALs (true 1080p), CameraX everywhere else. */
    private fun chooseApi(): String =
        when (val pref = PreferenceManager.getDefaultSharedPreferences(this).getString("capture_api", "auto") ?: "auto") {
            "camerax", "camera1" -> pref
            else -> if (isLegacy()) "camera1" else "camerax"
        }

    /** Desired stream size from the resolution pref; "auto" → 1080p target (the device gives its best ≤ that). */
    private fun desiredSize(): Size {
        val caps = H264Encoder.caps()
        val m = Regex("(\\d+)x(\\d+)").find(
            PreferenceManager.getDefaultSharedPreferences(this).getString("stream_res", "auto") ?: "auto")
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 1080
        return Size(minOf(w, caps.maxW), minOf(h, caps.maxH))
    }

    private fun startCameraIfNeeded() { if (!captureRunning) startCamera() }

    private fun startCamera() {
        if (!allPermissionsGranted()) return
        if (streamingServerHelper?.getH264Clients().isNullOrEmpty()) return   // only run for live viewers
        stopCamera()
        try {
            if (chooseApi() == "camera1") {
                // Camera1 → GL pipe → surface encoder (true 1920×1080 on legacy HALs).
                val want = desiredSize()
                val cap = Camera1Capture(camera1IndexForFacing(frontFacing), want.width, want.height)
                val pipe = newPipe(Size(cap.chosenW, cap.chosenH))
                cap.start(pipe.surfaceTexture)
                backend = cap
                Log.i(TAG, "stream ${camId()} api=camera1 ${cap.chosenW}x${cap.chosenH}")
            } else {
                // CameraX → ImageAnalysis YUV → byte-buffer encoder (reliable resolution) + ImageCapture stills.
                backend = CameraXCapture(this, this, frontFacing, desiredSize()) { img -> feedH264(img) }.also { it.start() }
                Log.i(TAG, "stream ${camId()} api=camerax")
            }
            captureRunning = true
            backend?.let { applyStored(it) }
        } catch (e: Exception) { Log.e(TAG, "startCamera: ${e.message}"); stopCamera() }
    }

    private fun stopCamera() {
        backend?.stop(); backend = null
        glPipe?.stop(); glPipe = null
        h264Encoder?.stop(); h264Encoder = null
        captureRunning = false
    }

    /** Surface-mode encoder + GL pipe, shared by both backends (CameraX Preview / Camera1 preview). */
    private fun newPipe(sz: Size): CameraGlPipe {
        val enc = H264Encoder(sz.width, sz.height, 30, H264Encoder.bitrateFor(sz.width, sz.height), true) { d, k -> broadcastH264(d, k) }
        h264Encoder = enc
        streamingServerHelper?.resetH264Wait()
        return CameraGlPipe(enc.inputSurface!!, sz.width, sz.height).also { it.start(); glPipe = it }
    }

    /** CameraX path: feed one analysis frame (YUV) to a byte-buffer encoder. Closes the frame. */
    private fun feedH264(image: ImageProxy) {
        try {
            var enc = h264Encoder
            if (enc == null || enc.width != image.width || enc.height != image.height) {
                enc?.stop()
                enc = H264Encoder(image.width, image.height, 30, H264Encoder.bitrateFor(image.width, image.height), false) { d, k -> broadcastH264(d, k) }
                h264Encoder = enc; enc.requestKeyFrame(); streamingServerHelper?.resetH264Wait()
            }
            enc.feed(image, image.imageInfo.timestamp / 1000)
        } catch (e: Exception) { Log.e(TAG, "feed: ${e.message}") }
        finally { image.close() }
    }

    private fun broadcastH264(data: ByteArray, isKey: Boolean) {
        val list = streamingServerHelper?.getH264Clients() ?: return
        for (c in list) {
            try {
                if (c.waitingKey) { if (!isKey) continue; c.waitingKey = false }
                c.outputStream.write(data); c.outputStream.flush()
            } catch (e: Exception) { streamingServerHelper?.removeH264Client(c) }
        }
    }

    private fun applyStored(b: CaptureBackend) {
        val p = PreferenceManager.getDefaultSharedPreferences(this); val id = camId()
        b.setTorch(p.getString("torch_$id", "off") == "on")
        p.getString("exposure_$id", null)?.toIntOrNull()?.let { b.setExposure(it) }
        p.getString("zoom_$id", null)?.toFloatOrNull()?.let { b.setZoom(it) }
    }

    // ---------------- snapshot (full resolution) ----------------

    /**
     * Full-resolution JPEG into RAM:
     *  - requested camera == the live one → video snapshot (takePicture during preview, no interruption).
     *  - other camera, or idle → pause the live stream, one-shot capture, resume (single HAL).
     */
    fun snapshot(cameraId: String): ByteArray? {
        synchronized(snapLock) {
            val want = when {
                cameraId.equals("front", true) -> "front"
                cameraId.equals("back", true) -> "back"
                else -> camId()
            }
            // Live camera → full-res still concurrent with the stream (no interruption).
            if (want == camId()) backend?.let { return captureFrom(it, want) ?: snapCache[want] }

            // Other camera (single HAL): switch CameraX to it, capture, switch back to the original.
            val orig = frontFacing
            val hadViewers = streamingServerHelper?.getH264Clients()?.isNotEmpty() == true
            frontFacing = (want == "front")
            switchAndWait()
            val jpeg = backend?.let { captureFrom(it, want) }
            frontFacing = orig
            launchMain { if (hadViewers) startCamera() else stopCamera() }
            return jpeg ?: snapCache[want]
        }
    }

    /** Take one still from [b] (blocks the caller on a latch; capture runs on the main thread). */
    private fun captureFrom(b: CaptureBackend, key: String): ByteArray? {
        val latch = CountDownLatch(1); val out = arrayOfNulls<ByteArray>(1)
        launchMain { b.captureStill { out[0] = it; latch.countDown() } }
        try { latch.await(8, TimeUnit.SECONDS) } catch (_: Exception) {}
        out[0]?.let { snapCache[key] = it }
        return out[0]
    }

    /** Restart the camera on the new facing and wait until CameraX is bound + the 3A has a moment. */
    private fun switchAndWait() {
        val l = CountDownLatch(1); launchMain { startCamera(); l.countDown() }
        try { l.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}
        var n = 0; while (n < 40 && backend?.ready != true) { try { Thread.sleep(100) } catch (_: Exception) {}; n++ }
        try { Thread.sleep(500) } catch (_: Exception) {}   // let auto-exposure settle on the new camera
    }

    // ---------------- controls ----------------

    /**
     * GET /?<key>=<value> (proxied as /api/video/control):
     *   torch=on|off|toggle   focus=1   exposure=<ev>   zoom=<ratio>
     *   camera=front|back|toggle   resolution=WxH   api=auto|camerax|camera1
     */
    private fun handleRemoteControl(key: String, value: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId()
        when (key) {
            "torch" -> {
                val next = when (value.lowercase()) {
                    "on" -> "on"; "off" -> "off"
                    "toggle" -> if (prefs.getString("torch_$id", "off") == "on") "off" else "on"
                    else -> return
                }
                prefs.edit().putString("torch_$id", next).apply()
                launchMain { backend?.setTorch(next == "on") }
            }
            "exposure" -> {
                val ev = value.toIntOrNull() ?: return
                prefs.edit().putString("exposure_$id", ev.toString()).apply()
                launchMain { backend?.setExposure(ev) }
            }
            "zoom" -> {
                val z = value.toFloatOrNull() ?: return
                prefs.edit().putString("zoom_$id", z.toString()).apply()
                launchMain { backend?.setZoom(z) }
            }
            "focus" -> launchMain { backend?.triggerAutoFocus() }
            "camera" -> {
                frontFacing = when {
                    value.equals("front", true) -> true
                    value.equals("back", true) -> false
                    value.equals("toggle", true) -> !frontFacing
                    else -> return
                }
                prefs.edit().putString(PREF_LAST_CAMERA_FACING, if (frontFacing) "front" else "back").apply()
                launchMain { if (captureRunning) startCamera() }
            }
            "resolution" -> {
                if (value == "auto" || value == "max" || Regex("\\d+x\\d+").matches(value)) {
                    prefs.edit().putString("stream_res", if (value == "max") "auto" else value).apply()
                    launchMain { if (captureRunning) startCamera() }
                }
            }
            "api" -> {
                if (value in listOf("auto", "camerax", "camera1")) {
                    prefs.edit().putString("capture_api", value).apply()
                    launchMain { if (captureRunning) startCamera() }
                }
            }
        }
    }

    private fun generateRandomPassword(): String {
        val r = SecureRandom()
        val all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { all[r.nextInt(all.length)] }.joinToString("")
    }
}

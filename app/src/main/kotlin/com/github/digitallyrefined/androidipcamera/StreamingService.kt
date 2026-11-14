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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import java.io.ByteArrayOutputStream
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
    /** Optional RTSP server (Settings > RTSP Stream) sharing the H.264 encoder with the HTTP server. */
    private var rtspServer: RtspServer? = null
    /** Optional ONVIF service (Settings > ONVIF) advertising the RTSP stream and motion events. */
    @Volatile private var onvifServer: OnvifServer? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    /** Motion detection over the capture frames already delivered to the streaming pipeline. */
    private var motionMonitor: MotionMonitor? = null

    // Streaming encoders (initialized when server is created)
    private var h264StreamingEncoder: H264StreamingEncoder? = null
    private var mjpegStreamingEncoder: MjpegStreamingEncoder? = null
    /** Outbound relay connection to the public server, when this camera is associated. */
    @Volatile private var relayClient: RelayClient? = null
    private val encoders: List<StreamingEncoder>
        get() = listOfNotNull(h264StreamingEncoder, mjpegStreamingEncoder)

    /** Motion detection is on: the camera must keep delivering frames even with no viewer. */
    private fun motionEnabled(): Boolean = motionMonitor?.enabled == true

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
    /** True while the torch is lit through [setDeviceTorch] (CameraManager.setTorchMode) rather
     *  than the streaming camera's own session. Tracked so it can be released on shutdown — unlike
     *  session-bound torch, a setTorchMode torch survives process death. */
    @Volatile private var deviceTorchOn = false
    @Volatile private var localRecorder: LocalRecorder? = null
    /** Serializes manual and motion-triggered recording start/stop transitions. */
    private val recordingLock = Any()
    /** Pending auto-stop for a motion-triggered recording (cancelled when motion resumes). */
    @Volatile private var motionStopJob: kotlinx.coroutines.Job? = null
    /** Smaller encoder feeding the RTSP sub stream (/live/sub) while it has viewers. */
    @Volatile private var subEncoder: H264HardwareEncoder? = null
    /** Sub stream settings the running sub encoder was built with. */
    private var subEncoderSettings: List<Int>? = null

    /**
     * Keeps the CPU awake while a viewer is connected. Without it the device enters deep sleep
     * shortly after the screen turns off, the camera stops delivering frames, and WebRTC viewers
     * freeze until the phone is unlocked again.
     */
    private var streamingWakeLock: PowerManager.WakeLock? = null

    @Volatile private var frontFacing = false             // false = back camera (read across threads)
    @Volatile private var selectedCameraId: String? = null

    /**
     * True once the foreground service was started with the camera/microphone types. Android 14+
     * refuses those types when the service is started from the background, so the regular
     * foreground notification may be running without camera/microphone access until an activity
     * brings the app back to the foreground.
     */
    @Volatile private var whileInUseForegroundAllowed = false
    /** True once any foreground notification was successfully posted (with or without types). */
    @Volatile private var foregroundNotificationStarted = false

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
    @Volatile private var pendingStartJob: kotlinx.coroutines.Job? = null
    /** The viewer check waiting to run, so a burst of viewer changes and a settings restart are decided once. */
    private var pendingViewerCheck: kotlinx.coroutines.Job? = null
    private var torchVerifyJob: kotlinx.coroutines.Job? = null
    @Volatile private var cameraCloseStartTime: Long = 0L
    /** What the running session was built for, so a viewer change restarts it only when needed. Null while stopped. */
    private var runningSession: CameraSession? = null
    /** Bumped by every settings restart request, so a start that read older settings is never reused. */
    private var settingsGeneration = 0
    @Volatile private var runningStartedAtMs = 0L

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
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "streaming_service_channel"
        private const val PREF_CAMERA_ID = "camera_id"
        private const val PREF_ONVIF_NAME = "onvif_name"
        private const val PREF_ONVIF_PORT = "onvif_port"
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
        /** A viewer change waits this long, so a burst of connections or a reconnect is decided once. */
        private const val VIEWER_CHECK_DELAY_MS = 300L
        /** A running session with no camera frame or encoder output for this long counts as stalled, and a viewer change restarts it. */
        private const val CAMERA_STALL_MS = 5_000L
        /** Longest a start waits for the previous Camera1 release (about 21 s seen) before opening anyway. */
        private const val CAMERA1_RELEASE_WAIT_MS = 30_000L
        /** A CameraX start without frames this soon after a slow Camera1 release is not counted as a miss. */
        private const val CAMERA1_SETTLE_MS = 30_000L
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
        const val ACTION_RESTART_SERVER = "com.github.digitallyrefined.androidipcamera.RESTART_SERVER"
        const val ACTION_START_SERVER = "com.github.digitallyrefined.androidipcamera.START_SERVER"
        const val ACTION_DISCONNECT_RELAY = "com.github.digitallyrefined.androidipcamera.DISCONNECT_RELAY"
    }

    inner class LocalBinder : Binder() { fun getService(): StreamingService = this@StreamingService }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        // Binding means an activity is coming to the foreground; retry the restricted FGS types
        // in case the service was restarted in the background.
        ensureForegroundTypes()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundNotificationStarted) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP_SERVICE -> { handleStopService(); return START_NOT_STICKY }
            ACTION_RESTART_NOTIFICATION ->
                if (!startForegroundService()) { stopSelf(); return START_NOT_STICKY }
            ACTION_RESTART_SERVER -> if (ensureForegroundTypes()) restartServer()
            ACTION_DISCONNECT_RELAY -> { disconnectRelay(); return START_STICKY }
            // Cold start with no activity bound (e.g. from BootReceiver): the activity normally
            // calls startStreamingServer() after binding, so start it here instead.
            ACTION_START_SERVER -> if (whileInUseForegroundAllowed) startStreamingServer()
        }
        // A null intent means the system re-created this START_STICKY service after killing the
        // process. Nothing starts the server in that path — no activity binds, and onCreate() only
        // posts the notification — so the service comes back looking healthy (foreground
        // notification, camera available) with nothing listening on the port. startStreamingServer()
        // early-returns when the socket is already open, so this is safe to reach on any path.
        // Without camera/microphone access the server would not be able to serve video, so wait
        // until an activity brings the app to the foreground.
        if (intent == null && whileInUseForegroundAllowed) startStreamingServer()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleStopService() {
        sendBroadcast(Intent("com.github.digitallyrefined.androidipcamera.CLOSE_APP").setPackage(packageName))
        stopForeground(true); stopSelf()
    }

    private fun restartServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopServer()
            rtspServer?.stop()
            stopOnvif()
            kotlinx.coroutines.delay(500)
            streamingServerHelper?.startStreamingServer()
            startRtspServerIfEnabled()
            startOnvifIfEnabled()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!startForegroundService()) {
            Log.e(TAG, "Unable to start the foreground service; stopping")
            stopSelf()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Earlier versions kept "camera2_unusable" forever after one CameraX start without frames, which
        // pinned capable phones to Camera1. Drop it so the next frame-needing start tests CameraX again.
        if (prefs.contains(CameraXFallback.LEGACY_PREF)) prefs.edit().remove(CameraXFallback.LEGACY_PREF).apply()
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
        // A setTorchMode torch outlives the process — release it explicitly or the LED burns
        // forever after the service is stopped.
        if (deviceTorchOn) setDeviceTorch(false)
        // Plain thread: the lifecycle scope is already cancelled once the service is destroyed.
        rtspServer?.let { server -> Thread { server.stop() }.start() }
        motionMonitor?.detector?.reset()
        stopOnvif()
        relayClient?.stop()
        relayClient = null
        releaseStreamingWakeLock()
        lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.stopStreamingServer() }
    }

    /**
     * Starts (or updates) the foreground notification with camera/microphone access.
     *
     * Returns true when any foreground notification was posted. If Android rejects the
     * camera/microphone types because the service was started in the background, a notification
     * without those types is posted instead so the process does not crash; the types are retried
     * by [ensureForegroundTypes] once an activity brings the app to the foreground.
     */
    private fun startForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(cameraActive = true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                markForegroundStarted(whileInUseAllowed = true)
                return true
            } catch (e: SecurityException) {
                // Expected after a background start (boot, START_STICKY restart): camera and
                // microphone are while-in-use permissions and cannot be claimed from the background.
                Log.w(TAG, "Camera/microphone foreground service not allowed in the background", e)
                whileInUseForegroundAllowed = false
                return try {
                    // Passing NONE explicitly is required: the two-argument startForeground() uses
                    // the manifest types and would be rejected again.
                    startForeground(
                        NOTIFICATION_ID,
                        buildForegroundNotification(cameraActive = false),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    )
                    markForegroundStarted(whileInUseAllowed = false)
                    true
                } catch (fallback: Exception) {
                    Log.e(TAG, "Unable to start the foreground service", fallback)
                    foregroundNotificationStarted = false
                    false
                }
            }
        }
        startForeground(NOTIFICATION_ID, buildForegroundNotification(cameraActive = true))
        markForegroundStarted(whileInUseAllowed = true)
        return true
    }

    private fun markForegroundStarted(whileInUseAllowed: Boolean) {
        whileInUseForegroundAllowed = whileInUseAllowed
        foregroundNotificationStarted = true
        // The service notification supersedes the "tap to start" prompt posted after boot.
        try { BootReceiver.cancelStartNotification(this) } catch (_: Exception) {}
    }

    /**
     * Retries the camera/microphone foreground service types. Safe to call repeatedly; only
     * startForeground() with the real types is attempted while the app is in the foreground.
     */
    fun ensureForegroundTypes(): Boolean {
        if (whileInUseForegroundAllowed) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            whileInUseForegroundAllowed = true
            return true
        }
        return try {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(cameraActive = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            markForegroundStarted(whileInUseAllowed = true)
            Log.i(TAG, "Foreground service promoted to camera/microphone types")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Still not allowed to use camera/microphone in a foreground service", e)
            false
        }
    }

    private fun buildForegroundNotification(cameraActive: Boolean): Notification {
        val contentPI = PendingIntent.getActivity(this, 0,
            Intent(this, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val restartPI = PendingIntent.getService(this, 2,
            Intent(this, StreamingService::class.java).setAction(ACTION_RESTART_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (cameraActive) "Android IP Camera Streaming" else "Android IP Camera")
            .setContentText(if (cameraActive) "Camera server is running in background" else "Tap to start streaming")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(contentPI)
            .addAction(R.drawable.ic_notification, "Exit App", stopPI)
            .setDeleteIntent(restartPI)
            .setOngoing(true)
            .build()
    }

    // MainActivity attaches PreviewView here; camera restarts when a surface is set while streaming.
    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        // Recording owns the camera session: stopping or restarting the camera now would tear
        // down the encoder and finalize the file, so leave the pipeline alone until it stops.
        if (localRecorder?.isRecording == true) return
        val shouldRun = encoders.any { it.hasClients() } || currentSurfaceProvider != null || motionEnabled()
        if (shouldRun) {
            debouncedStartCamera()
        } else {
            launchMain {
                stopCameraWhenIdle()
                bridgeTorchAcrossIdle()
            }
        }
    }
    fun isCameraRunning() = captureRunning
    fun hasActiveClients() = encoders.any { it.hasClients() } || localRecorder?.isRecording == true || motionEnabled()
    fun switchCamera() {
        selectCamera(!frontFacing, null)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_CAMERA_ID, camId())
            .apply()
        launchMain {
            awaitCameraSettled()
            if (captureRunning) debouncedStartCamera(force = true)
        }
    }

    fun getLocalIpAddress(): String {
        // A server limited to one interface should advertise that address, not whichever IPv4
        // address happens to be enumerated first.
        ListenInterface.specificIpv4(this)?.let { return it }
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

    // ---------------- relay association ----------------

    val isRelayAssociated: Boolean get() = RelayAssociation.load(this) != null
    val isRelayConnected: Boolean get() = relayClient?.isConnected == true

    /**
     * Exchange a one-time pairing code for a device token, then bring the outbound
     * connection up. Called from the activity when an androidipcamera://associate
     * intent is opened.
     */
    fun associateRelay(serverUrl: String, code: String, onResult: (success: Boolean, message: String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val credentials = RelayAssociation.associate(this@StreamingService, serverUrl, code)
                val relay = relayClient
                if (relay != null) {
                    relay.restartAfterAssociation()
                } else {
                    // The HTTP server has not been initialized yet; starting it also creates
                    // the relay client, which reads the credentials we just stored.
                    kotlinx.coroutines.withContext(Dispatchers.Main) { startStreamingServer() }
                }
                Log.i(TAG, "Associated relay: ${credentials.deviceId} @ ${credentials.serverUrl}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onResult(true, "Associated with ${credentials.serverUrl}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "relay associate failed: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Association failed")
                }
            }
        }
    }

    /**
     * Forget the relay association, stop the outbound connection, and remove
     * this camera from the relay's database. Everything that can block (secure
     * storage, socket shutdown, HTTP delete) runs on the IO dispatcher so the
     * Settings UI stays responsive. The relay section is disabled through the
     * relay_deleting preference until the remote delete finishes.
     */
    fun disconnectRelay() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putBoolean("relay_deleting", true)
            .putLong("relay_deleting_since", System.currentTimeMillis())
            .apply()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val credentials = RelayAssociation.load(this@StreamingService)
                val relay = relayClient
                if (relay != null) {
                    relay.disconnect()
                } else {
                    RelayAssociation.clear(this@StreamingService)
                    prefs.edit()
                        .putBoolean("relay_connected", false)
                        .putString("relay_status", "Not associated")
                        .putLong("relay_status_updated_at", System.currentTimeMillis())
                        .apply()
                }
                if (credentials != null) {
                    val removed = RelayAssociation.deleteRemote(credentials)
                    if (removed) {
                        Log.i(TAG, "Removed relay device ${credentials.deviceId}")
                    } else {
                        Log.w(TAG, "Could not remove relay device ${credentials.deviceId}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Relay disconnect failed: ${error.message}")
            } finally {
                // Whether the remote delete succeeded or exhausted its retries,
                // the user asked to disconnect: always forget the association.
                runCatching { RelayAssociation.clear(this@StreamingService) }
                    .onFailure { Log.w(TAG, "Could not clear relay association: ${it.message}") }
                prefs.edit()
                    .putBoolean("relay_connected", false)
                    .putString("relay_status", "Not associated")
                    .putLong("relay_status_updated_at", System.currentTimeMillis())
                    .putBoolean("relay_deleting", false)
                    .remove("relay_deleting_since")
                    .apply()
            }
        }
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
                onClientConnected = { handleClientConnected() },
                onClientDisconnected = { handleClientDisconnected() },
                onControlCommand = { key, value, ts -> handleRemoteControl(key, value, ts) },
                onSnapshot = { id -> snapshot(id) },
                onRecordStart = { startRecording() },
                onRecordStop = { stopRecording() },
                onRecordStatus = { getRecordingStatus() },
                // /control/stop must also drop RTSP viewers, which the HTTP server does not own.
                onStreamingEnabledChanged = { enabled -> if (!enabled) rtspServer?.disconnectAll() }
            )
            // Initialize encoders with the streaming server helper
            h264StreamingEncoder = H264StreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "H264: $it"); onLog?.invoke(it) }
            mjpegStreamingEncoder = MjpegStreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "MJPEG: $it"); onLog?.invoke(it) }
            motionMonitor = MotionMonitor(this) { active ->
                Log.i(TAG, "Motion ${if (active) "started" else "ended"}")
                onvifServer?.setMotion(active)
                // Report the start to the relay too, when the camera is associated.
                if (active) relayClient?.sendMotion()
                handleMotionRecording(active)
            }
            rtspServer = RtspServer(
                AndroidRtspHost(
                    this,
                    onLog = { Log.i(TAG, "RTSP: $it"); onLog?.invoke(it) },
                    clientConnected = { handleClientConnected() },
                    clientDisconnected = { handleClientDisconnected() },
                    keyFrameRequest = { stream ->
                        val sub = subEncoder
                        if (stream == RtspStream.SUB && sub != null) sub.requestKeyFrame()
                        else h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
                    },
                    streamingEnabled = { streamingServerHelper?.isStreamingEnabled() != false }
                ),
                DeviceMemoryHelper.rtspSendQueueCapacity(this),
                // Same certificate as the web server; null when TLS is disabled.
                tls = { TlsHelper.serverConfig(this) }
            )
            h264StreamingEncoder?.rtspServer = rtspServer

            val relay = RelayClient(
                this,
                onLog = { Log.i(TAG, "Relay: $it"); onLog?.invoke("Relay: $it") },
                onStatusChanged = { connected, status ->
                    Log.i(TAG, "Relay status: ${if (connected) "connected" else "disconnected"} - $status")
                },
                // A browser WebRTC viewer starts or stops the camera through the relay. The
                // encoder taps the same frames, so the normal connected/disconnected paths apply.
                onMediaActiveChanged = { active ->
                    if (active) handleClientConnected() else handleClientDisconnected()
                },
                onKeyFrameRequest = {
                    h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
                }
            )
            relayClient = relay
            h264StreamingEncoder?.relayClient = relay
            relay.start()
        }
        streamingServerHelper?.startStreamingServer()
        startRtspServerIfEnabled()
        startOnvifIfEnabled()
        // Motion detection persisted as on keeps the camera running with no viewer.
        if (motionEnabled()) launchMain { startCameraIfNeeded() }
    }

    /** Starts (or stops) the RTSP server to match the RTSP/ONVIF setting. ONVIF is enabled by the
     *  same preference and advertises this stream. */
    private fun startRtspServerIfEnabled() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(RtspServer.PREF_ENABLED, false)
        if (enabled) rtspServer?.start() else rtspServer?.stop()
    }

    // ---------------- ONVIF ----------------

    private fun stopOnvif() {
        try { onvifServer?.stop() } catch (e: Exception) { Log.w(TAG, "onvif stop: ${e.message}") }
        onvifServer = null
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    /** Starts the ONVIF service when RTSP/ONVIF is enabled. It never keeps the camera open by
     *  itself: video viewers (RTSP/HTTP) and motion detection do that on demand. */
    private fun startOnvifIfEnabled() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(RtspServer.PREF_ENABLED, false)) {
            stopOnvif()
            return
        }
        if (onvifServer != null) return
        val rtspPort = prefPort(RtspServer.PREF_PORT, RtspServer.DEFAULT_PORT)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val onvif = OnvifServer(
            port = prefPort(PREF_ONVIF_PORT, 8000),
            host = { getLocalIpAddress() },
            info = {
                OnvifDeviceInfo(
                    manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                    model = Build.MODEL,
                    firmware = "AndroidIPCamera $version",
                    serial = deviceId(),
                    hardwareId = Build.DEVICE,
                    mac = pseudoMac(),
                    name = prefs.getString(PREF_ONVIF_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
                )
            },
            stream = {
                val size = desiredSize()
                val fps = (prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30).coerceIn(1, 60)
                val scheme = if (TlsHelper.isEnabled(this)) "rtsps" else "rtsp"
                OnvifStreamInfo(
                    rtspUrl = "$scheme://${getLocalIpAddress()}:$rtspPort${RtspServer.PATH}",
                    width = size.width, height = size.height, fps = fps,
                    bitrateKbps = H264HardwareEncoder.bitrateFor(size.width, size.height) / 1000,
                )
            },
            credentials = { streamCredentials() },
            // Snapshots use the regular still-capture path, so they work whether or not a
            // stream is running (a snapshot opens the camera on demand and restores the stream).
            snapshot = { snapshot(camId()) },
            onLog = { Log.i(TAG, "ONVIF: $it"); onLog?.invoke("ONVIF: $it") },
            // Same certificate as the web server; null when TLS is disabled.
            tls = { TlsHelper.serverConfig(this) },
            bindAddress = ListenInterface.resolve(this)
        )
        try {
            multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createMulticastLock("onvif-discovery").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "multicast lock: ${e.message}") }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                onvif.start()
                // Settings may have changed while the service was binding.
                if (!PreferenceManager.getDefaultSharedPreferences(this@StreamingService)
                        .getBoolean(RtspServer.PREF_ENABLED, false)) {
                    onvif.stop()
                    return@launch
                }
                onvifServer = onvif
                motionMonitor?.detector?.let { if (it.active) onvif.setMotion(true) }
            } catch (e: Exception) {
                Log.e(TAG, "ONVIF start failed", e)
                onLog?.invoke("ONVIF failed to start: ${e.message}")
                try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                multicastLock = null
            }
        }
    }

    private fun streamCredentials(): Pair<String, String>? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("enable_auth", true)) return null
        val ss = SecureStorage(this)
        val u = InputValidator.validateAndSanitizeUsername(ss.getSecureString(SecureStorage.KEY_USERNAME, "") ?: "")
        val p = InputValidator.validateAndSanitizePassword(ss.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: "")
        return if (u.isNullOrBlank() || p.isNullOrBlank()) null else u to p
    }

    private fun prefPort(key: String, def: Int): Int =
        PreferenceManager.getDefaultSharedPreferences(this).getString(key, null)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: def

    /** Stable pseudo-MAC/serial from ANDROID_ID (Android hides the real Wi-Fi MAC from apps). */
    private fun deviceId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0000000000000000"

    private fun pseudoMac(): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(deviceId().toByteArray())
        h[0] = ((h[0].toInt() and 0xFC) or 0x02).toByte()   // locally administered, unicast
        return (0 until 6).joinToString(":") { String.format(Locale.US, "%02x", h[it]) }
    }

    /** Keeps the CPU and camera pipeline running while a viewer is connected. */
    private fun acquireStreamingWakeLock() {
        val lock = streamingWakeLock ?: run {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:streaming"
            ).apply { setReferenceCounted(false) }.also { streamingWakeLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseStreamingWakeLock() {
        streamingWakeLock?.let { if (it.isHeld) it.release() }
    }

    /** A viewer (HTTP or RTSP) connected: make sure the camera runs in a configuration that serves it. */
    private fun handleClientConnected() {
        acquireStreamingWakeLock()
        launchMain {
            onClientConnected?.invoke()
            // While recording, the camera is already running and feeding the encoder; a
            // restart would finalize the recording, and new viewers pick up the existing
            // stream, so no reconfiguration is needed (the sub stream encoder needs no restart).
            if (localRecorder?.isRecording == true) {
                updateSubStream()
                return@launchMain
            }
            // Restart only when the running session cannot serve the new viewer. startCamera() decides under
            // the camera lock, so a viewer arriving during a start is checked once that start ends.
            if (captureRunning) scheduleViewerCheck() else startCamera(viewersChanged = true)
        }
    }

    private fun handleClientDisconnected() {
        launchMain {
            onClientDisconnected?.invoke()
            // Recording owns the camera: stopping or restarting it now would finalize
            // the file, and remaining viewers keep their existing stream as-is.
            if (localRecorder?.isRecording == true) {
                updateSubStream()
                return@launchMain
            }
            if (!hasActiveClients()) {
                // No viewers left: the device may sleep again.
                releaseStreamingWakeLock()
                // Reload/idle: keep the LED lit across the session gap before the
                // shed-restart tears the streaming camera down.
                bridgeTorchAcrossIdle()
                if (currentSurfaceProvider == null) {
                    // Last client gone and no on-screen preview: shed the H.264
                    // surface encoder / GL pipe and keep the camera warm in a plain,
                    // snapshot-friendly configuration. Force ignores the hasClients()
                    // bypass so the reconfiguration actually happens.
                    pendingStartJob?.cancel()
                    pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(300)
                        // Past its delay a start is not replaced any more: it may be opening or closing a Camera1 device
                        // on another thread. A newer request runs after it on cameraMutex instead.
                        if (pendingStartJob === coroutineContext[kotlinx.coroutines.Job]) pendingStartJob = null
                        // A viewer leaving during a Camera1 open sheds the session that open ends with.
                        awaitCameraSettled()
                        if (!captureRunning) return@launch   // camera stopped meanwhile
                        if (encoders.any { it.hasClients() } || currentSurfaceProvider != null || motionEnabled()) return@launch
                        startCamera(force = true)
                    }
                } else {
                    debouncedStartCamera()
                }
            } else if (captureRunning) {
                scheduleViewerCheck()
            }
        }
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

    /** Reads the camera list from cameraserver, so it runs on the Camera1 thread. [selectedId] is read on main. */
    private fun camera1IndexForSelectedOrFacing(selectedId: String?, front: Boolean): Int {
        val selected = selectedId?.toIntOrNull()
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

    /** Encoder frame for a surface path: [want] turned only by the manual rotate= control. */
    private fun surfaceEncoderSize(want: Size): Size {
        val (w, h) = CameraGlPipe.frameSize(want.width, want.height, quantizedRotation(readRotatePref()))
        return Size(w, h)
    }

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

    /** Desired stream size from the resolution pref; "auto" → preset target, otherwise the picked WxH. */
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
            return fitToEncoderCaps(target.width, target.height, caps.maxW, caps.maxH)
        }
        val m = Regex("(\\d+)x(\\d+)").find(streamRes)
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 1080
        // Keep the selected aspect ratio when the encoder cannot take the full size; capping
        // width and height independently would turn e.g. 1080x1920 into a square frame.
        return fitToEncoderCaps(w, h, caps.maxW, caps.maxH)
    }

    /** Scale [w]×[h] down to fit the encoder caps without changing its aspect ratio. */
    private fun fitToEncoderCaps(w: Int, h: Int, maxW: Int, maxH: Int): Size {
        if (w <= maxW && h <= maxH) return Size(w, h)
        val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
        fun even(v: Double) = ((v.toInt().coerceAtLeast(2)) / 2) * 2
        return Size(even(w * scale), even(h * scale))
    }

    private suspend fun startCameraIfNeeded() {
        awaitCameraSettled()
        if (!captureRunning) startCamera()
    }

    /**
     * Waits for a camera start or stop in flight. Camera1 opens off the main thread, so captureRunning
     * reads false until that start ends. Callers that act on captureRunning wait here, as they used to
     * wait behind the blocked main thread.
     */
    private suspend fun awaitCameraSettled() { cameraMutex.withLock {} }

    /**
     * Stops the camera if nothing needs it any more, checked again after any start in flight, so a viewer
     * that joined meanwhile keeps its stream. Returns once a Camera1 device is released too, so a device
     * torch lit next is not refused with CAMERA_IN_USE. True when the camera was stopped.
     */
    private suspend fun stopCameraWhenIdle(): Boolean {
        val stopped = cameraMutex.withLock {
            if (hasActiveClients() || currentSurfaceProvider != null) false else { stopCamera(); true }
        }
        if (stopped) Camera1Capture.awaitReleased(CAMERA1_RELEASE_WAIT_MS)
        return stopped
    }

    /** What the viewers, recording, motion detection, a snapshot and the on-phone preview ask of the camera now. */
    private fun cameraDemand() = CameraDemand(
        h264Viewers = h264StreamingEncoder?.hasClients() == true,
        mjpegViewers = mjpegStreamingEncoder?.hasClients() == true,
        recording = recordingNeeded || localRecorder?.isRecording == true,
        motion = motionEnabled(),
        snapshot = snapshotInProgress,
        preview = currentSurfaceProvider != null,
    )

    private fun streamFps(): Int =
        (PreferenceManager.getDefaultSharedPreferences(this).getString("stream_fps", "30")?.toIntOrNull() ?: 30).coerceIn(1, 60)

    /**
     * The running session drew a camera frame lately, or only just started. One that stopped (camera
     * taken by another app, cameraserver restarted) is restarted by the next viewer change, as every
     * viewer change used to do. So is one whose H.264 encoder stopped while the camera kept running.
     */
    private fun cameraDelivering(): Boolean {
        val lastFrameAt = when (val b = backend) {
            is CameraXCapture -> b.lastFrameAtMs
            is Camera1Capture -> glPipe?.lastFrameAtMs ?: 0L
            else -> 0L
        }
        // A GL pipe draws every camera frame into a surface encoder, so one with no output lately has
        // stopped (codec reclaimed or failed, pipe gone). A byte-buffer encoder is fed only while H.264
        // is wanted, so for it only a failed drain counts.
        val enc = h264StreamingEncoder?.h264HardwareEncoder
        if (enc?.drainFailed == true) return false
        val encodedAt = enc?.takeIf { it.useSurface }?.lastOutputAtMs
        return CameraSession.delivering(SystemClock.elapsedRealtime(), runningStartedAtMs, CAMERA_STALL_MS, lastFrameAt, encodedAt)
    }

    /**
     * A viewer joined or left while the camera runs. After a short settle, which folds a burst of
     * connections into one decision, [startCamera] restarts it only if the running session cannot serve
     * the viewers then connected. A settings restart scheduled meanwhile replaces the check, since it
     * rebuilds the session for the viewers connected when it runs.
     */
    private fun scheduleViewerCheck() {
        pendingViewerCheck?.cancel()
        pendingViewerCheck = lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(VIEWER_CHECK_DELAY_MS)
            val self = coroutineContext[kotlinx.coroutines.Job]
            startCamera(viewersChanged = true) { if (pendingViewerCheck === self) pendingViewerCheck = null }
        }
    }

    /**
     * Debounced camera restart. Coalesces rapid [startCamera] calls (e.g. when a settings
     * change fires both a resolution and a camera-restart broadcast) into a single restart
     * after [delayMs]. Immediate restarts (force=true) bypass the debounce.
     */
    private fun debouncedStartCamera(force: Boolean = false, delayMs: Long = 300) {
        // Remote controls call in from the server's IO threads. The pending jobs are only read and
        // replaced on main, so a start cannot be cancelled after it has claimed the camera lock.
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            launchMain { debouncedStartCamera(force, delayMs) }
            return
        }
        // The running session no longer matches what was asked for. A viewer change before this start
        // runs, or after it is bypassed or replaced by the shed, rebuilds it instead of keeping it. A start
        // already running read the old settings, so it does not publish its session either.
        runningSession = null
        settingsGeneration++
        pendingViewerCheck?.cancel()
        pendingViewerCheck = null
        if (force) {
            pendingStartJob?.cancel()
            pendingStartJob = null
            launchMain { startCamera(force = true) }
            return
        }
        pendingStartJob?.cancel()
        pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(delayMs)
            val self = coroutineContext[kotlinx.coroutines.Job]
            startCamera { if (pendingStartJob === self) pendingStartJob = null }
        }
    }

    /**
     * Restarts the camera for the current settings and consumers. [onLocked] runs first once the
     * camera lock is held: a delayed start clears its pending job there. Until then a newer request
     * replaces it, so settings changed during a slow start cost one restart. After that it may open or
     * close a Camera1 device on another thread, so a newer request runs after it instead.
     */
    private suspend fun startCamera(
        force: Boolean = false,
        viewersChanged: Boolean = false,
        onLocked: () -> Unit = {},
    ) = cameraMutex.withLock {
        onLocked()
        Log.i(TAG, "startCamera() called. force=$force, viewersChanged=$viewersChanged, selectedCameraId=$selectedCameraId, frontFacing=$frontFacing, currentSurfaceProvider=$currentSurfaceProvider, captureRunning=$captureRunning")
        if (!allPermissionsGranted()) {
            Log.w(TAG, "startCamera bypassed: permissions not granted")
            return@withLock
        }
        val hasClients = encoders.any { it.hasClients() }
        if (!force && !hasClients && currentSurfaceProvider == null && !motionEnabled()) {
            Log.i(TAG, "startCamera bypassed: no force, no clients, and preview surface is null")
            return@withLock
        }
        // A viewer joining or leaving restarts the camera only when the running session cannot serve the
        // viewers now connected, or has stopped delivering frames. Restarting on every connection cost a
        // close and an open each time, over 40 s on some Camera1 HALs, long enough to crash cameraserver.
        // A session the torch holds open was built for nobody, so a viewer restarts it, ending the hold.
        if (viewersChanged && captureRunning && !cameraHeldForTorch && cameraDelivering() &&
            runningSession?.serves(cameraDemand(), streamFps(), camId()) == true) {
            Log.i(TAG, "startCamera skipped: the running camera already serves the connected viewers")
            // A /live/sub viewer joining or leaving starts or stops the sub stream encoder.
            updateSubStream()
            // New HTTP H.264 viewers wait for a keyframe, which a restart used to send at once.
            h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
            return@withLock
        }
        // An explicit restart must always be able to tear down the current session — even one the
        // torch is holding open. Otherwise the flag permanently neuters stopCamera(), the old
        // backend leaks (its HAL device stays claimed), and on single-HAL devices every later
        // camera open fails until process death. Restore the hold after a successful start.
        val resumeTorchHold = cameraHeldForTorch
        cameraHeldForTorch = false
        stopCamera()
        h264StreamingEncoder?.awaitRelease()
        // stopCamera() hands a Camera1 device to its own thread to close. Opening a camera before that ends
        // fails, or starves CameraX of frames and gets Camera2 blamed for it.
        if (!Camera1Capture.awaitReleased(CAMERA1_RELEASE_WAIT_MS)) {
            Log.w(TAG, "Camera1 still closing after ${CAMERA1_RELEASE_WAIT_MS}ms, starting the camera anyway")
        }
        try {
            val generation = settingsGeneration
            val want = desiredSize()
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // If no camera id has been resolved yet (fresh process / the UI never sent a camera
            // control), pin the facing-default to a real Camera2 id. Otherwise `camId()` falls back
            // to "back"/"front" and every per-camera pref (rotate/zoom/exposure/...) is written and
            // read under a placeholder key, while `/info.json` lensSettings looks them up by the real
            // camera id — so on page refresh those values appear reset to defaults.
            if (selectedCameraId == null) {
                firstCameraIdForFacing(frontFacing)?.let { resolved ->
                    selectedCameraId = resolved
                    prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
                }
            }
            val demand = cameraDemand()
            val surfaceEncoder = supportsSurfaceEncoder()
            val fps = streamFps()
            val camera = camId()
            val cameraXMissed = cameraXMissedRecently(prefs, camera)
            // The on-phone preview is only wired to CameraX, so the idle/preview-only state is
            // ALWAYS CameraX — its Preview use case renders even on devices whose Camera2 session
            // never configures ImageAnalysis/ImageCapture. The backend choice only matters when
            // frames are actually needed (clients / recording / snapshot in flight): then honor the
            // API preference and a recent CameraX start without frames, which route to the Camera1 backend.
            val useCamera1 = demand.frames && (chooseApi() == "camera1" || cameraXMissed)
            if (useCamera1) {
                if (cameraXMissed) {
                    Log.i(TAG, "CameraX recently bound without frames on this device and frames are needed, using Camera1 backend")
                }
                startCamera1Backend(want, demand)
            } else {
                startCameraXBackend(want, demand, surfaceEncoder)
                // CameraX can bind without the session ever configuring (e.g. "Unable to configure
                // camera ... TimeoutException") — no analysis frames ever arrive. Detect that and fall
                // back to the Camera1 backend so streaming and snapshots keep working on such devices.
                if (demand.frames) {
                    val framed = waitForCameraXFrame()
                    // While a Camera1 device closes, or soon after a slow close that may have taken
                    // cameraserver down with it, a start without frames says nothing about Camera2.
                    if (framed || !Camera1Capture.unsettled(CAMERA1_SETTLE_MS)) recordCameraXFrames(prefs, camera, framed)
                    if (!framed) {
                        Log.w(TAG, "CameraX bound but no frames within ${CAMERAX_FRAME_GRACE_MS}ms, falling back to Camera1")
                        stopCamera()
                        startCamera1Backend(want, demand)
                    }
                }
            }
            // Persist the active camera id so encoders and helpers can read per-camera prefs
            try {
                prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
            } catch (_: Exception) {}

            // Settings changed while this start ran (a Camera1 open can take seconds): it is not reusable.
            runningSession = if (generation == settingsGeneration) {
                CameraSession(demand, camera1 = backend is Camera1Capture, surfaceEncoder = surfaceEncoder, fps = fps, camera = camera)
            } else {
                null
            }
            runningStartedAtMs = SystemClock.elapsedRealtime()
            captureRunning = true
            encoders.forEach { it.start() }
            updateSubStream()
            // Still no viewers? The camera is open purely to keep the torch lit.
            if (resumeTorchHold && !hasActiveClients() && currentSurfaceProvider == null) {
                cameraHeldForTorch = true
            }
            // enableTorch can silently lose the race with a settling HAL; confirm it stuck.
            scheduleVerifyTorchRestored()
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

    /** A frame-needing start on [camera] uses Camera1 while a recent CameraX miss holds, see [CameraXFallback]. */
    private fun cameraXMissedRecently(prefs: SharedPreferences, camera: String): Boolean = CameraXFallback.useCamera1(
        prefs.getInt(CameraXFallback.missesKey(camera), 0),
        prefs.getLong(CameraXFallback.missedAtKey(camera), 0L),
        System.currentTimeMillis()
    )

    /** Records a frame-needing CameraX start on [camera]: frames clear its misses, none adds one and restarts the hold. */
    private fun recordCameraXFrames(prefs: SharedPreferences, camera: String, framed: Boolean) {
        val missesKey = CameraXFallback.missesKey(camera)
        val missedAtKey = CameraXFallback.missedAtKey(camera)
        if (framed) {
            if (prefs.contains(missesKey)) prefs.edit().remove(missesKey).remove(missedAtKey).apply()
            return
        }
        val misses = prefs.getInt(missesKey, 0) + 1
        prefs.edit()
            .putInt(missesKey, misses)
            .putLong(missedAtKey, System.currentTimeMillis())
            .apply()
        Log.w(TAG, "CameraX bound without frames on camera $camera ($misses in a row), frame-needing starts use Camera1 for the next ${CameraXFallback.holdMs(misses) / 60_000} min")
    }

    /** Ends the Camera1 hold on every camera, so the next frame-needing start tests CameraX again. */
    private fun forgetCameraXMisses(prefs: SharedPreferences) {
        val edit = prefs.edit().remove(CameraXFallback.LEGACY_PREF)
        prefs.all.keys.filter { CameraXFallback.isRecordKey(it) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private suspend fun startCamera1Backend(want: Size, demand: CameraDemand) {
        // Camera1 → GL pipe → surface encoder (true 1920×1080 on legacy HALs). Opening the camera and
        // starting its preview block for seconds on some HALs (24 s seen), so both run on the Camera1 thread.
        val selectedId = selectedCameraId
        val front = frontFacing
        val cap = Camera1Capture.open({ camera1IndexForSelectedOrFacing(selectedId, front) }, want.width, want.height)
        // Read on every frame, so an MJPEG viewer joining a session started for motion detection is fed too.
        // Both skip frames they have no use for (no MJPEG viewer, motion detection off).
        val onFrame = { nv21: ByteArray ->
            mjpegStreamingEncoder?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
            motionMonitor?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
            Unit
        }.takeIf { demand.nv21 }
        try {
            val pipe = newPipe(Size(cap.chosenW, cap.chosenH))
            cap.start(pipe.surfaceTexture, onFrame = onFrame)
        } catch (e: Exception) {
            // Not the backend yet, so stopCamera() cannot release it. Its pipe and encoder go once the
            // preview stops.
            val pipe = glPipe
            glPipe = null
            val enc = h264StreamingEncoder?.detachEncoder()
            cap.stop(afterPreview = { pipe?.stop(); enc?.stop() })
            throw e
        }
        backend = cap
        Log.i(TAG, "stream ${camId()} api=camera1 ${cap.chosenW}x${cap.chosenH}")
    }

    private fun startCameraXBackend(want: Size, demand: CameraDemand, surfaceEncoder: Boolean) {
        // CameraX → ImageAnalysis YUV → encoders + optional Preview on the phone.
        val h264 = h264StreamingEncoder
        var h264SurfaceProvider: Preview.SurfaceProvider? = null

        if (h264 != null && demand.h264 && surfaceEncoder) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
            val fpsCoerced = fps.coerceIn(1, 60)
            fun newEncoder(size: Size) = H264HardwareEncoder(
                size.width,
                size.height,
                fpsCoerced,
                H264HardwareEncoder.bitrateFor(size.width, size.height),
                true // useSurface = true
            ) { d, k, pts -> h264.broadcastH264(d, k, pts) }

            // Encode in the picture's shape (portrait when the camera image is upright portrait).
            // If the encoder rejects the portrait size, fall back to the byte-buffer YUV path,
            // which rotates in software — never to a landscape surface frame, which would make a
            // 90°/270° stream look horizontal.
            val frame = surfaceEncoderSize(want)
            val enc = try {
                newEncoder(frame)
            } catch (e: Exception) {
                if (frame == want) throw e
                Log.w(TAG, "H.264 encoder rejected ${frame.width}x${frame.height}; falling back to software-rotated YUV: ${e.message}")
                null
            }

            if (enc != null) {
                h264.setEncoder(enc)
                streamingServerHelper?.resetH264Wait()

                // Route CameraX frames through a GL pipe so mirror can be applied
                // without restarting the camera (the pipe reads mirror on each frame).
                val pipe = CameraGlPipe(enc.inputSurface!!, enc.width, enc.height, fpsCoerced, standardBuffer = true).also {
                    it.mirror = readMirrorPref()
                    it.rotation = quantizedRotation(readRotatePref())
                    it.start()
                    cameraXGlPipe = it
                }

                h264SurfaceProvider = Preview.SurfaceProvider { request ->
                    val surface = pipe.inputSurface
                    if (surface != null && surface.isValid) {
                        // Render at the resolution CameraX negotiated rather than the pipe's
                        // placeholder buffer size, so the selected resolution is what gets captured.
                        pipe.setCameraBufferSize(request.resolution.width, request.resolution.height)
                        Log.i(TAG, "Providing CameraX H.264 surface via GL pipe (${request.resolution}, mirror=${pipe.mirror})")
                        enc.hasSurfaceBeenProvided = true
                        request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { result ->
                            Log.i(TAG, "CameraX H.264 surface released by CameraX (result code: ${result.resultCode})")
                        }
                    } else {
                        request.willNotProvideSurface()
                    }
                }
            } else {
                h264.setEncoder(null)
            }
        } else if (h264 != null) {
            // Fall back to YUV mode immediately (previous method)
            h264.setEncoder(null)
        }

        val showScreenPreview = demand.screenPreview
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
                // Motion detection reads the luma plane without disturbing the encoders' buffers.
                motionMonitor?.processFrame(img)
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
        stopSubStream()   // detach it from the GL pipe while the pipe is still running
        val old = backend
        backend = null
        if (old is Camera1Capture) {
            // Camera1 closes on its own thread (over 20 s on some HALs) and draws into this pipe until its
            // preview stops there, so the pipe and then the encoder it feeds are stopped there too. The
            // next start waits for the release.
            val pipe = glPipe
            glPipe = null
            val enc = h264StreamingEncoder?.detachEncoder()
            old.stop(afterPreview = { pipe?.stop(); enc?.stop() })
        } else {
            cameraCloseStartTime = System.currentTimeMillis()
            old?.stop()
            val closeDuration = System.currentTimeMillis() - cameraCloseStartTime
            if (closeDuration > 1000) {
                Log.w(TAG, "Camera close took ${closeDuration}ms — HAL is slow to release resources")
            }
        }
        cameraXGlPipe?.stop(); cameraXGlPipe = null
        glPipe?.stop(); glPipe = null
        encoders.forEach { it.stop() }
        motionMonitor?.reset()
        runningSession = null
        captureRunning = false
        if (!hasActiveClients()) releaseStreamingWakeLock()
    }

    // ---------------- RTSP sub stream ----------------

    /**
     * Runs the RTSP sub stream's own small encoder while a /live/sub viewer is connected and the
     * H.264 picture goes through a GL pipe, which then draws every frame into it too. Without a
     * pipe (byte-buffer capture: front camera, LIMITED devices) the sub stream carries the main
     * encoder's frames instead.
     */
    private fun updateSubStream() {
        val rtsp = rtspServer ?: return
        val h264 = h264StreamingEncoder ?: return
        val pipe = cameraXGlPipe ?: glPipe
        if (pipe == null || !rtsp.hasSubVideoClients()) {
            stopSubStream()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val shortSide = prefs.getString(RtspServer.PREF_SUB_SIZE, null)?.toIntOrNull() ?: RtspServer.DEFAULT_SUB_SIZE
        val fps = (prefs.getString(RtspServer.PREF_SUB_FPS, null)?.toIntOrNull() ?: RtspServer.DEFAULT_SUB_FPS).coerceIn(1, 60)
        val kbps = prefs.getString(RtspServer.PREF_SUB_BITRATE, null)?.toIntOrNull() ?: RtspServer.DEFAULT_SUB_BITRATE_KBPS
        // A viewer joining no longer restarts the camera, so rebuild the sub encoder here when its
        // settings changed since it started.
        val settings = listOf(shortSide, fps, kbps)
        if (subEncoder != null) {
            if (settings == subEncoderSettings) return
            stopSubStream()
        }
        val bitrate = H264HardwareEncoder.clampBitrate(kbps * 1000L)
        val (w, h) = CameraGlPipe.scaledFrame(pipe.width, pipe.height, shortSide)
        val enc = try {
            H264HardwareEncoder(w, h, fps, bitrate, true, RtspServer.SUB_KEYFRAME_INTERVAL_SEC) { d, k, pts ->
                rtsp.onVideoFrame(d, k, pts, RtspStream.SUB)
            }
        } catch (e: Exception) {
            Log.w(TAG, "RTSP sub stream encoder ${w}x$h failed, sub stream carries the main stream: ${e.message}")
            return
        }
        subEncoder = enc
        subEncoderSettings = settings
        // Switch the sub stream's source: viewers wait for the new encoder's first keyframe.
        h264.subStreamEncoded = true
        rtsp.resyncVideo(RtspStream.SUB)
        if (!pipe.setSecondaryOutput(enc.inputSurface, w, h, fps)) {
            Log.w(TAG, "RTSP sub stream could not be attached to the GL pipe, sub stream carries the main stream")
            stopSubStream()
            return
        }
        enc.requestKeyFrame()
        Log.i(TAG, "RTSP sub stream ${w}x$h @${fps}fps ${bitrate / 1000}kbps")
    }

    private fun stopSubStream() {
        val enc = subEncoder ?: return
        subEncoder = null
        subEncoderSettings = null
        h264StreamingEncoder?.subStreamEncoded = false
        rtspServer?.resyncVideo(RtspStream.SUB)
        h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
        (cameraXGlPipe ?: glPipe)?.setSecondaryOutput(null)
        enc.stop()
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

    /** Per-camera rotate= knob (token then physical id fallback). Consumers apply their own
     *  quantisation: the byte-buffer H.264 encoder snaps to 90° (YUV rotate cost) and the GL pipe
     *  only acts on 90° multiples. Returns the raw stored angle. */
    private fun readRotatePref(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId() ?: return 0
        val phys = id.substringAfter(':', id)
        return when {
            prefs.contains("camera_rotate_$id") -> prefs.getInt("camera_rotate_$id", 0)
            prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
            else -> 0
        }
    }

    /** Quantise a rotation to the nearest 90°, matching the H.264 byte-buffer encoder's snapping
     *  (the raw value is persisted verbatim for the MJPEG pipeline, which rotates arbitrary angles). */
    private fun quantizedRotation(angle: Int): Int {
        val norm = ((angle % 360) + 360) % 360
        return ((norm + 45) / 90 * 90) % 360
    }

    /** Surface-mode encoder + GL pipe, used by Camera1 H.264. [cameraSize] is the size Camera1
     *  produces; the encoder is sized to the picture's shape after the manual rotate= control. */
    private fun newPipe(cameraSize: Size): CameraGlPipe {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
        val fpsCoerced = fps.coerceIn(1, 60)
        val frame = surfaceEncoderSize(cameraSize)
        val enc = H264HardwareEncoder(frame.width, frame.height, fpsCoerced, H264HardwareEncoder.bitrateFor(frame.width, frame.height), true) { d, k, pts -> h264StreamingEncoder?.broadcastH264(d, k, pts) }
        h264StreamingEncoder?.setEncoder(enc)
        streamingServerHelper?.resetH264Wait()
        return CameraGlPipe(enc.inputSurface!!, frame.width, frame.height, fpsCoerced).also {
            it.mirror = readMirrorPref()
            it.rotation = quantizedRotation(readRotatePref())
            it.start()
            // Camera1 renders at [cameraSize]; the pipe turns that frame into [frame].
            it.setCameraBufferSize(cameraSize.width, cameraSize.height)
            glPipe = it
        }
    }

    private fun applyStored(b: CaptureBackend) {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        // The torch is device-level, not per-camera, so it is restored ahead of the early return
        // below — the per-camera settings need a resolved camera id, the torch does not.
        if (p.getString(PREF_CAMERA_TORCH, "off") == "on") {
            b.setTorch(true)
            // The active lens may not own the flash unit (ultra-wide/depth); anchor the torch to
            // the flash-capable rear camera instead so a lens switch never strands the state.
            if (!b.hasFlashUnit) setDeviceTorch(true)
        }
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

    /** Rear camera id that owns the flash unit (usually the main lens). Auxiliary rear lenses on
     *  multi-camera phones often report no flash unit, yet physically share this one. */
    private fun flashAnchorCameraId(): String? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.firstOrNull { id ->
            try {
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
            } catch (_: Exception) { false }
        }
    } catch (_: Exception) { null }

    /**
     * Torch through [CameraManager.setTorchMode] — works with NO camera session open, so it keeps
     * the LED lit while a flash-less lens (ultra-wide/depth) is streaming or while the camera
     * restarts between lens switches. Returns false if the platform rejected it.
     */
    private fun setDeviceTorch(on: Boolean): Boolean {
        deviceTorchOn = try {
            val id = flashAnchorCameraId() ?: return false
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            on
        } catch (e: Exception) {
            // Releasing the device torch while the camera session owns the flash
            // is expected to fail with CAMERA_IN_USE — not a real error.
            val msg = e.message ?: ""
            if (!( !on && msg.contains("CAMERA_IN_USE"))) {
                Log.w(TAG, "device torch($on): $msg")
            }
            false
        }
        return deviceTorchOn
    }

    /**
     * Going idle (last viewer left, no on-phone preview)? Hand the torch to the device channel
     * BEFORE/AS the streaming session closes: [CameraManager.setTorchMode] needs no camera open,
     * so a page reload or preview-off no longer takes the LED down with the session. The post-start
     * verifier ([scheduleVerifyTorchRestored]) reclaims it for the next stream.
     */
    private fun bridgeTorchAcrossIdle() {
        if (hasActiveClients() || currentSurfaceProvider != null) return
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_CAMERA_TORCH, "off") != "on") return
        setDeviceTorch(true)
    }

    /**
     * Torch restoration is best-effort at start time — enableTorch can fail while the HAL is still
     * settling after a bind, and opening any camera can strip a device-channel torch. Re-check
     * briefly and re-apply until it sticks, the user toggles it off, or the camera stops again.
     */
    private fun scheduleVerifyTorchRestored() {
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getString(PREF_CAMERA_TORCH, "off") != "on") {
            if (deviceTorchOn) setDeviceTorch(false)
            return
        }
        torchVerifyJob?.cancel()
        torchVerifyJob = lifecycleScope.launch(Dispatchers.Main) {
            // enableTorch reports failure ASYNC via its future (which flips getTorch() back to
            // false), so the state must survive several consecutive checks before it is trusted.
            var confirmed = 0
            repeat(16) {
                kotlinx.coroutines.delay(250)
                if (!captureRunning) return@launch
                val b = backend
                when (PreferenceManager.getDefaultSharedPreferences(this@StreamingService)
                        .getString(PREF_CAMERA_TORCH, "off")) {
                    "on" -> if (b?.hasFlashUnit == true) {
                        if (b.getTorch()) {
                            confirmed++
                            if (confirmed == 1) {
                                // Session owns the LED now; drop any lingering device-channel
                                // claim so the two mechanisms can't fight over one flash unit.
                                if (deviceTorchOn) setDeviceTorch(false)
                            } else if (confirmed >= 4) {
                                return@launch
                            }
                        } else {
                            confirmed = 0
                            b.setTorch(true)
                        }
                    } else {
                        // Device channel has no queryable state; re-arm through the settle window
                        // in case the framework stripped it while a camera was opening.
                        confirmed++
                        setDeviceTorch(true)
                        if (confirmed >= 8) return@launch
                    }
                    "off" -> {
                        if (deviceTorchOn) setDeviceTorch(false)
                        return@launch
                    }
                    else -> return@launch
                }
            }
        }
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
                    launchMain {
                        // Decided when this runs: a viewer may have joined during the capture.
                        if (hadViewers || !stopCameraWhenIdle()) startCamera() else {
                            // The snapshot's temporary session may have stripped the idle torch.
                            bridgeTorchAcrossIdle()
                        }
                    }
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
                        if (cameraXMissedRecently(prefs, idForPref)) {
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
                        // Only a settled session says something about Camera2: not one replaced during the
                        // wait (a viewer restart), not one a start in flight is still measuring (it records
                        // itself), and not one bound while Camera1 closes or soon after a slow close.
                        if (backend === live && !cameraMutex.isLocked && !Camera1Capture.unsettled(CAMERA1_SETTLE_MS)) {
                            recordCameraXFrames(prefs, idForPref, framed = false)
                        }
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
            result = out[0]?.let { rotateSnapshot(it, readRotatePref()) }
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

    /**
     * Turn a still's pixels by the manual rotate= setting. EXIF is not touched, so the image
     * carries no orientation tag telling a viewer to rotate it on top of the manual angle.
     */
    private fun rotateSnapshot(jpeg: ByteArray, degrees: Int): ByteArray {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0) return jpeg
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            try {
                val matrix = Matrix().apply { postRotate(angle.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                try {
                    if (rotated === bmp) return jpeg
                    val out = ByteArrayOutputStream(jpeg.size)
                    try {
                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.toByteArray()
                    } finally { out.close() }
                } finally { if (rotated !== bmp) rotated.recycle() }
            } finally { bmp.recycle() }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "snapshot rotate OOM")
            try { System.gc() } catch (_: Exception) {}
            jpeg
        } catch (e: Exception) {
            Log.e(TAG, "snapshot rotate: ${e.message}")
            jpeg
        }
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
                    awaitCameraSettled()
                    if (next && !captureRunning) {
                        // Nothing is streaming, so there is no backend to talk to. Open the camera
                        // just for the torch and record that this session is ours to close.
                        cameraHeldForTorch = true
                        startCamera(force = true)
                    }
                    backend?.setTorch(next)
                    // The active lens may not own the flash unit (ultra-wide/depth) — drive the
                    // torch through the flash-capable rear camera instead so it still lights.
                    // On "off" always clear the device channel too: a lens switch can have moved
                    // the torch between mechanisms.
                    if (backend?.hasFlashUnit != true || !next) setDeviceTorch(next)
                    if (!next && cameraHeldForTorch) {
                        cameraHeldForTorch = false
                        stopCameraWhenIdle()
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
                    awaitCameraSettled()
                    if (captureRunning) {
                        debouncedStartCamera(force = true)
                        if (keepTorchOn) backend?.setTorch(true)
                    }
                }
            }
            "resolution" -> {
                when {
                    value in listOf("low", "medium", "high") -> {
                        // A preset only takes effect when no explicit size is selected, so clear
                        // any saved WxH; otherwise desiredSize() would keep using the old size.
                        prefs.edit()
                            .putString("camera_resolution", value)
                            .putString("stream_res", "auto")
                            .apply()
                        debouncedStartCamera()
                    }
                    value == "auto" || value == "max" -> {
                        prefs.edit().putString("stream_res", "auto").apply()
                        debouncedStartCamera()
                    }
                    else -> {
                        val m = Regex("^(\\d+)x(\\d+)$").find(value) ?: return
                        val w = m.groupValues[1].toIntOrNull()
                        val h = m.groupValues[2].toIntOrNull()
                        if (w == null || h == null || w <= 0 || h <= 0) return
                        // Store the normalised size the stream will actually use, so /info.json
                        // reports exactly what the user selected.
                        prefs.edit().putString("stream_res", "${w}x${h}").apply()
                        debouncedStartCamera()
                    }
                }
            }
            // RTSP sub stream and audio settings. The RTSP server reads them when a viewer
            // connects, so a change applies to the next connection (as the old Settings screen did).
            "rtsp_audio" -> {
                if (value.equals("true", true) || value.equals("false", true)) {
                    prefs.edit().putBoolean(RtspServer.PREF_AUDIO, value.toBoolean()).apply()
                }
            }
            "rtsp_sub_size" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(240, 360, 480, 720)) {
                    prefs.edit().putString(RtspServer.PREF_SUB_SIZE, n.toString()).apply()
                }
            }
            "rtsp_sub_fps" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(10, 15, 20, 30)) {
                    prefs.edit().putString(RtspServer.PREF_SUB_FPS, n.toString()).apply()
                }
            }
            "rtsp_sub_bitrate" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(300, 500, 800, 1200, 2000)) {
                    prefs.edit().putString(RtspServer.PREF_SUB_BITRATE, n.toString()).apply()
                }
            }
            "rotate" -> {
                val angle = value.toIntOrNull() ?: return
                // normalize to 0..359
                val norm = ((angle % 360) + 360) % 360
                // Persist rotate per-camera
                prefs.edit().putInt("camera_rotate_$id", norm).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putInt("camera_rotate_$physicalId", norm).apply()
                // H.264 only supports 90° steps (the raw value is persisted verbatim for the MJPEG
                // pipeline, which rotates arbitrary angles). Apply the quantised value live to the
                // surface-mode GL pipes so they snap to the same steps the byte-buffer encoder uses.
                val q = quantizedRotation(norm)
                glPipe?.rotation = q
                cameraXGlPipe?.rotation = q
                // The surface encoder's frame follows the picture's shape, so a quarter turn needs
                // a new encoder (the byte-buffer encoder resizes itself per frame). Applies to both
                // the CameraX and Camera1 GL pipes. Not while recording: restarting the camera
                // would end the recording.
                val current = h264StreamingEncoder?.h264HardwareEncoder
                val camera1Pipe = glPipe
                val pipe = cameraXGlPipe ?: camera1Pipe
                if (pipe != null && current != null && localRecorder?.isRecording != true) {
                    val cameraSize = if (camera1Pipe != null && camera1Pipe.frameWidth > 0 && camera1Pipe.frameHeight > 0) {
                        Size(camera1Pipe.frameWidth, camera1Pipe.frameHeight)
                    } else {
                        desiredSize()
                    }
                    val target = surfaceEncoderSize(cameraSize)
                    if (target.width != current.width || target.height != current.height) debouncedStartCamera()
                }
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
                    // CameraX starts without frames).
                    if (value != "camera1") forgetCameraXMisses(prefs)
                    debouncedStartCamera()
                }
            }
            MotionMonitor.PREF_ENABLED -> {
                val enabled = when (value.lowercase()) {
                    "true", "on", "1" -> true
                    "false", "off", "0" -> false
                    else -> return
                }
                prefs.edit().putBoolean(MotionMonitor.PREF_ENABLED, enabled).apply()
                motionMonitor?.onSettingsChanged()
                if (!enabled) {
                    // Turning Record on Motion off also ends any motion clip immediately.
                    motionStopJob?.cancel()
                    motionStopJob = null
                    val recorder = localRecorder
                    if (recorder?.isRecording == true && recorder.trigger == LocalRecorder.Trigger.MOTION) {
                        lifecycleScope.launch(Dispatchers.IO) { stopRecording() }
                    }
                }
                launchMain {
                    if (enabled) {
                        awaitCameraSettled()
                        if (captureRunning) debouncedStartCamera() else startCameraIfNeeded()
                    } else if (localRecorder?.isRecording != true &&
                        !encoders.any { it.hasClients() } && currentSurfaceProvider == null) {
                        // Motion was the only frame consumer: release the camera again.
                        stopCameraWhenIdle()
                        bridgeTorchAcrossIdle()
                    }
                }
            }
            MotionMonitor.PREF_SENSITIVITY -> {
                val sensitivity = value.toIntOrNull()?.coerceIn(1, 10) ?: return
                prefs.edit().putString(MotionMonitor.PREF_SENSITIVITY, sensitivity.toString()).apply()
            }
            MotionMonitor.PREF_STOP_DELAY -> {
                val delay = value.toIntOrNull()?.coerceIn(0, MotionMonitor.MAX_STOP_DELAY) ?: return
                prefs.edit().putString(MotionMonitor.PREF_STOP_DELAY, delay.toString()).apply()
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

    fun startRecording(trigger: LocalRecorder.Trigger = LocalRecorder.Trigger.MANUAL): RecordingResponse {
        synchronized(recordingLock) {
            if (localRecorder?.isRecording == true) return Triple(false, "409 Conflict",
                """{"error":"already_recording","message":"Recording is already in progress","uri":"${localRecorder?.recordingLocation() ?: ""}"}"""
            )

            // Make the camera deliver frames and initialize the H.264 encoder even with no viewers
            // connected; cleared once localRecorder takes over (or the start fails).
            recordingNeeded = true

            // Ensure camera and H.264 encoder are started on demand. Motion detection counts as an
            // active client, so checking hasActiveClients() alone would skip the restart needed to
            // create the H.264 encoder for a motion-triggered recording.
            var enc = h264StreamingEncoder?.h264HardwareEncoder
            if (!captureRunning || enc == null) {
                val latch = CountDownLatch(1)
                launchMain {
                    startCamera(force = true)
                    latch.countDown()
                }
                try { latch.await(3, TimeUnit.SECONDS) } catch (_: Exception) {}
            }

            var attempts = 0
            while (enc == null && attempts < 100) {
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
                val recorder = LocalRecorder(this, enc.width, enc.height, trigger)
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
    }

    fun stopRecording(): RecordingResponse {
        synchronized(recordingLock) {
            val recorder = localRecorder ?: return Triple(false, "409 Conflict",
                """{"error":"not_recording","message":"No recording is in progress"}"""
            )
            return try {
                if (recorder.trigger == LocalRecorder.Trigger.MOTION) {
                    motionStopJob?.cancel()
                    motionStopJob = null
                }
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
    }

    fun getRecordingStatus(): String {
        return localRecorder?.statusJson()
            ?: """{"recording":false}"""
    }

    /** Motion true starts a motion clip; motion false schedules the auto-stop after the set delay. */
    private fun handleMotionRecording(active: Boolean) {
        if (active) {
            motionStopJob?.cancel()
            motionStopJob = null
            lifecycleScope.launch(Dispatchers.IO) { startMotionRecordingIfNeeded() }
        } else {
            scheduleMotionRecordingStop()
        }
    }

    private fun startMotionRecordingIfNeeded() {
        if (!motionEnabled() || localRecorder?.isRecording == true) return
        val (ok, status, body) = startRecording(LocalRecorder.Trigger.MOTION)
        if (!ok) {
            Log.w(TAG, "Motion recording failed to start: $status $body")
            return
        }
        // Record on Motion may have been turned off, or motion cleared, while the camera/encoder
        // was starting. In both cases the clip should not linger.
        if (!motionEnabled()) {
            stopRecording()
        } else if (motionMonitor?.detector?.active != true) {
            scheduleMotionRecordingStop()
        }
    }

    private fun scheduleMotionRecordingStop() {
        val recorder = localRecorder
        if (recorder?.isRecording != true || recorder.trigger != LocalRecorder.Trigger.MOTION) return
        motionStopJob?.cancel()
        val delaySec = motionMonitor?.stopDelaySeconds ?: MotionMonitor.DEFAULT_STOP_DELAY
        motionStopJob = lifecycleScope.launch(Dispatchers.IO) {
            if (delaySec > 0) kotlinx.coroutines.delay(delaySec * 1000L)
            val current = localRecorder
            if (current?.isRecording == true &&
                current.trigger == LocalRecorder.Trigger.MOTION &&
                motionMonitor?.detector?.active != true) {
                stopRecording()
            }
        }
    }

    /**
     * Bring the camera back to the no-viewer baseline: fully stop it when nothing else needs it,
     * or keep/restart it in preview-only mode when the on-phone preview surface is attached.
     * Does nothing while network streaming clients are still connected.
     */
    private fun restoreNoViewerCameraState() {
        if (encoders.any { it.hasClients() }) return
        // Motion detection needs the camera to keep delivering frames with no viewer connected.
        // Stopping here (e.g. after a motion clip auto-stops) would end detection until the next
        // client connects, so leave the running pipeline alone.
        if (motionEnabled()) return
        launchMain {
            if (currentSurfaceProvider != null) {
                debouncedStartCamera()
            } else {
                stopCameraWhenIdle()
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

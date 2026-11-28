package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.annotation.SuppressLint
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
import android.media.MediaCodecInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.*
import com.github.digitallyrefined.androidipcamera.opentunnel.LocalTlsBridge
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelConfig
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelController
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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
    private val encoders: List<StreamingEncoder>
        get() = listOfNotNull(h264StreamingEncoder, mjpegStreamingEncoder)

    /** A recording mode other than "off": the camera must keep delivering frames with no viewer. */
    private fun cameraAlwaysOn(): Boolean = motionMonitor?.cameraAlwaysOn == true

    /** Frame-difference motion detection is active in the selected recording mode. */
    private fun motionDetectionEnabled(): Boolean = motionMonitor?.motionDetection == true

    /**
     * Camera AE auto-night-mode is selected. Metering is passive: it samples whatever frames the
     * running session already produces and never starts the camera or keeps it open by itself.
     * On demand therefore releases the camera normally; Camera always on keeps it running for its
     * own reasons and Camera AE samples continuously.
     */
    private fun cameraAeSelected(): Boolean =
        AutoNightMode.modeIncludesCameraAe(AutoNightMode.mode(this))

    /** On demand releases the camera when the last consumer leaves. */
    private fun onDemandMode(): Boolean = MotionMonitor.modeFor(this) == MotionMonitor.MODE_OFF

    /**
     * An On demand camera still has a consumer, even if it is momentarily restarting. This keeps
     * the automatic torch alive across camera restarts and only drops it once the session is idle.
     */
    private fun onDemandCameraInUse(): Boolean =
        captureRunning || hasActiveClients() || currentSurfaceProvider != null ||
            snapshotInProgress || recordingNeeded

    /**
     * Camera AE and LUX read the scene the torch itself lights. While the automatic torch is on in
     * On demand, keep that session's darkness decision latched instead of letting the lit scene
     * immediately turn the torch back off.
     */
    private fun triggerLatchesTorch(mode: String): Boolean =
        AutoNightMode.modeIncludesCameraAe(mode) || AutoNightMode.modeIncludesLux(mode)

    /** Loopback TLS bridge used when the local web server speaks HTTPS and OpenTunnel forwards plain HTTP. */
    @Volatile private var openTunnelBridge: LocalTlsBridge? = null
    private val openTunnelLock = Any()

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
    /** Shared OSD renderer, so MJPEG, the GL H.264 pipe and the CPU H.264 path agree. */
    private val osdRenderer by lazy {
        CameraOverlayRenderer(applicationContext) { currentOsdCameraName() }
    }
    /** Serializes manual and motion-triggered recording start/stop transitions. */
    private val recordingLock = Any()
    /** Pending auto-stop for a motion-triggered recording (cancelled when motion resumes). */
    @Volatile private var motionStopJob: kotlinx.coroutines.Job? = null
    /** Segmented continuous-recording loop (null unless the continuous mode is active). */
    @Volatile private var continuousRecordingJob: kotlinx.coroutines.Job? = null
    /** Smaller encoder feeding the RTSP sub stream (/live/sub) while it has viewers. */
    @Volatile private var subEncoder: H264HardwareEncoder? = null
    /** Sub stream settings the running sub encoder was built with. */
    private var subEncoderSettings: List<Int>? = null

    /**
     * Keeps the CPU awake while a viewer is connected. Without it the device enters deep sleep
     * shortly after the screen turns off and the camera stops delivering frames.
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

    /**
     * Night mode replaces live video with a loop of night-sight stills pushed into the normal
     * MJPEG/H.264 streams. [nightModeSelected] is the persisted/automatic choice, while
     * [nightModeEnabled] is the state actually able to capture right now. A lit torch suspends the
     * selected mode without clearing it, then restores it when the flash turns off.
     */
    @Volatile private var nightModeSelected = false
    @Volatile private var nightModeEnabled = false
    @Volatile private var nightCapture: NightStillCapture? = null
    @Volatile private var nightCaptureReady = false
    @Volatile private var nightCaptureCamera: String? = null
    @Volatile private var nightCaptureSize: Size? = null
    @Volatile private var nightCaptureExposureMs: Long? = null
    @Volatile private var nightCaptureIso: Int? = null
    private var nightModeJob: kotlinx.coroutines.Job? = null
    private var nightModeGeneration = 0
    /**
     * Camera whose still-capture pipeline kept timing out. The night profile stays selected (so
     * grayscale and the camera-AE decision keep working) but frames come from the normal live
     * video session instead of a black/frozen still loop. Reset only by a process restart or a
     * different camera, so a broken still HAL is not retried on every night transition.
     */
    @Volatile private var nightVideoFallbackCamera: String? = null
    /** Polls the sun position for the auto-night-mode option; runs for the service lifetime. */
    private var autoNightModeJob: kotlinx.coroutines.Job? = null
    /** Battery-temperature safety guard; runs for the service lifetime. */
    private var overheatJob: kotlinx.coroutines.Job? = null
    /** Last automatic-night-mode decision: true = schedule says night, null = unknown/disabled. */
    @Volatile private var autoNightScheduleOn: Boolean? = null

    /**
     * Motion-triggered flash cycle. While active the night stills and grayscale are paused, the
     * torch is held on, and the off timer runs once motion clears. When it ends, both are restored.
     */
    @Volatile private var autoFlashCycleActive = false
    private var autoFlashOffJob: kotlinx.coroutines.Job? = null
    private var autoFlashTorchJob: kotlinx.coroutines.Job? = null

    /** Continuous torch while the automatic night profile is active. */
    @Volatile private var alwaysTorchActive = false
    /** Overheat protection has paused streaming and must also hold the torch off. */
    @Volatile private var overheatPaused = false
    private var alwaysTorchJob: kotlinx.coroutines.Job? = null

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
    /** Throttles Camera1 error-driven restarts so a failing HAL cannot cause a restart loop. */
    @Volatile private var lastCamera1ErrorRestartAt = 0L

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
        /** Alerts when motion is seen in the always-on and motion-clip modes. */
        private const val MOTION_NOTIFICATION_ID = 2
        private const val MOTION_CHANNEL_ID = "motion_events_channel"
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
        /** CameraX grace period for the first encoded frame from its H.264 GL surface. Some HALs
         *  release that surface shortly after providing it; if no frame arrives, an Auto start
         *  falls back to Camera1 instead of streaming from a dead surface. */
        private const val CAMERAX_SURFACE_GRACE_MS = 2_500L
        /** A viewer change waits this long, so a burst of connections or a reconnect is decided once. */
        private const val VIEWER_CHECK_DELAY_MS = 300L
        /** Grace period before an On demand camera is fully released after its last viewer leaves. */
        private const val ON_DEMAND_IDLE_RELEASE_DELAY_MS = 300L
        /** A running session with no camera frame or encoder output for this long counts as stalled, and a viewer change restarts it. */
        private const val CAMERA_STALL_MS = 5_000L
        /** Longest a start waits for the previous Camera1 release (about 21 s seen) before opening anyway. */
        private const val CAMERA1_RELEASE_WAIT_MS = 30_000L
        /** A CameraX start without frames this soon after a slow Camera1 release is not counted as a miss. */
        private const val CAMERA1_SETTLE_MS = 30_000L
        /** Night stills are exposed for seconds, so restart immediately after each frame. */
        private const val NIGHT_MODE_FRAME_GAP_MS = 100L
        /** Fall back to live video after this many consecutive still-capture failures. */
        private const val NIGHT_MODE_MAX_FAILURES = 2
        /** Delay between failed night captures before retrying. */
        private const val NIGHT_MODE_RETRY_DELAY_MS = 2_000L
        /** How often to re-evaluate the sun position for automatic night mode. */
        private const val AUTO_NIGHT_POLL_MS = 30_000L
        /** Camera AE needs a faster poll to honour its 15 s entry dwell. */
        private const val AUTO_NIGHT_FAST_POLL_MS = 5_000L
        /** How often to check the optional battery-temperature streaming guard. */
        private const val OVERHEAT_POLL_MS = 30_000L
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
        const val ACTION_RESTART_SERVER = "com.github.digitallyrefined.androidipcamera.RESTART_SERVER"
        const val ACTION_START_SERVER = "com.github.digitallyrefined.androidipcamera.START_SERVER"
        const val ACTION_OPEN_TUNNEL_REGISTER = "com.github.digitallyrefined.androidipcamera.OPEN_TUNNEL_REGISTER"
        const val ACTION_OPEN_TUNNEL_CONNECT = "com.github.digitallyrefined.androidipcamera.OPEN_TUNNEL_CONNECT"
        const val ACTION_OPEN_TUNNEL_DISCONNECT = "com.github.digitallyrefined.androidipcamera.OPEN_TUNNEL_DISCONNECT"
        const val ACTION_OPEN_TUNNEL_FORGET = "com.github.digitallyrefined.androidipcamera.OPEN_TUNNEL_FORGET"
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
            ACTION_OPEN_TUNNEL_REGISTER -> { registerOpenTunnel(); return START_STICKY }
            ACTION_OPEN_TUNNEL_CONNECT -> { connectOpenTunnel(); return START_STICKY }
            ACTION_OPEN_TUNNEL_DISCONNECT -> { disconnectOpenTunnel(); return START_STICKY }
            ACTION_OPEN_TUNNEL_FORGET -> { forgetOpenTunnel(); return START_STICKY }
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
            stopOpenTunnel()
            streamingServerHelper?.stopServer()
            rtspServer?.stop()
            stopOnvif()
            kotlinx.coroutines.delay(500)
            streamingServerHelper?.startStreamingServer()
            startRtspServerIfEnabled()
            startOnvifIfEnabled()
            autoConnectOpenTunnel()
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
        if (prefs.contains(CameraXFallback.LEGACY_PREF)) prefs.edit { remove(CameraXFallback.LEGACY_PREF) }
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
        nightModeSelected = NightMode.isEnabled(this)
        if (nightModeSelected && !NightMode.supports(this, camId())) {
            nightModeSelected = false
            NightMode.setEnabled(this, false)
        }
        // On demand starts every camera session in day video and lets Camera AE re-detect. Clear a
        // night state left behind by an automatic switch before the last session ended.
        if (MotionMonitor.modeFor(this) == MotionMonitor.MODE_OFF &&
            AutoNightMode.modeIncludesCameraAe(AutoNightMode.mode(this)) &&
            nightModeSelected
        ) {
            Log.i(TAG, "On demand Camera AE starts in day mode; clearing the previous night state")
            nightModeSelected = false
            NightMode.setEnabled(this, false)
        }
        if (AutoNightMode.mode(this) == AutoNightMode.MODE_CAMERA_AE &&
            NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS && !onDemandMode()
        ) {
            Log.w(TAG, "Camera AE and always-on torch are incompatible in always-on modes; disabling the torch")
            NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
        }
        CameraLightMeter.reset()
        refreshFlashSuppression()
        nightModeEnabled = NightMode.isActive(this)
        applyGrayscaleState()
        startAutoNightModeLoop()
        AutoNightMode.syncLuxMonitor(this)
        startOverheatMonitor()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerReceiver(notificationChannelReceiver,
                IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startNotificationChannelCheckFallback()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
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
        AutoNightMode.stopLuxMonitor()
        AutoNightMode.setCurrentDark(null)
        NightMode.clearGrayscaleOverride()
        NightMode.clearNightSightOverride()
        NightMode.setFlashSuppressed(false)
        stopOnvif()
        stopOpenTunnel()
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
        // The camera/microphone type constants (and the ability to claim them) arrived in API 30;
        // API 29 only has the plain two-argument startForeground().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(cameraActive = true),
                    foregroundServiceTypes()
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
     * [force] re-applies the types even when they are already claimed, so a location permission
     * granted later can be added without restarting the service.
     */
    fun ensureForegroundTypes(force: Boolean = false): Boolean {
        if (whileInUseForegroundAllowed && !force) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            whileInUseForegroundAllowed = true
            return true
        }
        return try {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(cameraActive = true),
                foregroundServiceTypes()
            )
            markForegroundStarted(whileInUseAllowed = true)
            Log.i(TAG, "Foreground service types applied: ${foregroundServiceTypes()}")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Still not allowed to use camera/microphone in a foreground service", e)
            false
        }
    }

    /**
     * The foreground types to claim. Location is only included when the app may use it; otherwise
     * startForeground() rejects the whole call and streaming would lose camera/microphone access.
     * It keeps automatic night mode working while the app is in the background.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (AutoNightMode.hasLocationPermission(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
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

    /**
     * Posts the motion alert used by the always-on and motion-clip modes. Uses its own channel so
     * it can make a sound while the ongoing streaming notification stays quiet. Skipped when the
     * user denied the notification permission.
     */
    private fun notifyMotion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(
                        MOTION_CHANNEL_ID,
                        "Motion events",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Motion notification channel: ${e.message}")
            }
        }
        val contentPI = PendingIntent.getActivity(this, 3,
            Intent(this, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, MOTION_CHANNEL_ID)
            .setContentTitle("Motion detected")
            .setContentText("Android IP Camera saw movement")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(MOTION_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Motion notification: ${e.message}")
        }
    }

    // MainActivity attaches PreviewView here; camera restarts when a surface is set while streaming.
    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        // Recording owns the camera session: stopping or restarting the camera now would tear
        // down the encoder and finalize the file, so leave the pipeline alone until it stops.
        if (localRecorder?.isRecording == true) return
        val shouldRun = encoders.any { it.hasClients() } || currentSurfaceProvider != null || cameraAlwaysOn()
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
    fun hasActiveClients() = encoders.any { it.hasClients() } || localRecorder?.isRecording == true || cameraAlwaysOn()
    fun switchCamera() {
        selectCamera(!frontFacing, null)
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString(PREF_CAMERA_ID, camId())
        }
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

    private fun generateCertificateAndStart() {
        val pw = generateRandomPassword()
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@StreamingService, pw)
            if (certFile != null) {
                SecureStorage(this@StreamingService).putSecureString(SecureStorage.KEY_CERT_PASSWORD, pw)
                PreferenceManager.getDefaultSharedPreferences(this@StreamingService).edit { remove("certificate_path") }
                kotlinx.coroutines.delay(100)
                initServer()
            } else launch(Dispatchers.Main) {
                Toast.makeText(this@StreamingService, "Failed to generate certificate", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- OpenTunnel (public HTTPS URL) ----------------

    fun openTunnelStatus(): OpenTunnelStatus = OpenTunnelController.status(this)

    /** Create the tunnel identity if needed, then connect. */
    fun registerOpenTunnel() {
        lifecycleScope.launch(Dispatchers.IO) { startOpenTunnel(createIfNeeded = true) }
    }

    /** Connect an already registered identity. */
    fun connectOpenTunnel() {
        lifecycleScope.launch(Dispatchers.IO) { startOpenTunnel(createIfNeeded = false) }
    }

    /** Drop an active tunnel while keeping the registration files and preferences. */
    fun disconnectOpenTunnel() {
        lifecycleScope.launch(Dispatchers.IO) { stopOpenTunnel() }
    }

    /** Disconnect and delete every saved OpenTunnel file (and the tunnel on the server). */
    fun forgetOpenTunnel() {
        lifecycleScope.launch(Dispatchers.IO) {
            synchronized(openTunnelLock) {
                openTunnelBridge?.stop()
                openTunnelBridge = null
            }
            OpenTunnelController.forget(this@StreamingService)
            toastOnMain("Disconnecting OpenTunnel and deleting its files…")
        }
    }

    private fun startOpenTunnel(createIfNeeded: Boolean) {
        if (!OpenTunnelConfig.isEnabled(this)) return
        val problem = OpenTunnelConfig.credentialsProblem(this)
        if (problem != null) {
            toastOnMain(problem)
            return
        }
        if (!OpenTunnelController.isAvailable) {
            toastOnMain(OpenTunnelController.unavailableReason ?: "OpenTunnel is unavailable on this build")
            return
        }

        val status = OpenTunnelController.status(this)
        if (!createIfNeeded && !status.registered) {
            toastOnMain("Register OpenTunnel first")
            return
        }
        // Already connected, or a registration/connection is already in progress.
        if (status.connected || status.busy) return

        val target = openTunnelTarget() ?: return
        if (!OpenTunnelController.start(this, target)) {
            synchronized(openTunnelLock) {
                openTunnelBridge?.stop()
                openTunnelBridge = null
            }
            val failure = OpenTunnelController.status(this).error
            toastOnMain(failure ?: "Could not start OpenTunnel")
        }
    }

    /** Runs on a background dispatcher. Reconnects only when already registered. */
    private fun autoConnectOpenTunnel() {
        if (!OpenTunnelConfig.isEnabled(this)) return
        if (!OpenTunnelController.isAvailable) return
        if (OpenTunnelConfig.credentialsProblem(this) != null) return
        val status = OpenTunnelController.status(this)
        if (!status.registered || status.connected || status.busy) return
        startOpenTunnel(createIfNeeded = false)
    }

    /**
     * Builds the route target for the local web server. OpenTunnel terminates
     * the public TLS connection and forwards plain TCP, so an HTTPS local
     * server needs the loopback TLS bridge.
     */
    private fun openTunnelTarget(): String? {
        synchronized(openTunnelLock) {
            openTunnelBridge?.stop()
            openTunnelBridge = null

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val port = prefs.getString("server_port", null)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4444
            val host = ListenInterface.specificIpv4(this) ?: "127.0.0.1"
            if (!TlsHelper.isEnabled(this)) return formatHostPort(host, port)

            val configuredVersion = TlsHelper.tlsVersion(this)
            val actualVersion = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && configuredVersion != "1.2") {
                // TlsHelper falls back to TLS 1.2 on Android 9 and older.
                "1.2"
            } else {
                configuredVersion
            }
            return try {
                val bridge = LocalTlsBridge(host, port, actualVersion) { message ->
                    Log.i(TAG, "OpenTunnel: $message")
                }
                bridge.start()
                openTunnelBridge = bridge
                "127.0.0.1:${bridge.port}"
            } catch (e: Exception) {
                Log.e(TAG, "OpenTunnel local TLS bridge failed", e)
                toastOnMain("OpenTunnel could not reach the local HTTPS server: ${e.message}")
                null
            }
        }
    }

    private fun formatHostPort(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    private fun stopOpenTunnel() {
        OpenTunnelController.disconnect()
        synchronized(openTunnelLock) {
            openTunnelBridge?.stop()
            openTunnelBridge = null
        }
    }

    private fun toastOnMain(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                actualStreamSize = { actualStreamSize()?.let { it.width to it.height } },
                // Never let an /info.json request open Camera1 behind a running session: on a
                // single-HAL device that evicts the stream with Camera1 error 2. This also covers
                // always-on sessions and RTSP viewers, which the HTTP server cannot see.
                cameraBusy = { cameraMutex.isLocked || captureRunning },
                // /control/stop must also drop RTSP viewers, which the HTTP server does not own.
                onStreamingEnabledChanged = { enabled -> if (!enabled) rtspServer?.disconnectAll() }
            )
            // Initialize encoders with the streaming server helper
            h264StreamingEncoder = H264StreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "H264: $it"); onLog?.invoke(it) }
                .also { it.overlayRenderer = osdRenderer }
            mjpegStreamingEncoder = MjpegStreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "MJPEG: $it"); onLog?.invoke(it) }
                .also { it.overlayRenderer = osdRenderer }
            motionMonitor = MotionMonitor(this) { active ->
                Log.i(TAG, "Motion ${if (active) "started" else "ended"}")
                // Suppress the start event when notifications are off; always publish the end so a
                // client that saw an earlier motion-start is not left stuck on.
                if (!active || motionMonitor?.notifyOnMotion == true) {
                    onvifServer?.setMotion(active)
                }
                handleMotionRecording(active)
                handleAutoFlashMotion(active)
            }
            // Drop a stored motion/always profile that the current camera mode cannot run.
            sanitizeTorchMode()
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
        }
        streamingServerHelper?.startStreamingServer()
        startRtspServerIfEnabled()
        startOnvifIfEnabled()
        // An always-on recording mode keeps the camera running with no viewer, which also gives
        // Camera AE continuous telemetry. On demand starts the camera only for a real consumer.
        // Continuous mode also starts the segmented recorder; its first start brings the camera up itself.
        if (cameraAlwaysOn()) {
            acquireStreamingWakeLock()
            if (motionMonitor?.continuousRecording == true) {
                startContinuousRecording()
            } else {
                launchMain { startCameraIfNeeded() }
            }
        }
        // Reconnect a registered tunnel whenever the app/server starts again.
        lifecycleScope.launch(Dispatchers.IO) { autoConnectOpenTunnel() }
    }

    /** Starts (or stops) the RTSP server to match the RTSP setting. ONVIF is started separately:
     *  it also runs while an always-on recording mode is selected. */
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

    /** ONVIF runs when RTSP is enabled or any always-on recording mode is selected. */
    private fun onvifEnabled(): Boolean =
        MotionMonitor.isEnabled(this) ||
            PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(RtspServer.PREF_ENABLED, false)

    /** Starts the ONVIF service when RTSP is enabled or an always-on recording mode needs it.
     *  It never keeps the camera open by itself: video viewers (RTSP/HTTP) and motion detection
     *  do that on demand. */
    private fun startOnvifIfEnabled() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!onvifEnabled()) {
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
                // While streaming, report the negotiated frame size; before a viewer connects the
                // camera is idle, so fall back to the size the active capture path will request.
                val size = actualStreamSize() ?: desiredSize()
                val fps = (prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30).coerceIn(1, 60)
                val enc = StreamEncoding.encoderConfig(prefs, size.width, size.height, fps)
                val scheme = if (TlsHelper.isEnabled(this)) "rtsps" else "rtsp"
                OnvifStreamInfo(
                    rtspUrl = "$scheme://${getLocalIpAddress()}:$rtspPort${RtspServer.PATH}",
                    width = size.width, height = size.height, fps = fps,
                    bitrateKbps = enc.bitrateBps / 1000,
                    keyframeIntervalSec = enc.keyframeIntervalSec,
                    cbr = enc.bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
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
                if (!onvifEnabled()) {
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
    // A per-app stable ID is exactly what the ONVIF serial and the virtual MAC need; it never
    // leaves the device and is not used for advertising or analytics.
    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0000000000000000"

    private fun pseudoMac(): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(deviceId().toByteArray())
        h[0] = ((h[0].toInt() and 0xFC) or 0x02).toByte()   // locally administered, unicast
        return (0 until 6).joinToString(":") { String.format(Locale.US, "%02x", h[it]) }
    }

    /** Keeps the CPU and camera pipeline running while a viewer is connected. */
    // The lock is intentionally held for the whole streaming session (not a fixed timeout):
    // releaseStreamingWakeLock() drops it when the last viewer leaves or the service stops.
    @SuppressLint("WakelockTimeout")
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
        // Let a GPS/LUX/Schedule dark decision start the torch as soon as a viewer arrives. Camera
        // AE still waits for its normal darkness dwell; the fast poll handles that.
        lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
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
            if (captureRunning) {
                val next = cameraDemand()
                val session = runningSession
                // A second viewer of a kind already being served must not rebuild the encoder: adding it
                // to the existing broadcast is enough. This keeps other viewers from freezing for a second.
                val sameKindAlreadyServed = session != null &&
                    ((session.demand.h264Viewers && next.h264Viewers) ||
                        (session.demand.mjpegViewers && next.mjpegViewers))
                if (sameKindAlreadyServed && session.serves(next, streamFps(), camId())) {
                    Log.i(TAG, "client connected: running session already serves this viewer; no restart")
                    updateSubStream()
                    h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
                    return@launchMain
                }
                scheduleViewerCheck()
            } else {
                startCamera(viewersChanged = true)
                // The camera is running now; apply a pending dark/torch decision without waiting
                // for the next poll.
                lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
            }
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
                // Reload/idle: keep the LED lit across the session gap while the camera closes.
                bridgeTorchAcrossIdle()
                if (currentSurfaceProvider == null) {
                    // Let a quick reload reconnect first, then fully release the camera. Camera AE
                    // metering and automatic night mode are not consumers, so they must not keep
                    // an On demand camera warm after the last viewer leaves.
                    pendingStartJob?.cancel()
                    pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(ON_DEMAND_IDLE_RELEASE_DELAY_MS)
                        // Past its delay a start is not replaced any more: it may be opening or closing a Camera1 device
                        // on another thread. A newer request runs after it on cameraMutex instead.
                        if (pendingStartJob === coroutineContext[kotlinx.coroutines.Job]) pendingStartJob = null
                        // A viewer leaving during a Camera1 open releases the session that open ends with.
                        awaitCameraSettled()
                        stopCameraWhenIdle()
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

    /**
     * Camera-name line in the OSD. A user-set name wins; otherwise use the selected lens
     * (e.g. "Back 3") so the burned-in text identifies the source without extra configuration.
     */
    private fun currentOsdCameraName(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val custom = CameraOsd.sanitizeName(prefs.getString(CameraOsd.PREF_CAMERA_NAME, "").orEmpty())
        if (custom.isNotEmpty()) return custom
        val facing = if (frontFacing) "Front" else "Back"
        val id = camId()
        val physical = id.substringAfter(':', "")
        return if (physical.isNotBlank()) "$facing $physical" else facing
    }

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

    /** Encoder frame for a surface path: [want] turned by the manual rotate= control, then cropped. */
    private fun surfaceEncoderSize(want: Size): Size {
        val (w, h) = CameraGlPipe.frameSize(want.width, want.height, quantizedRotation(readRotatePref()))
        val crop = readCropPref()
        if (crop != null) {
            val bounds = crop.outputSize(w, h)
            return Size(bounds.width, bounds.height)
        }
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
        val quality = prefs.getString("camera_resolution", "low") ?: "low"
        // Keep the selected aspect ratio when the encoder cannot take the full size; capping
        // width and height independently would turn e.g. 1080x1920 into a square frame.
        val (w, h) = StreamSizing.effectiveSize(streamRes, quality, caps)
        // A lens can list a size for one output path but not the one the stream actually uses.
        // Negotiate against the active path so e.g. a front camera that only exposes YUV up to
        // 720p does not silently run at 720p while the UI claims 1080p.
        val characteristics = selectedCameraCharacteristics()
        val path = CameraStreamSizing.outputPath(
            front = frontFacing,
            hardwareLevel = characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            captureApi = prefs.getString("capture_api", "auto"),
        )
        val supported = characteristics?.let { CameraStreamSizing.outputSizes(it, path) }
            ?.filter { it.first <= caps.maxW && it.second <= caps.maxH }
        val (sw, sh) = if (supported != null) StreamSizing.negotiateSize(w, h, supported) else w to h
        return Size(sw, sh)
    }

    /** The characteristics of the camera the stream is currently configured for, if it can be resolved. */
    private fun selectedCameraCharacteristics(): CameraCharacteristics? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
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
            ?: cm.cameraIdList.firstOrNull()
        id?.let { cm.getCameraCharacteristics(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * The frame size the running encoder is actually producing, or null while the camera is idle.
     * Used by `/info.json` and ONVIF so status reflects the negotiated stream rather than only the
     * resolution that was requested.
     */
    private fun actualStreamSize(): Size? {
        val encoder = h264StreamingEncoder?.h264HardwareEncoder
        if (encoder != null && !encoder.isReleased) return Size(encoder.width, encoder.height)
        val runningBackend = backend
        if (!captureRunning || runningBackend == null) return null
        return Size(runningBackend.width, runningBackend.height)
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
            if (hasActiveClients() || currentSurfaceProvider != null ||
                snapshotInProgress || recordingNeeded || cameraHeldForTorch
            ) {
                false
            } else {
                stopCamera()
                true
            }
        }
        if (stopped) {
            Camera1Capture.awaitReleased(CAMERA1_RELEASE_WAIT_MS)
            resetOnDemandNightAutomation()
        }
        return stopped
    }

    /**
     * On demand releases the camera when the last consumer leaves. The automatic torch belongs to
     * that camera session, so turn it off here and forget a Camera AE decision; the next viewer
     * starts in day video and re-detects. Camera always on never calls this because its camera is
     * not released.
     */
    private fun resetOnDemandNightAutomation() {
        if (!onDemandMode()) return
        stopAlwaysTorch()
        val mode = AutoNightMode.mode(this)
        val cameraAe = AutoNightMode.modeIncludesCameraAe(mode)
        if (cameraAe) {
            CameraLightMeter.reset()
            autoNightScheduleOn = null
        }
        // Camera AE always re-detects on the next session. The dark torch also forces normal video,
        // so a stored night-still state must not delay the next viewer's torch-lit stream.
        val darkTorch = mode != AutoNightMode.MODE_OFF &&
            NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS
        if (cameraAe || darkTorch) {
            val hadOverride = NightMode.currentNightSightOverride(this) != null
            NightMode.clearNightSightOverride()
            if (nightModeSelected || hadOverride) {
                Log.i(TAG, "On demand camera released; night mode reset for the next day-mode start")
                setNightModeSelected(false)
            }
        }
    }

    /** What the viewers, recording, motion detection, a snapshot and the on-phone preview ask of the camera now. */
    private fun cameraDemand() = CameraDemand(
        h264Viewers = h264StreamingEncoder?.hasClients() == true,
        mjpegViewers = mjpegStreamingEncoder?.hasClients() == true,
        recording = recordingNeeded || localRecorder?.isRecording == true,
        motion = motionDetectionEnabled(),
        keepAlive = cameraAlwaysOn(),
        snapshot = snapshotInProgress,
        preview = currentSurfaceProvider != null,
        lightMeter = cameraAeSelected(),
    )

    /**
     * Night stills replace the video frames, so they are only captured while a viewer, local
     * recording, motion detection or a snapshot actually consumes frames. On demand with only the
     * on-phone preview attached, and Camera always on/Camera AE with no viewer, use the normal video
     * pipeline instead: the still loop would capture full-resolution photos that go nowhere.
     */
    private fun nightStillsNeeded(): Boolean =
        camId() != nightVideoFallbackCamera &&
            (encoders.any { it.hasClients() } ||
                localRecorder?.isRecording == true ||
                recordingNeeded ||
                motionDetectionEnabled() ||
                snapshotInProgress)

    private fun streamFps(): Int =
        (PreferenceManager.getDefaultSharedPreferences(this).getString("stream_fps", "30")?.toIntOrNull() ?: 30).coerceIn(1, 60)

    private fun parseClockMinutes(value: String): Int? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

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
        if (!force && !hasActiveClients() && currentSurfaceProvider == null) {
            Log.i(TAG, "startCamera bypassed: no force, no clients, and preview surface is null")
            return@withLock
        }
        // Night mode is a still-photo loop, not a video session. It owns the camera only while
        // something actually consumes frames; a camera kept on for the on-phone preview or an
        // always-on recording mode stays on the normal video pipeline, which avoids full-resolution
        // stills that nobody receives and still provides Camera AE telemetry.
        if (nightModeEnabled) {
            if (!hasActiveClients() && currentSurfaceProvider == null) {
                stopCamera()
                resetOnDemandNightAutomation()
                return@withLock
            }
            if (nightStillsNeeded()) {
                val wantedSize = desiredSize()
                val wantedCamera = camId()
                val wantedManual = NightMode.manualExposure(this, wantedCamera)
                val wantedExposure = wantedManual?.let { NightMode.currentExposureMs(this, wantedCamera, it) }
                val wantedIso = wantedManual?.let { NightMode.currentIso(this, wantedCamera, it) }
                if (nightCaptureReady && nightCapture != null &&
                    nightCaptureCamera == wantedCamera && nightCaptureSize == wantedSize &&
                    nightCaptureExposureMs == wantedExposure && nightCaptureIso == wantedIso
                ) {
                    h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()
                    return@withLock
                }
                cameraHeldForTorch = false
                stopCamera()
                startNightModeCamera()
                return@withLock
            }
            // Nothing consumes night stills right now: fall through to a normal video session so
            // an always-on camera or the on-phone preview keeps its frames, and Camera AE can
            // still sample telemetry while the camera runs.
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
                    prefs.edit { putString(PREF_CAMERA_ID, camId()) }
                }
            }
            val demand = cameraDemand()
            val surfaceEncoder = supportsSurfaceEncoder()
            val fps = streamFps()
            val camera = camId()
            val cameraXMissed = cameraXMissedRecently(prefs, camera)
            val api = chooseApi()
            // The on-phone preview is only wired to CameraX, so the idle/preview-only state is
            // ALWAYS CameraX — its Preview use case renders even on devices whose Camera2 session
            // never configures ImageAnalysis/ImageCapture. The backend choice only matters when
            // frames are actually needed (clients / recording / snapshot in flight): then honor the
            // API preference and a recent CameraX start without frames, which route to the Camera1 backend.
            val useCamera1 = demand.frames && (api == "camera1" || cameraXMissed)
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
                    } else if (demand.h264 && surfaceEncoder && !waitForCameraXSurfaceOutput() && cameraDemand().h264) {
                        Log.w(TAG, "CameraX H.264 surface produced no frames; ${if (api == "auto") "falling back to Camera1" else "switching H.264 to software YUV"}")
                        if (api == "auto") {
                            recordCameraXFrames(prefs, camera, false)
                            stopCamera()
                            startCamera1Backend(want, demand)
                        } else {
                            // The user explicitly chose CameraX; keep streaming through the YUV path.
                            cameraXGlPipe?.stop()
                            cameraXGlPipe = null
                            h264StreamingEncoder?.setEncoder(null)
                        }
                    }
                }
            }
            // Persist the active camera id so encoders and helpers can read per-camera prefs
            try {
                prefs.edit { putString(PREF_CAMERA_ID, camId()) }
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

    /**
     * Waits for the first encoded frame from the CameraX GL surface. Some HALs hand the surface
     * back to CameraX shortly after it is provided, leaving a surface-mode encoder with no input;
     * analysis frames still arrive, so [waitForCameraXFrame] cannot see that. Returns true when
     * there is no surface encoder to check (the YUV path already carries H.264).
     */
    private suspend fun waitForCameraXSurfaceOutput(): Boolean {
        val enc = h264StreamingEncoder?.h264HardwareEncoder ?: return true
        if (!enc.useSurface) return true
        var waited = 0L
        while (enc.lastOutputAtMs == 0L && waited < CAMERAX_SURFACE_GRACE_MS) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        return enc.lastOutputAtMs > 0L
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
            if (prefs.contains(missesKey)) prefs.edit { remove(missesKey); remove(missedAtKey) }
            return
        }
        val misses = prefs.getInt(missesKey, 0) + 1
        prefs.edit {
            putInt(missesKey, misses)
            putLong(missedAtKey, System.currentTimeMillis())
        }
        Log.w(TAG, "CameraX bound without frames on camera $camera ($misses in a row), frame-needing starts use Camera1 for the next ${CameraXFallback.holdMs(misses) / 60_000} min")
    }

    /** Ends the Camera1 hold on every camera, so the next frame-needing start tests CameraX again. */
    private fun forgetCameraXMisses(prefs: SharedPreferences) {
        prefs.edit {
            remove(CameraXFallback.LEGACY_PREF)
            prefs.all.keys.filter { CameraXFallback.isRecordKey(it) }.forEach { remove(it) }
        }
    }

    private suspend fun startCamera1Backend(want: Size, demand: CameraDemand) {
        // Camera1 → GL pipe → surface encoder (true 1920×1080 on legacy HALs). Opening the camera and
        // starting its preview block for seconds on some HALs (24 s seen), so both run on the Camera1 thread.
        val selectedId = selectedCameraId
        val front = frontFacing
        val cap = Camera1Capture.open(
            { camera1IndexForSelectedOrFacing(selectedId, front) },
            want.width,
            want.height,
            onError = { error ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastCamera1ErrorRestartAt > 5_000) {
                    lastCamera1ErrorRestartAt = now
                    Log.w(TAG, "Camera1 error $error; restarting the camera")
                    launchMain {
                        kotlinx.coroutines.delay(1_000)
                        debouncedStartCamera(force = true)
                    }
                }
            },
        )
        // Read on every frame, so an MJPEG viewer joining a session started for motion detection is fed too.
        // Both skip frames they have no use for (no MJPEG viewer, motion detection off).
        val onFrame = { nv21: ByteArray ->
            CameraLightMeter.reportNv21Luma(nv21, cap.chosenW, cap.chosenH)
            mjpegStreamingEncoder?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
            motionMonitor?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
            Unit
        }.takeIf { demand.nv21 }
        try {
            val pipe = newPipe(Size(cap.chosenW, cap.chosenH))
            cap.start(pipe.surfaceTexture, streamFps(), onFrame = onFrame)
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
            fun newEncoder(size: Size): H264HardwareEncoder {
                val encConfig = StreamEncoding.encoderConfig(prefs, size.width, size.height, fpsCoerced)
                return H264HardwareEncoder(
                    size.width,
                    size.height,
                    fpsCoerced,
                    encConfig.bitrateBps,
                    true, // useSurface = true
                    encConfig.keyframeIntervalSec,
                    encConfig.bitrateMode,
                ) { d, k, pts -> h264.broadcastH264(d, k, pts) }
            }

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
                    it.denoise = readDenoisePref()
                    it.grayscale = readGrayscalePref()
                    it.crop = readCropPref()
                    it.overlayRenderer = osdRenderer
                    it.start()
                    cameraXGlPipe = it
                }

                h264SurfaceProvider = Preview.SurfaceProvider { request ->
                    val surface = pipe.inputSurface
                    if (surface.isValid) {
                        // Render at the resolution CameraX negotiated rather than the pipe's
                        // placeholder buffer size, so the selected resolution is what gets captured.
                        pipe.setCameraBufferSize(request.resolution.width, request.resolution.height)
                        Log.i(TAG, "Providing CameraX H.264 surface via GL pipe (${request.resolution}, mirror=${pipe.mirror})")
                        // CameraX only gets the pipe's surface. The encoder's own surface stays with the
                        // pipe, so stopCamera() can release the encoder as soon as the pipe has stopped.
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
            this, this, frontFacing, selectedCameraId, want, streamFps(),
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

    /**
     * Keep night mode in step with civil twilight while automatic night mode is enabled. The
     * position is cached by [AutoNightMode], so most ticks are only the cheap solar calculation.
     */
    private fun startAutoNightModeLoop() {
        if (autoNightModeJob != null) return
        autoNightModeJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    evaluateAutoNightMode()
                } catch (e: Exception) {
                    Log.w(TAG, "Auto night mode check failed: ${e.message}")
                }
                val mode = AutoNightMode.mode(this@StreamingService)
                delay(
                    if (AutoNightMode.modeIncludesCameraAe(mode) ||
                        (onDemandMode() &&
                            NightMode.torchMode(this@StreamingService) == NightMode.TORCH_MODE_ALWAYS)
                    ) {
                        AUTO_NIGHT_FAST_POLL_MS
                    } else {
                        AUTO_NIGHT_POLL_MS
                    }
                )
            }
        }
    }

    /**
     * Battery-temperature safety guard, on by default at [OverheatProtection.DEFAULT_C] degrees.
     * Uses the same streaming gate as `/control/stop`, so the camera stops once the last viewer is
     * dropped. Streaming resumes when the battery cools by
     * [OverheatProtection.RESUME_HYSTERESIS_C] degrees, or immediately when the threshold is set
     * to [OverheatProtection.DISABLED].
     */
    private fun startOverheatMonitor() {
        if (overheatJob != null) return
        overheatJob = lifecycleScope.launch(Dispatchers.IO) {
            var autoPaused = false
            while (isActive) {
                delay(OVERHEAT_POLL_MS)
                try {
                    val threshold = OverheatProtection.stored(this@StreamingService)
                    if (threshold == OverheatProtection.DISABLED) {
                        if (autoPaused) {
                            autoPaused = false
                            streamingServerHelper?.setStreamingEnabled(true)
                            onLog?.invoke("Overheat protection disabled; streaming resumed")
                        }
                        continue
                    }
                    val temperature = DeviceStatus.temperatureC(this@StreamingService) ?: continue
                    if (!autoPaused && temperature >= threshold) {
                        if (streamingServerHelper?.isStreamingEnabled() == true) {
                            autoPaused = true
                            overheatPaused = true
                            streamingServerHelper?.setStreamingEnabled(false)
                            stopAlwaysTorch()
                            endAutoFlashCycle()
                            onLog?.invoke(
                                "Overheat protection: battery " +
                                    "${String.format(Locale.US, "%.1f", temperature)}°C >= ${threshold}°C; streaming paused"
                            )
                        }
                    } else if (autoPaused &&
                        temperature <= threshold - OverheatProtection.RESUME_HYSTERESIS_C
                    ) {
                        autoPaused = false
                        overheatPaused = false
                        streamingServerHelper?.setStreamingEnabled(true)
                        onLog?.invoke(
                            "Overheat protection: battery cooled to " +
                                "${String.format(Locale.US, "%.1f", temperature)}°C; streaming resumed"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Overheat check failed: ${e.message}")
                }
            }
        }
    }

    private fun evaluateAutoNightMode() {
        val mode = AutoNightMode.mode(this)
        val onDemand = onDemandMode()
        if (mode == AutoNightMode.MODE_OFF) {
            autoNightScheduleOn = null
            AutoNightMode.setCurrentDark(null)
            applyGrayscaleState()
            stopAlwaysTorch()
            return
        }
        if (autoFlashCycleActive) return
        // The automatic torch belongs to the On demand camera session. If that camera has stopped,
        // drop the torch before the next decision so it cannot outlive its viewers.
        if (onDemand && !onDemandCameraInUse() && alwaysTorchActive) stopAlwaysTorch()
        // Camera AE needs live camera telemetry. On demand releases the camera when idle, so wait
        // for a viewer, snapshot, recording or the on-phone preview to start it. Camera always on
        // keeps the camera running and is evaluated continuously, as before.
        if (mode == AutoNightMode.MODE_CAMERA_AE && onDemand && !onDemandCameraInUse()) {
            autoNightScheduleOn = null
            AutoNightMode.setCurrentDark(null)
            applyGrayscaleState()
            return
        }
        val camera = camId()
        if (!NightMode.supports(this, camera)) {
            autoNightScheduleOn = null
            AutoNightMode.setCurrentDark(null)
            applyGrayscaleState()
            return
        }

        // Camera AE and LUX read the scene the torch itself lights. While a torch-lit On demand
        // session is running, keep that session's darkness decision instead of letting the lit
        // scene immediately turn the torch back off. The idle reset clears it for the next viewer.
        if (onDemand && onDemandCameraInUse() && alwaysTorchActive &&
            NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS && triggerLatchesTorch(mode)
        ) {
            if (overheatPaused) {
                stopAlwaysTorch()
                return
            }
            autoNightScheduleOn = true
            AutoNightMode.setCurrentDark(true)
            applyGrayscaleState()
            startAlwaysTorch()
            return
        }

        val gpsNight = if (AutoNightMode.modeIncludesGps(mode)) {
            val location = AutoNightMode.resolveLocation(this)
            if (location == null) {
                Log.d(TAG, "Auto night mode ($mode) is waiting for a location fix")
                null
            } else {
                AutoNightMode.isNight(
                    System.currentTimeMillis(),
                    location.latitude,
                    location.longitude,
                    AutoNightMode.offsetMinutes(this),
                )
            }
        } else {
            null
        }

        val luxNight = if (AutoNightMode.modeIncludesLux(mode)) {
            val lux = AutoNightMode.readLux(this)
            if (lux == null) {
                Log.d(TAG, "Auto night mode ($mode) is waiting for a light sensor reading")
                null
            } else {
                lux < AutoNightMode.luxThreshold(this)
            }
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val shouldBeOn = when (mode) {
            AutoNightMode.MODE_SCHEDULE -> AutoNightMode.scheduleNight(
                now,
                AutoNightMode.scheduleStartMinutes(this),
                AutoNightMode.scheduleEndMinutes(this),
            )
            AutoNightMode.MODE_CAMERA_AE -> CameraLightMeter.decide(
                AutoNightMode.cameraAeThreshold(this),
                now,
            )
            else -> AutoNightMode.shouldBeNight(mode, gpsNight, luxNight)
        } ?: run {
            autoNightScheduleOn = null
            AutoNightMode.setCurrentDark(null)
            applyGrayscaleState()
            return
        }
        // A decision can be computed just as the last On demand viewer leaves. Never re-arm night
        // mode from that stale decision: the idle reset below/after the stop must win.
        if (mode == AutoNightMode.MODE_CAMERA_AE && onDemand && !onDemandCameraInUse()) {
            autoNightScheduleOn = null
            AutoNightMode.setCurrentDark(null)
            applyGrayscaleState()
            return
        }
        autoNightScheduleOn = shouldBeOn
        AutoNightMode.setCurrentDark(shouldBeOn)
        applyGrayscaleState()
        if (overheatPaused) {
            stopAlwaysTorch()
            return
        }
        // Reconcile a day transition before releasing a torch suppression. Otherwise ending an
        // always/motion flash at dawn would briefly re-arm the night stills that auto switching is
        // about to turn off.
        if (!shouldBeOn && AutoNightMode.isSwitchingEnabled(this) && nightModeSelected) {
            setNightModeSelected(false)
        }
        // In On demand the torch only runs while the camera session is active. Camera always on
        // keeps its camera running, so the torch still follows the whole dark period there.
        val cameraActive = !onDemand || onDemandCameraInUse()
        if (NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS && shouldBeOn && cameraActive) {
            startAlwaysTorch()
        } else {
            stopAlwaysTorch()
        }
        // On demand with the dark torch selected: wait for a camera session instead of turning on
        // night stills first. The next viewer starts normal video and the torch lit by the loop.
        if (NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS && onDemand && shouldBeOn) return
        if (!AutoNightMode.isSwitchingEnabled(this)) return
        if (shouldBeOn == nightModeSelected) return
        Log.i(TAG, "Auto night mode ($mode): turning night mode ${if (shouldBeOn) "on" else "off"}")
        setNightModeSelected(shouldBeOn)
    }

    /**
     * Applies a night-mode selection from the web UI or an automatic trigger. The selection is
     * remembered even while a torch is lit; [applyNightModeActive] decides whether still capture
     * can run right now.
     */
    @Synchronized
    private fun setNightModeSelected(next: Boolean) {
        if (next && !NightMode.supports(this, camId())) {
            Log.w(TAG, "Ignoring night_mode=on: ${camId()} has no night-mode still pipeline")
            return
        }
        // On demand Camera AE always starts a new camera session in day video and re-detects.
        // In particular, an automatic decision computed just before the last viewer left must not
        // re-arm night stills while the camera is idle.
        if (next && cameraAeSelected() && MotionMonitor.modeFor(this) == MotionMonitor.MODE_OFF &&
            !captureRunning
        ) {
            Log.i(TAG, "Camera AE night mode stays off while the On demand camera is idle")
            return
        }
        if (nightModeSelected != next) {
            nightModeSelected = next
            NightMode.setEnabled(this, next)
        }
        applyGrayscaleState()
        applyNightModeActive(NightMode.isActive(this))
    }

    /** Starts or stops the still-photo session for the current [nightModeEnabled] state. */
    @Synchronized
    private fun applyNightModeActive(next: Boolean) {
        if (nightModeEnabled == next) return
        nightModeEnabled = next
        // Automatic changes can happen with nobody watching. Do not wake the camera just to hold a
        // night session open; the next viewer starts the mode that is stored now. A connected
        // viewer, the on-phone preview, motion detection or a recording still restart immediately.
        val cameraInDemand = hasActiveClients() || currentSurfaceProvider != null
        if (!cameraInDemand) return
        if (next) {
            debouncedStartCamera(force = true)
        } else {
            // Release the still session before rebinding video. Camera extension sessions can take
            // a moment to close, and starting CameraX immediately can freeze on the last night photo.
            launchMain {
                stopNightModeCamera()
                captureRunning = false
                delay(1000)
                if (!nightModeEnabled) startCamera(force = true)
            }
        }
    }

    /** Clear the stored selection after a capture failure or a reset. */
    private fun clearNightModeSelection() {
        nightModeSelected = false
        nightModeEnabled = false
        NightMode.setEnabled(this, false)
        NightMode.clearNightSightOverride()
        applyGrayscaleState()
    }

    /**
     * Recompute whether a torch is currently lit. Manual flash, the on-motion cycle and the
     * always-on night torch all suppress night-sight and grayscale until they turn off again.
     */
    private fun refreshFlashSuppression() {
        val manualTorch = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_CAMERA_TORCH, "off") == "on"
        setFlashSuppressed(manualTorch || alwaysTorchActive || autoFlashCycleActive)
    }

    @Synchronized
    private fun setFlashSuppressed(suppressed: Boolean) {
        if (NightMode.isFlashSuppressed() == suppressed) return
        NightMode.setFlashSuppressed(suppressed)
        applyGrayscaleState()
        applyNightModeActive(NightMode.isActive(this))
    }

    /**
     * Opens the still-only night session used in place of live video. Camera extensions can spend
     * several seconds merging a night photo, so capture runs on its own coroutine and each resulting
     * JPEG is fed to the active streaming encoder.
     */
    private fun startNightModeCamera() {
        val generation = ++nightModeGeneration
        nightModeJob?.cancel()
        nightModeJob = null
        nightCapture?.stop()
        nightCapture = null
        nightCaptureReady = false

        val camera = camId()
        val size = desiredSize()
        if (!NightMode.supports(this, camera)) {
            Log.w(TAG, "Night mode requested on $camera but this camera does not support it")
            clearNightModeSelection()
            launchMain { startCamera(force = true) }
            return
        }
        nightCaptureCamera = camera
        nightCaptureSize = size
        acquireStreamingWakeLock()

        val manual = NightMode.manualExposure(this, camera)
        val exposureMs = manual?.let { NightMode.currentExposureMs(this, camera, it) }
        val iso = manual?.let { NightMode.currentIso(this, camera, it) }
        nightCaptureExposureMs = exposureMs
        nightCaptureIso = iso
        val mode = NightMode.captureMode(
            getSystemService(Context.CAMERA_SERVICE) as CameraManager,
            camera,
        )
        val capture: NightStillCapture = if (mode == NightMode.CaptureMode.MANUAL && exposureMs != null && iso != null) {
            Camera2NightCapture(this, frontFacing, selectedCameraId, size, exposureMs, iso) { message ->
                Log.i(TAG, message)
                onLog?.invoke(message)
            }
        } else {
            NightModeCapture(this, this, frontFacing, selectedCameraId, size, exposureMs, iso) { message ->
                Log.i(TAG, message)
                onLog?.invoke(message)
            }
        }
        nightCapture = capture
        capture.start { ready ->
            if (generation != nightModeGeneration || !nightModeEnabled) {
                capture.stop()
                return@start
            }
            if (!ready) {
                Log.w(TAG, "Night mode capture could not start; restoring video")
                clearNightModeSelection()
                nightCapture = null
                nightCaptureReady = false
                launchMain { startCamera(force = true) }
                return@start
            }
            nightCaptureReady = true
            captureRunning = true
            runningSession = null
            runningStartedAtMs = SystemClock.elapsedRealtime()
            // The still session can drive the LED itself; setTorchMode would be CAMERA_IN_USE.
            // Re-assert a torch that was lit before this still session took the camera.
            val manualTorch = PreferenceManager.getDefaultSharedPreferences(this@StreamingService)
                .getString(PREF_CAMERA_TORCH, "off") == "on"
            if (alwaysTorchActive || autoFlashCycleActive || manualTorch) applyTorch(true)
            encoders.forEach { it.start() }
            startNightModeLoop(capture, generation)
        }
    }

    private fun startNightModeLoop(capture: NightStillCapture, generation: Int) {
        nightModeJob?.cancel()
        nightModeJob = lifecycleScope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive && nightModeEnabled && generation == nightModeGeneration) {
                if (!hasActiveClients() && currentSurfaceProvider == null) break
                val still = captureNightStill(capture, generation)
                // A capture can block for seconds. If night mode was switched off meanwhile, do not
                // push this stale still into the video stream after the normal camera restarted.
                if (!isActive || generation != nightModeGeneration) break
                if (still == null) {
                    if (!isActive || generation != nightModeGeneration) break
                    failures++
                    Log.w(TAG, "Night capture failed ($failures/$NIGHT_MODE_MAX_FAILURES)")
                    if (failures >= NIGHT_MODE_MAX_FAILURES) {
                        launchMain {
                            if (generation == nightModeGeneration) {
                                // Some legacy HALs hang on takePicture while preview runs, so the
                                // still loop delivers no frames at all. Keep the night profile
                                // selected (grayscale and Camera AE stay active) but stream normal
                                // video instead of freezing on a black frame forever.
                                val camera = nightCaptureCamera ?: camId()
                                nightVideoFallbackCamera = camera
                                Log.w(
                                    TAG,
                                    "Night stills are not working on $camera; " +
                                        "keeping the night profile on live video",
                                )
                                onLog?.invoke("Night stills unavailable; using live video with grayscale")
                                startCamera(force = true)
                            }
                        }
                        break
                    }
                    delay(NIGHT_MODE_RETRY_DELAY_MS)
                    continue
                }
                failures = 0
                if (!hasActiveClients() && currentSurfaceProvider == null) break
                val jpeg = still.jpeg
                // Night stills are the only frames available in this mode; motion detection (and
                // therefore motion-triggered flash/recording) has to run on them too.
                motionMonitor?.processJpegFrame(jpeg)
                val size = nightCaptureSize
                val maxW = size?.width ?: 0
                val maxH = size?.height ?: 0
                if (mjpegStreamingEncoder?.hasClients() == true) {
                    mjpegStreamingEncoder?.processJpegFrame(jpeg, maxW, maxH, still.rotationDegrees)
                }
                if (h264StreamingEncoder?.hasClients() == true) {
                    h264StreamingEncoder?.processStillJpeg(jpeg, maxW, maxH, still.rotationDegrees)
                }
                delay(NIGHT_MODE_FRAME_GAP_MS)
            }
        }
    }

    /** Requests one still from the night session and waits for it on the IO thread. */
    private suspend fun captureNightStill(capture: NightStillCapture, generation: Int): NightStill? {
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<NightStill>(1)
        launchMain {
            if (generation == nightModeGeneration && nightCapture === capture) {
                capture.capture {
                    out[0] = it
                    latch.countDown()
                }
            } else {
                latch.countDown()
            }
        }
        try {
            latch.await(16, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return out[0]
    }

    /** Blocking variant for a /video/snapshot request while the night still loop owns the camera. */
    private fun snapshotNightStill(): ByteArray? {
        val capture = nightCapture ?: return null
        val generation = nightModeGeneration
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<NightStill>(1)
        launchMain {
            if (generation == nightModeGeneration && nightCapture === capture) {
                capture.capture {
                    out[0] = it
                    latch.countDown()
                }
            } else {
                latch.countDown()
            }
        }
        try {
            latch.await(16, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return out[0]?.let {
            transformSnapshot(
                it.jpeg,
                readRotatePref(),
                readMirrorPref(),
                readCropPref(),
                it.rotationDegrees,
            )
        }
    }

    private fun stopNightModeCamera() {
        nightModeGeneration++
        nightModeJob?.cancel()
        nightModeJob = null
        nightCaptureReady = false
        nightCapture?.stop()
        nightCapture = null
        nightCaptureCamera = null
        nightCaptureSize = null
        nightCaptureExposureMs = null
        nightCaptureIso = null
    }

    private fun stopCamera() {
        // Single choke point for every on-demand release path (last viewer left, preview hidden,
        // stream reconfigured). The torch-off handler clears the flag before calling in, so it is
        // the one caller that can still close a torch-held session.
        if (cameraHeldForTorch) return
        stopNightModeCamera()
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
        // settings changed since it started, or its output thread died.
        val settings = listOf(shortSide, fps, kbps)
        if (subEncoder != null) {
            if (settings == subEncoderSettings && subEncoder?.drainFailed != true) return
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
        val phys = id.substringAfter(':', id)
        return when {
            prefs.contains("mirror_$id") -> prefs.getString("mirror_$id", null)?.toBoolean() ?: false
            prefs.contains("mirror_$phys") -> prefs.getString("mirror_$phys", null)?.toBoolean() ?: false
            else -> false
        }
    }

    /** Global spatial denoise strength (0..1) for the GL pipe; off when unset. */
    private fun readDenoisePref(): Float {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return NoiseReduction.strength(NoiseReduction.stored(prefs))
    }

    /** Grayscale Night Mode applies to the GL video pipe as well as the night still loop. */
    private fun readGrayscalePref(): Boolean = NightMode.effectiveGrayscale(this)

    /** Re-apply the effective grayscale state to live GL pipes after night mode or follow changes. */
    private fun applyGrayscaleState() {
        val effective = NightMode.effectiveGrayscale(this)
        glPipe?.grayscale = effective
        cameraXGlPipe?.grayscale = effective
    }

    /** Per-camera rotate= knob (token then physical id fallback). Consumers apply their own
     *  quantisation: the byte-buffer H.264 encoder snaps to 90° (YUV rotate cost) and the GL pipe
     *  only acts on 90° multiples. Returns the raw stored angle. */
    private fun readRotatePref(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId()
        val phys = id.substringAfter(':', id)
        return when {
            prefs.contains("camera_rotate_$id") -> prefs.getInt("camera_rotate_$id", 0)
            prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
            else -> 0
        }
    }

    /** Per-camera crop rectangle in displayed (rotated) frame coordinates, or null for full frame. */
    private fun readCropPref(): CropRegion? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return CropRegion.read(prefs, camId())
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
        val encConfig = StreamEncoding.encoderConfig(prefs, frame.width, frame.height, fpsCoerced)
        val enc = H264HardwareEncoder(
            frame.width,
            frame.height,
            fpsCoerced,
            encConfig.bitrateBps,
            true,
            encConfig.keyframeIntervalSec,
            encConfig.bitrateMode,
        ) { d, k, pts -> h264StreamingEncoder?.broadcastH264(d, k, pts) }
        h264StreamingEncoder?.setEncoder(enc)
        streamingServerHelper?.resetH264Wait()
        return CameraGlPipe(enc.inputSurface!!, frame.width, frame.height, fpsCoerced).also {
            it.mirror = readMirrorPref()
            it.rotation = quantizedRotation(readRotatePref())
            it.denoise = readDenoisePref()
            it.grayscale = readGrayscalePref()
            it.crop = readCropPref()
            it.overlayRenderer = osdRenderer
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
     * Applies the torch through whichever camera path currently owns the device. While night
     * stills run there is no streaming backend, and [CameraManager.setTorchMode] is refused with
     * CAMERA_IN_USE, so the still session drives the LED itself. A stale (stopped) backend must
     * not masquerade as the owner either — that is why [captureRunning] gates the backend path.
     */
    private fun applyTorch(on: Boolean) {
        val capture = nightCapture
        if (nightModeEnabled && nightCaptureReady && capture != null && capture.setTorch(on)) {
            // The session owns the LED now; drop any lingering device-channel claim.
            if (deviceTorchOn) setDeviceTorch(false)
            return
        }
        val active = backend?.takeIf { captureRunning }
        if (active != null && active.hasFlashUnit) active.setTorch(on)
        if (on) {
            if (active?.hasFlashUnit != true) setDeviceTorch(true)
        } else {
            setDeviceTorch(false)
        }
    }

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
                // Night mode has no video backend; grab one of its stills directly instead of
                // trying to rebind a normal camera session behind the still loop.
                if (nightModeEnabled && nightCaptureReady) {
                    snapshotNightStill()?.let { return it }
                }
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
            result = out[0]?.let { transformSnapshot(it, readRotatePref(), readMirrorPref(), readCropPref()) }
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
     * Turn a still's pixels by the manual rotate=/mirror settings, then crop it to the selected
     * region. EXIF is not touched, so the image carries no orientation tag telling a viewer to
     * rotate it on top of the manual angle.
     */
    private fun transformSnapshot(
        jpeg: ByteArray,
        degrees: Int,
        mirror: Boolean,
        crop: CropRegion?,
        sensorRotationDegrees: Int = 0,
    ): ByteArray {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0 && !mirror && crop == null && sensorRotationDegrees == 0) return jpeg
        return try {
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val allocated = LinkedHashSet<Bitmap>()
            allocated.add(decoded)
            var current = NightStillTransform.toSensorSpace(decoded, sensorRotationDegrees)
            if (current !== decoded) allocated.add(current)
            try {
                if (angle != 0 || mirror) {
                    val matrix = Matrix().apply {
                        if (mirror) {
                            postScale(-1f, 1f)
                            postTranslate(current.width.toFloat(), 0f)
                        }
                        if (angle != 0) postRotate(angle.toFloat())
                    }
                    val transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                    if (transformed !== current) {
                        allocated.add(transformed)
                        current = transformed
                    }
                }
                if (crop != null) {
                    val bounds = crop.pixelBounds(current.width, current.height)
                    if (bounds.width < current.width || bounds.height < current.height) {
                        val cropped = Bitmap.createBitmap(current, bounds.left, bounds.top, bounds.width, bounds.height)
                        if (cropped !== current) {
                            allocated.add(cropped)
                            current = cropped
                        }
                    }
                }
                if (current === decoded) return jpeg
                val out = ByteArrayOutputStream(jpeg.size)
                try {
                    current.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.toByteArray()
                } finally { out.close() }
            } finally {
                allocated.forEach { bmp -> try { bmp.recycle() } catch (_: Exception) {} }
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "snapshot transform OOM")
            try { System.gc() } catch (_: Exception) {}
            jpeg
        } catch (e: Exception) {
            Log.e(TAG, "snapshot transform: ${e.message}")
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
        val physicalId = id.substringAfter(':', id)
        when (key) {
            "night_mode" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> return
                }
                NightMode.clearNightSightOverride()
                setNightModeSelected(next)
                recheckAutoNightAfterManual()
            }
            // Temporary Night-sight choice while Auto Night-sight mode owns the switch. It expires
            // automatically on the next automatic night/day transition.
            "night_mode_override" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    "clear", "auto", "none" -> null
                    else -> return
                }
                val autoOwns = AutoNightMode.isSwitchingEnabled(this) &&
                    AutoNightMode.isEnabled(this) &&
                    AutoNightMode.currentDark() != null
                if (autoOwns) {
                    NightMode.setNightSightOverride(next)
                    applyGrayscaleState()
                    applyNightModeActive(NightMode.isActive(this))
                } else {
                    NightMode.clearNightSightOverride()
                    if (next != null) {
                        setNightModeSelected(next)
                        recheckAutoNightAfterManual()
                    } else {
                        applyNightModeActive(NightMode.isActive(this))
                    }
                }
                Log.i(TAG, "Night-sight override=${next ?: "auto"}")
            }
            "night_exposure" -> {
                val camera = camId()
                val manual = NightMode.manualExposure(this, camera) ?: return
                val ms = value.trim().toFloatOrNull()?.toLong()
                    ?.coerceIn(manual.minExposureMs, manual.maxExposureMs)
                    ?: return
                NightMode.setExposureMs(this, camera, ms)
                if (nightModeEnabled) debouncedStartCamera(force = true)
            }
            "night_iso" -> {
                val camera = camId()
                val manual = NightMode.manualExposure(this, camera) ?: return
                val iso = value.trim().toIntOrNull()
                    ?.coerceIn(manual.minIso, manual.maxIso)
                    ?: return
                NightMode.setIso(this, camera, iso)
                if (nightModeEnabled) debouncedStartCamera(force = true)
            }
            "auto_night_mode" -> {
                var mode = AutoNightMode.normalizeMode(value)
                if (mode != AutoNightMode.MODE_OFF) {
                    if (AutoNightMode.modeIncludesGps(mode) && !AutoNightMode.hasLocationPermission(this)) {
                        Log.w(TAG, "Ignoring auto_night_mode=$mode: location permission is not granted")
                        mode = AutoNightMode.MODE_OFF
                    } else if (mode == AutoNightMode.MODE_LUX && !AutoNightMode.hasLightSensor(this)) {
                        Log.w(TAG, "Ignoring auto_night_mode=$mode: no ambient light sensor")
                        mode = AutoNightMode.MODE_OFF
                    } else if (mode == AutoNightMode.MODE_CAMERA_AE &&
                        NightMode.torchMode(this) == NightMode.TORCH_MODE_ALWAYS &&
                        !onDemandMode()
                    ) {
                        Log.w(TAG, "Ignoring auto_night_mode=camera_ae: always-on torch would blind the meter")
                        return
                    } else if (!NightMode.supports(this, camId())) {
                        Log.w(TAG, "Ignoring auto_night_mode=$mode: ${camId()} has no night-mode still pipeline")
                        mode = AutoNightMode.MODE_OFF
                    }
                }
                // A different trigger is a new automation context; drop any temporary grayscale
                // override from the previous mode.
                NightMode.clearGrayscaleOverride()
                NightMode.clearNightSightOverride()
                AutoNightMode.setMode(this, mode)
                applyNightModeActive(NightMode.isActive(this))
                if (mode == AutoNightMode.MODE_OFF) {
                    autoNightScheduleOn = null
                    AutoNightMode.setCurrentDark(null)
                    applyGrayscaleState()
                    endAutoFlashCycle()
                    stopAlwaysTorch()
                    launchMain { stopCameraWhenIdle() }
                } else {
                    if (AutoNightMode.modeIncludesCameraAe(mode)) {
                        launchMain {
                            if (captureRunning) {
                                // Camera1 supplies luma only through its preview callback. An
                                // H.264-only session has none until it is rebuilt; CameraX always
                                // reports AE and luma from its analysis use case.
                                if (backend is Camera1Capture && runningSession?.demand?.nv21 != true) {
                                    debouncedStartCamera(force = true)
                                }
                            } else {
                                startCameraIfNeeded()
                            }
                        }
                    } else {
                        launchMain { stopCameraWhenIdle() }
                    }
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_switching" -> {
                val enabled = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> return
                }
                AutoNightMode.setSwitchingEnabled(this, enabled)
                NightMode.clearNightSightOverride()
                applyNightModeActive(NightMode.isActive(this))
                if (AutoNightMode.isEnabled(this)) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_offset" -> {
                val minutes = value.trim().toIntOrNull() ?: return
                AutoNightMode.setOffsetMinutes(this, minutes)
                if (AutoNightMode.modeIncludesGps(AutoNightMode.mode(this))) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_lux" -> {
                val lux = value.trim().toIntOrNull()
                    ?: value.trim().toFloatOrNull()?.let { Math.round(it) }
                    ?: return
                AutoNightMode.setLuxThreshold(this, lux)
                if (AutoNightMode.modeIncludesLux(AutoNightMode.mode(this))) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_schedule_start" -> {
                val start = parseClockMinutes(value) ?: return
                AutoNightMode.setSchedule(this, start, AutoNightMode.scheduleEndMinutes(this))
                if (AutoNightMode.modeIncludesSchedule(AutoNightMode.mode(this))) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_schedule_end" -> {
                val end = parseClockMinutes(value) ?: return
                AutoNightMode.setSchedule(this, AutoNightMode.scheduleStartMinutes(this), end)
                if (AutoNightMode.modeIncludesSchedule(AutoNightMode.mode(this))) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "auto_night_camera_ae_threshold" -> {
                val percent = value.trim().toIntOrNull() ?: return
                AutoNightMode.setCameraAeThreshold(this, percent)
                if (AutoNightMode.modeIncludesCameraAe(AutoNightMode.mode(this))) {
                    lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
                }
            }
            "night_torch_mode" -> {
                val mode = NightMode.normalizeTorchMode(value)
                // Motion flashes need an always-on camera with motion detection enabled.
                if (mode == NightMode.TORCH_MODE_MOTION && !motionDetectionEnabled()) {
                    Log.w(TAG, "Ignoring night_torch_mode=motion: motion detection is not enabled")
                    NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
                    stopAlwaysTorch()
                    endAutoFlashCycle()
                    return
                }
                // Camera AE can use the always torch in On demand (the decision is latched for the
                // session). In always-on modes the torch would blind the meter permanently.
                if (mode == NightMode.TORCH_MODE_ALWAYS &&
                    AutoNightMode.mode(this) == AutoNightMode.MODE_CAMERA_AE &&
                    !onDemandMode()
                ) {
                    Log.w(TAG, "Ignoring night_torch_mode=always: Camera AE cannot meter through the torch")
                    NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
                    stopAlwaysTorch()
                    return
                }
                NightMode.setTorchMode(this, mode)
                if (mode != NightMode.TORCH_MODE_MOTION) endAutoFlashCycle()
                if (mode != NightMode.TORCH_MODE_ALWAYS) stopAlwaysTorch()
                lifecycleScope.launch(Dispatchers.IO) { evaluateAutoNightMode() }
            }
            "night_auto_flash" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> return
                }
                if (next && !motionDetectionEnabled()) {
                    Log.w(TAG, "Ignoring night_auto_flash=on: motion detection is not enabled")
                    NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
                    return
                }
                val mode = if (next) NightMode.TORCH_MODE_MOTION else NightMode.TORCH_MODE_OFF
                NightMode.setTorchMode(this, mode)
                stopAlwaysTorch()
                if (next) {
                    if (nightModeEnabled && motionMonitor?.detector?.active == true) {
                        startAutoFlashCycle()
                    }
                } else {
                    endAutoFlashCycle()
                }
            }
            "night_auto_flash_seconds" -> {
                val seconds = value.trim().toIntOrNull() ?: return
                NightMode.setAutoFlashSeconds(this, seconds)
            }
            "torch" -> {
                val nightTorch = if (nightModeEnabled && nightCaptureReady) nightCapture?.getTorch() else null
                val current = nightTorch ?: backend?.getTorch()
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
                prefs.edit { putString(PREF_CAMERA_TORCH, if (next) "on" else "off") }
                refreshFlashSuppression()
                launchMain {
                    awaitCameraSettled()
                    if (next && !captureRunning) {
                        // Nothing is streaming, so there is no backend to talk to. Open the camera
                        // just for the torch and record that this session is ours to close.
                        cameraHeldForTorch = true
                        startCamera(force = true)
                    }
                    applyTorch(next)
                    // A manual toggle can land while the camera is still settling; enableTorch
                    // then reports "Camera is not active" without a retry of its own.
                    if (next) scheduleVerifyTorchRestored()
                    if (!next && cameraHeldForTorch) {
                        cameraHeldForTorch = false
                        stopCameraWhenIdle()
                    }
                }
            }
            "exposure" -> {
                val ev = value.toIntOrNull() ?: return
                prefs.edit { putString("exposure_$id", ev.toString()) }
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("exposure_$physicalId", ev.toString()) }
                launchMain { backend?.setExposure(ev) }
            }
            "zoom" -> {
                val z = value.toFloatOrNull() ?: return
                val zStr = String.format(Locale.US, "%.1f", z)
                prefs.edit { putString("zoom_$id", zStr) }
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("zoom_$physicalId", zStr) }
                launchMain { backend?.setZoom(z) }
            }
            "focus_distance" -> {
                val f = value.toFloatOrNull() ?: return
                if (f < 0f) {
                    prefs.edit { remove("focus_$id") }
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit { remove("focus_$physicalId") }
                } else {
                    prefs.edit { putString("focus_$id", f.coerceIn(0f, 1f).toString()) }
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("focus_$physicalId", f.coerceIn(0f, 1f).toString()) }
                }
                launchMain { backend?.setManualFocus(f) }
            }
            "snapshot_res" -> {
                if (value == "max" || value == "stream") {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                    val sid = camId()
                    prefs.edit { putString("snapshot_res_$sid", value) }
                    val phys = sid.substringAfter(':', sid)
                    if (phys.isNotBlank() && phys != sid) prefs.edit { putString("snapshot_res_$phys", value) }
                }
            }
            "camera" -> {
                val keepTorchOn = backend?.getTorch() == true
                val target = resolveCamera(value) ?: return
                CameraLightMeter.reset()
                NightMode.clearNightSightOverride()
                NightMode.clearGrayscaleOverride()
                selectCamera(target.first, target.second)
                // Save the original value (e.g. "1:3") so the dropdown can match it;
                // selectedCameraId may be the bare physical ID (e.g. "3") used by CameraX.
                // Persist the canonical camera id (what camId() will return) so per-camera
                // preferences are stored/loaded under the same key format.
                prefs.edit {
                    putString(PREF_CAMERA_ID, camId())
                }
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
                        prefs.edit {
                            putString("camera_resolution", value)
                            putString("stream_res", "auto")
                        }
                        debouncedStartCamera()
                    }
                    value == "auto" || value == "max" -> {
                        prefs.edit { putString("stream_res", "auto") }
                        debouncedStartCamera()
                    }
                    else -> {
                        val m = Regex("^(\\d+)x(\\d+)$").find(value) ?: return
                        val w = m.groupValues[1].toIntOrNull()
                        val h = m.groupValues[2].toIntOrNull()
                        if (w == null || h == null || w <= 0 || h <= 0) return
                        // Store the normalised size the stream will actually use, so /info.json
                        // reports exactly what the user selected.
                        prefs.edit { putString("stream_res", "${w}x${h}") }
                        debouncedStartCamera()
                    }
                }
            }
            // Main stream encoder settings. MediaCodec only reads bitrate, bitrate mode and
            // keyframe interval at configure time, so a change rebuilds the encoder via a restart.
            "stream_bitrate" -> {
                val raw = value.trim().lowercase()
                if (raw == StreamEncoding.AUTO) {
                    prefs.edit { putString(StreamEncoding.PREF_BITRATE, StreamEncoding.AUTO) }
                } else {
                    val kbps = raw.toIntOrNull()
                        ?.takeIf { it in StreamEncoding.MIN_BITRATE_KBPS..StreamEncoding.MAX_BITRATE_KBPS }
                        ?: return
                    prefs.edit { putString(StreamEncoding.PREF_BITRATE, kbps.toString()) }
                }
                debouncedStartCamera()
            }
            "stream_bitrate_mode" -> {
                val mode = value.trim().lowercase()
                if (mode != StreamEncoding.MODE_VBR && mode != StreamEncoding.MODE_CBR) return
                prefs.edit { putString(StreamEncoding.PREF_BITRATE_MODE, mode) }
                debouncedStartCamera()
            }
            "stream_keyframe_interval" -> {
                val sec = value.toIntOrNull()
                    ?.takeIf { it in StreamEncoding.MIN_KEYFRAME_INTERVAL_SEC..StreamEncoding.MAX_KEYFRAME_INTERVAL_SEC }
                    ?: return
                prefs.edit { putString(StreamEncoding.PREF_KEYFRAME_INTERVAL, sec.toString()) }
                debouncedStartCamera()
            }
            // Spatial denoising runs in the GL pipe, so it applies live without restarting the
            // camera or rebuilding the encoder. The level is persisted and exposed in /info.json.
            "stream_denoise" -> {
                val level = NoiseReduction.parse(value) ?: return
                prefs.edit { putString(NoiseReduction.PREF, level.toString()) }
                val strength = NoiseReduction.strength(level)
                glPipe?.denoise = strength
                cameraXGlPipe?.denoise = strength
                Log.i(TAG, "Noise reduction level=$level strength=$strength " +
                    "glPipe=${glPipe != null} cameraXGlPipe=${cameraXGlPipe != null}")
            }
            // Grayscale Night Mode is a post-processing switch shared by the GL video pipe and the
            // still encoders. The still loop reads the pref each time it processes a JPEG, so no
            // pipeline restart is needed when it changes.
            "night_grayscale" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> return
                }
                NightMode.setGrayscaleEnabled(this, next)
                applyGrayscaleState()
                Log.i(TAG, "Grayscale night mode=$next " +
                    "glPipe=${glPipe != null} cameraXGlPipe=${cameraXGlPipe != null}")
            }
            "night_grayscale_follow" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> return
                }
                NightMode.setGrayscaleFollowEnabled(this, next)
                NightMode.clearGrayscaleOverride()
                applyGrayscaleState()
                Log.i(TAG, "Auto grayscale=$next")
            }
            // Temporary manual grayscale choice while Auto grayscale owns the switch. It expires
            // automatically on the next automatic night/day transition.
            "night_grayscale_override" -> {
                val next = when (value.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    "clear", "auto", "none" -> null
                    else -> return
                }
                val autoOwns = NightMode.isGrayscaleFollowEnabled(this) &&
                    AutoNightMode.isEnabled(this) &&
                    AutoNightMode.currentDark() != null
                if (autoOwns) {
                    NightMode.setGrayscaleOverride(next)
                } else if (next != null) {
                    // The trigger is not currently driving grayscale, so the manual setting is the
                    // visible switch. Keep the control working in every state.
                    NightMode.setGrayscaleEnabled(this, next)
                }
                applyGrayscaleState()
                Log.i(TAG, "Grayscale override=${next ?: "auto"}")
            }
            // Burned-in OSD. The renderer re-reads these preferences once per second, so changes
            // apply to MJPEG, the GL surface path and the CPU H.264 path without a restart.
            "osd_enabled" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_ENABLED, next) }
            }
            "osd_time" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_TIME, next) }
            }
            "osd_date" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_DATE, next) }
            }
            "osd_battery" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_BATTERY, next) }
            }
            "osd_wifi" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_WIFI, next) }
            }
            "osd_temperature" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_TEMPERATURE, next) }
            }
            "osd_camera_name_enabled" -> {
                val next = CameraOsd.parseBoolean(value) ?: return
                prefs.edit { putBoolean(CameraOsd.PREF_CAMERA_NAME_ENABLED, next) }
            }
            "osd_camera_name" -> {
                prefs.edit { putString(CameraOsd.PREF_CAMERA_NAME, CameraOsd.sanitizeName(value)) }
            }
            "overheat_pause_c" -> {
                val threshold = OverheatProtection.parse(value) ?: return
                prefs.edit { putString(OverheatProtection.PREF, threshold.toString()) }
            }
            // RTSP sub stream and audio settings. The RTSP server reads them when a viewer
            // connects, so a change applies to the next connection (as the old Settings screen did).
            "rtsp_audio" -> {
                if (value.equals("true", true) || value.equals("false", true)) {
                    prefs.edit { putBoolean(RtspServer.PREF_AUDIO, value.toBoolean()) }
                }
            }
            "rtsp_sub_size" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(240, 360, 480, 720)) {
                    prefs.edit { putString(RtspServer.PREF_SUB_SIZE, n.toString()) }
                }
            }
            "rtsp_sub_fps" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(10, 15, 20, 30)) {
                    prefs.edit { putString(RtspServer.PREF_SUB_FPS, n.toString()) }
                }
            }
            "rtsp_sub_bitrate" -> {
                val n = value.toIntOrNull()
                if (n != null && n in listOf(300, 500, 800, 1200, 2000)) {
                    prefs.edit { putString(RtspServer.PREF_SUB_BITRATE, n.toString()) }
                }
            }
            "rotate" -> {
                val angle = value.toIntOrNull() ?: return
                // normalize to 0..359
                val norm = ((angle % 360) + 360) % 360
                // Persist rotate per-camera
                prefs.edit { putInt("camera_rotate_$id", norm) }
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putInt("camera_rotate_$physicalId", norm) }
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
                prefs.edit { putString("stream_scale_$id", value) }
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("stream_scale_$physicalId", value) }
            }
            "contrast" -> {
                // Persist contrast per-camera
                prefs.edit { putString("camera_contrast_$id", value) }
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("camera_contrast_$physicalId", value) }
            }
            "mirror" -> {
                if (value in listOf("true", "false")) {
                    prefs.edit { putString("mirror_$id", value) }
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit { putString("mirror_$physicalId", value) }
                    val enabled = value == "true"
                    glPipe?.mirror = enabled
                    cameraXGlPipe?.mirror = enabled
                }
            }
            "crop" -> {
                val raw = value.trim()
                val region = if (raw.equals("reset", true) || raw.equals("none", true) || raw.isEmpty()) {
                    null
                } else {
                    CropRegion.parse(raw) ?: return
                }
                CropRegion.write(prefs, id, region)
                glPipe?.crop = region
                cameraXGlPipe?.crop = region
                // The surface encoder frame follows the crop's aspect ratio, so it needs rebuilding;
                // MJPEG and the byte-buffer H.264 path pick the new region up on the next frame.
                // Never restart while recording — that would end the clip.
                if (localRecorder?.isRecording != true) debouncedStartCamera()
            }
            "api" -> {
                if (value in listOf("auto", "camerax", "camera1")) {
                    prefs.edit { putString("capture_api", value) }
                    // Explicitly choosing CameraX again re-tests the device (clears the remembered
                    // CameraX starts without frames).
                    if (value != "camera1") forgetCameraXMisses(prefs)
                    debouncedStartCamera()
                }
            }
            MotionMonitor.PREF_ENABLED -> {
                // Backwards compatibility for clients that still send the old Record on Motion
                // checkbox: on means motion clips, off means the camera sleeps until a viewer.
                val enabled = when (value.lowercase()) {
                    "true", "on", "1" -> true
                    "false", "off", "0" -> false
                    else -> return
                }
                val mode = if (enabled) MotionMonitor.MODE_MOTION else MotionMonitor.MODE_OFF
                prefs.edit {
                    putBoolean(MotionMonitor.PREF_ENABLED, enabled)
                    putString(MotionMonitor.PREF_MODE, mode)
                    putBoolean(MotionMonitor.PREF_NOTIFY, enabled)
                }
                motionMonitor?.onSettingsChanged()
                applyRecordingMode(mode)
            }
            MotionMonitor.PREF_MODE -> {
                val mode = MotionMonitor.normalizeMode(value) ?: return
                // Motion clips forces notifications on, On demand forces them off, and the other
                // modes keep whatever the independent Notify on motion switch says.
                val notify = when {
                    mode == MotionMonitor.MODE_OFF -> false
                    mode == MotionMonitor.MODE_MOTION -> true
                    MotionMonitor.isLegacyNotifyMode(value) -> true
                    else -> MotionMonitor.notifyEnabled(this)
                }
                prefs.edit {
                    putString(MotionMonitor.PREF_MODE, mode)
                    putBoolean(MotionMonitor.PREF_ENABLED, mode != MotionMonitor.MODE_OFF)
                    putBoolean(MotionMonitor.PREF_NOTIFY, notify)
                }
                motionMonitor?.onSettingsChanged()
                if (!notify) onvifServer?.setMotion(false)
                applyRecordingMode(mode)
            }
            MotionMonitor.PREF_NOTIFY -> {
                val requested = when (value.lowercase()) {
                    "true", "on", "1" -> true
                    "false", "off", "0" -> false
                    else -> return
                }
                val mode = MotionMonitor.modeFor(this)
                val enabled = when (mode) {
                    MotionMonitor.MODE_OFF -> false
                    MotionMonitor.MODE_MOTION -> true
                    else -> requested
                }
                val wasDetecting = motionMonitor?.motionDetection == true
                prefs.edit { putBoolean(MotionMonitor.PREF_NOTIFY, enabled) }
                val nowDetecting = motionMonitor?.motionDetection == true
                if (wasDetecting != nowDetecting) motionMonitor?.onSettingsChanged()
                if (!nowDetecting) {
                    onvifServer?.setMotion(false)
                } else if (motionMonitor?.detector?.active == true) {
                    onvifServer?.setMotion(true)
                }
            }
            MotionMonitor.PREF_SENSITIVITY -> {
                val sensitivity = value.toIntOrNull()?.coerceIn(1, 10) ?: return
                prefs.edit { putString(MotionMonitor.PREF_SENSITIVITY, sensitivity.toString()) }
            }
            MotionMonitor.PREF_STOP_DELAY -> {
                val delay = value.toIntOrNull()?.coerceIn(0, MotionMonitor.MAX_STOP_DELAY) ?: return
                prefs.edit { putString(MotionMonitor.PREF_STOP_DELAY, delay.toString()) }
            }
            MotionMonitor.PREF_SEGMENT_MINUTES -> {
                val minutes = value.toIntOrNull()
                    ?.coerceIn(MotionMonitor.MIN_SEGMENT_MINUTES, MotionMonitor.MAX_SEGMENT_MINUTES)
                    ?: return
                prefs.edit { putString(MotionMonitor.PREF_SEGMENT_MINUTES, minutes.toString()) }
            }
            MotionMonitor.PREF_STORAGE_LIMIT_PERCENT -> {
                val percent = value.toIntOrNull()
                    ?.coerceIn(MotionMonitor.MIN_STORAGE_LIMIT_PERCENT, MotionMonitor.MAX_STORAGE_LIMIT_PERCENT)
                    ?: return
                prefs.edit { putString(MotionMonitor.PREF_STORAGE_LIMIT_PERCENT, percent.toString()) }
                // Apply the new limit immediately instead of waiting for the next clip to finish.
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        RecordingsHelper.trimOldest(this@StreamingService, percent) {
                            localRecorder?.currentRelativePath()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Storage cleanup failed: ${e.message}")
                    }
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

    /** Stops the current clip. In continuous mode this also ends the segmented-recording loop. */
    fun stopRecording(): RecordingResponse {
        if (localRecorder?.trigger == LocalRecorder.Trigger.CONTINUOUS) stopContinuousRecording()
        return stopRecordingInternal()
    }

    private fun stopRecordingInternal(): RecordingResponse {
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
                trimRecordingsIfNeeded()
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
        val status = localRecorder?.statusJson() ?: """{"recording":false}"""
        return try {
            JSONObject(status)
                .put("mode", motionMonitor?.mode ?: MotionMonitor.modeFor(this))
                .put(
                    "notifyOnMotion",
                    motionMonitor?.notifyOnMotion ?: MotionMonitor.notifyEnabled(this)
                )
                .toString()
        } catch (_: Exception) {
            status
        }
    }

    /** Deletes the oldest clips when the recordings drive has reached the configured limit. */
    private fun trimRecordingsIfNeeded() {
        val percent = motionMonitor?.storageLimitPercent
            ?: MotionMonitor.DEFAULT_STORAGE_LIMIT_PERCENT
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deleted = RecordingsHelper.trimOldest(this@StreamingService, percent) {
                    localRecorder?.currentRelativePath()
                }
                if (deleted > 0) onLog?.invoke("Storage cleanup deleted $deleted old recording(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Storage cleanup failed: ${e.message}")
            }
        }
    }

    /**
     * Applies a recording-mode change live: starts or stops the always-on camera, motion clips and
     * the segmented continuous recorder. Manual recordings are left alone.
     */
    private fun applyRecordingMode(mode: String) {
        val recorder = localRecorder
        when (mode) {
            MotionMonitor.MODE_OFF -> {
                // Motion detection is unavailable in On demand mode, so a flash cycle cannot get
                // its ending event. End it now instead of leaving the torch on indefinitely. The
                // automatic night torch is left to the auto-night loop: it keeps following the
                // camera session and is released when the camera goes idle.
                endAutoFlashCycle()
                stopContinuousRecording()
                motionStopJob?.cancel()
                motionStopJob = null
                if (recorder?.isRecording == true && recorder.trigger != LocalRecorder.Trigger.MANUAL) {
                    stopRecording()
                }
                launchMain {
                    if (localRecorder?.isRecording != true &&
                        !encoders.any { it.hasClients() } && currentSurfaceProvider == null) {
                        stopCameraWhenIdle()
                        bridgeTorchAcrossIdle()
                    }
                }
                if (!hasActiveClients()) releaseStreamingWakeLock()
            }

            MotionMonitor.MODE_CONTINUOUS -> {
                motionStopJob?.cancel()
                motionStopJob = null
                if (recorder?.isRecording == true && recorder.trigger == LocalRecorder.Trigger.MOTION) {
                    stopRecording()
                }
                acquireStreamingWakeLock()
                startContinuousRecording()
            }

            else -> { // MODE_ALWAYS_ON or MODE_MOTION
                stopContinuousRecording()
                if (recorder?.isRecording == true &&
                    (recorder.trigger == LocalRecorder.Trigger.CONTINUOUS ||
                        (mode != MotionMonitor.MODE_MOTION && recorder.trigger == LocalRecorder.Trigger.MOTION))
                ) {
                    stopRecording()
                }
                acquireStreamingWakeLock()
                launchMain { startCameraIfNeeded() }
            }
        }
        // The selected torch profile may no longer be valid for this camera mode.
        sanitizeTorchMode()
        // ONVIF is also available while an always-on mode is selected, even with RTSP disabled.
        startOnvifIfEnabled()
    }

    /** Runs the camera continuously, closing and reopening a new file every segment. */
    private fun startContinuousRecording() {
        if (continuousRecordingJob?.isActive == true) return
        continuousRecordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val self = coroutineContext[kotlinx.coroutines.Job]
            while (isActive && continuousRecordingJob === self &&
                motionMonitor?.continuousRecording == true
            ) {
                if (localRecorder?.isRecording == true) {
                    // A manual clip owns the encoder; wait for it instead of failing every second.
                    kotlinx.coroutines.delay(1_000)
                    continue
                }
                val (ok, status, body) = startRecording(LocalRecorder.Trigger.CONTINUOUS)
                if (!ok) {
                    Log.w(TAG, "Continuous recording failed to start: $status $body")
                    // The failure may be a full drive; free space before retrying.
                    trimRecordingsIfNeeded()
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                // The mode may have changed while the camera was starting. Only this job's own
                // recorder is stopped: a newer continuous job may already own a fresh one.
                val recorder = localRecorder
                if (!isActive || continuousRecordingJob !== self ||
                    motionMonitor?.continuousRecording != true
                ) {
                    if (localRecorder === recorder) stopRecordingInternal()
                    break
                }
                val startedAt = recorder?.recordingStartTimeMs ?: System.currentTimeMillis()
                val segmentMinutes = motionMonitor?.segmentMinutes
                    ?: MotionMonitor.DEFAULT_SEGMENT_MINUTES
                val remaining = segmentMinutes * 60_000L - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) kotlinx.coroutines.delay(remaining)
                if (!isActive || continuousRecordingJob !== self) {
                    if (localRecorder === recorder) stopRecordingInternal()
                    break
                }
                if (motionMonitor?.continuousRecording != true) {
                    if (localRecorder === recorder) stopRecordingInternal()
                    break
                }
                stopRecordingInternal()
            }
        }
    }

    private fun stopContinuousRecording() {
        continuousRecordingJob?.cancel()
        continuousRecordingJob = null
    }

    /** Motion true starts a motion clip; motion false schedules the auto-stop after the set delay. */
    private fun handleMotionRecording(active: Boolean) {
        val monitor = motionMonitor ?: return
        if (active) {
            motionStopJob?.cancel()
            motionStopJob = null
            if (monitor.notifyOnMotion) notifyMotion()
            if (monitor.recordingOnMotion) {
                lifecycleScope.launch(Dispatchers.IO) { startMotionRecordingIfNeeded() }
            }
        } else if (monitor.recordingOnMotion) {
            scheduleMotionRecordingStop()
        }
    }

    // ---------------- motion-triggered flash (night mode helper) ----------------

    @Synchronized
    private fun startAlwaysTorch() {
        if (alwaysTorchActive) return
        // In On demand the auto torch is part of the camera session; never start it while idle.
        if (onDemandMode() && !onDemandCameraInUse()) return
        alwaysTorchActive = true
        refreshFlashSuppression()
        applyAutoFlashTorch(true)
        alwaysTorchJob?.cancel()
        alwaysTorchJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && alwaysTorchActive) {
                applyTorch(true)
                delay(500)
            }
        }
        Log.i(TAG, "Always-on night torch active")
    }

    @Synchronized
    private fun stopAlwaysTorch() {
        if (!alwaysTorchActive) return
        alwaysTorchActive = false
        alwaysTorchJob?.cancel()
        alwaysTorchJob = null
        val manualTorch = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_CAMERA_TORCH, "off") == "on"
        applyAutoFlashTorch(manualTorch)
        refreshFlashSuppression()
        Log.i(TAG, "Always-on night torch inactive")
    }

    /** Keep the stored torch profile valid after the camera/recording mode changes. */
    private fun sanitizeTorchMode() {
        val torch = NightMode.torchMode(this)
        if (torch == NightMode.TORCH_MODE_MOTION && !motionDetectionEnabled()) {
            NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
            endAutoFlashCycle()
            return
        }
        if (torch == NightMode.TORCH_MODE_ALWAYS &&
            AutoNightMode.mode(this) == AutoNightMode.MODE_CAMERA_AE && !onDemandMode()
        ) {
            NightMode.setTorchMode(this, NightMode.TORCH_MODE_OFF)
            stopAlwaysTorch()
        }
    }

    /** Re-run the schedule after a manual night-mode toggle so automatic switching reasserts it. */
    private fun recheckAutoNightAfterManual() {
        if (!AutoNightMode.isEnabled(this) || !AutoNightMode.isSwitchingEnabled(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1_500)
            evaluateAutoNightMode()
        }
    }

    /**
     * When "Flash on motion" is enabled and the automatic trigger currently says it is night,
     * motion pauses night-sight and grayscale, switches to normal video, and lights the torch.
     * Once motion clears, the flash stays on for the configured number of seconds; motion during
     * that wait restarts the timer. When the timer finally expires, the torch goes off and both
     * selected modes return.
     */
    private fun handleAutoFlashMotion(active: Boolean) {
        if (overheatPaused) return
        if (NightMode.torchMode(this) != NightMode.TORCH_MODE_MOTION) return
        if (motionMonitor?.motionDetection != true) return
        if (active) {
            autoFlashOffJob?.cancel()
            autoFlashOffJob = null
            if (autoFlashCycleActive) return
            // Auto flash follows the automatic night-mode schedule, not the manual night-mode
            // switch. It only runs while that schedule currently says it is night.
            if (autoNightScheduleOn != true) return
            startAutoFlashCycle()
        } else if (autoFlashCycleActive) {
            scheduleAutoFlashOff()
        }
    }

    @Synchronized
    private fun startAutoFlashCycle() {
        if (autoFlashCycleActive) return
        autoFlashCycleActive = true
        autoFlashOffJob?.cancel()
        autoFlashOffJob = null
        refreshFlashSuppression()
        applyAutoFlashTorch(true)
        startAutoFlashTorchLoop()
        Log.i(TAG, "Auto flash: motion detected; night sight and grayscale paused, torch on")
    }

    private fun scheduleAutoFlashOff() {
        autoFlashOffJob?.cancel()
        val seconds = NightMode.autoFlashSeconds(this)
        autoFlashOffJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(seconds * 1000L)
            // Motion during the wait restarts this timer; never end the cycle while motion is active.
            if (autoFlashCycleActive && motionMonitor?.detector?.active != true) {
                endAutoFlashCycle()
            }
        }
    }

    @Synchronized
    private fun endAutoFlashCycle() {
        if (!autoFlashCycleActive) return
        autoFlashCycleActive = false
        autoFlashOffJob?.cancel()
        autoFlashOffJob = null
        autoFlashTorchJob?.cancel()
        autoFlashTorchJob = null
        val manualTorch = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(PREF_CAMERA_TORCH, "off") == "on"
        applyAutoFlashTorch(manualTorch)
        refreshFlashSuppression()
        Log.i(TAG, "Auto flash: motion cleared; torch off, night sight and grayscale restored")
    }

    /** Turn the torch on or off for the auto-flash cycle, routing through the device when needed. */
    private fun applyAutoFlashTorch(on: Boolean) {
        launchMain { applyTorch(on) }
    }

    /**
     * Keep the torch held while the cycle is active. The camera is restarted when night mode is
     * paused, so a single enableTorch() call can lose the race with the settling HAL.
     */
    private fun startAutoFlashTorchLoop() {
        autoFlashTorchJob?.cancel()
        autoFlashTorchJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && autoFlashCycleActive) {
                applyTorch(true)
                delay(500)
            }
        }
    }

    private fun startMotionRecordingIfNeeded() {
        if (motionMonitor?.recordingOnMotion != true || localRecorder?.isRecording == true) return
        val (ok, status, body) = startRecording(LocalRecorder.Trigger.MOTION)
        if (!ok) {
            Log.w(TAG, "Motion recording failed to start: $status $body")
            return
        }
        // Record on Motion may have been turned off, or motion cleared, while the camera/encoder
        // was starting. In both cases the clip should not linger.
        if (motionMonitor?.recordingOnMotion != true) {
            stopRecording()
        } else if (motionMonitor?.detector?.active != true) {
            scheduleMotionRecordingStop()
        }
    }

    private fun scheduleMotionRecordingStop() {
        if (motionMonitor?.recordingOnMotion != true) return
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
        // Camera always-on modes need the camera to keep delivering frames with no viewer
        // connected. Stopping here would end them until the next client connects, so leave the
        // running pipeline alone. Camera AE is not a reason to keep the camera running.
        if (cameraAlwaysOn()) return
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
        stopContinuousRecording()
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

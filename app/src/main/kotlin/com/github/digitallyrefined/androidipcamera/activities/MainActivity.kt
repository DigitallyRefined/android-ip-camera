package com.github.digitallyrefined.androidipcamera.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import com.github.digitallyrefined.androidipcamera.helpers.ListenInterface
import com.github.digitallyrefined.androidipcamera.helpers.RtspServer
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelConfig
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelController
import com.github.digitallyrefined.androidipcamera.opentunnel.OpenTunnelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var streamingService: StreamingService? = null
    private var isBound = false
    private var hasRequestedPermissions = false
    private var userHiddenPreview = false
    private lateinit var noClientMessage: TextView
    private lateinit var openTunnelText: TextView
    private var openTunnelPollJob: Job? = null
    private lateinit var backGestureCallback: OnBackPressedCallback
    private var isFullscreen = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true

            // Connect UI listeners
            streamingService?.onClientConnected = {
                runOnUiThread {
                    showNoClientMessage(false)
                }
            }
            streamingService?.onClientDisconnected = {
                runOnUiThread {
                    val hasClients = streamingService?.hasActiveClients() == true
                    showNoClientMessage(!hasClients)
                }
            }
            streamingService?.onLog = { message ->
                Log.i(TAG, "Service: $message")
            }

            // The service may have been restarted in the background (where Android does not
            // allow camera/microphone foreground service types). The activity is now in the
            // foreground, so retry the types before starting the server and camera.
            streamingService?.ensureForegroundTypes()

            // Initialize Server
            streamingService?.startStreamingServer()

            // Check current status
            val hasClients = streamingService?.hasActiveClients() == true

            // Set Preview if needed
            if (!userHiddenPreview) {
                if (hasClients) {
                    streamingService?.setPreviewSurface(null)
                } else {
                    streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
                }
            }

            showNoClientMessage(!hasClients)
            updateOpenTunnelText()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            streamingService = null
        }
    }

    private val cameraRestartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.github.digitallyrefined.androidipcamera.RESTART_CAMERA") {
                if (allPermissionsGranted() && isBound) {
                    // Trigger camera restart in service by re-setting preview or calling explicit method
                    // For now, re-setting preview triggers restart in service if camera was running
                    if (!userHiddenPreview) {
                        val hasClients = streamingService?.hasActiveClients() == true
                        if (hasClients) {
                            streamingService?.setPreviewSurface(null)
                        } else {
                            streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
                        }
                    }
                }
            }
        }
    }

    private val closeAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.github.digitallyrefined.androidipcamera.CLOSE_APP") {
                finishAndRemoveTask()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Black window background so the system bar inset padding and the preview
        // surface never flash the light theme colour behind them.
        window.setBackgroundDrawableResource(android.R.color.black)

        // Initialize view binding first
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        noClientMessage = findViewById(R.id.noClientMessage)
        openTunnelText = findViewById(R.id.openTunnelText)

        backGestureCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideShowPreview()
            }
        }
        onBackPressedDispatcher.addCallback(this, backGestureCallback)

        // Draw behind the system bars and apply the bar insets as padding ourselves. This
        // keeps the buttons clear of the status bar on start (and on Android 15+, where
        // edge-to-edge is enforced) while still allowing true fullscreen in black screen mode.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsets()

        supportActionBar?.hide()

        // Start with the system bars visible; fullscreen is only entered for the black
        // screen (hidden preview) mode.
        setFullscreen(false)

        if (!allPermissionsGranted() && !hasRequestedPermissions) {
            hasRequestedPermissions = true
            val toRequest = (REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS).distinct().toTypedArray()
            ActivityCompat.requestPermissions(this, toRequest, REQUEST_CODE_PERMISSIONS)
        } else if (allPermissionsGranted()) {
            startService()
        } else {
            finish()
        }

        ContextCompat.registerReceiver(this, cameraRestartReceiver, IntentFilter("com.github.digitallyrefined.androidipcamera.RESTART_CAMERA"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, closeAppReceiver, IntentFilter("com.github.digitallyrefined.androidipcamera.CLOSE_APP"), ContextCompat.RECEIVER_NOT_EXPORTED)

        showNoClientMessage(true)
        updateIpAddressText()

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.hidePreviewButton).setOnClickListener {
            hideShowPreview()
        }

        findViewById<ImageButton>(R.id.helpButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://ip-cam.app".toUri()))
        }

        findViewById<ImageButton>(R.id.exitButton).setOnClickListener {
            exitApp()
        }
    }

    private fun startService() {
        val intent = Intent(this, StreamingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (isBound && !userHiddenPreview) {
            val hasClients = streamingService?.hasActiveClients() == true
            if (hasClients) {
                streamingService?.setPreviewSurface(null)
            } else {
                streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
            }
        }
        // Refresh the IP address text in case the port was changed in settings
        updateIpAddressText()
        updateOpenTunnelText()
        startOpenTunnelPolling()
        checkNotificationChannelEnabled()
        // Re-apply the bar visibility: a returning activity or dialog may have restored them.
        setFullscreen(userHiddenPreview)
    }

    /**
     * Pads the root layout by the system bar insets while they are visible. In fullscreen
     * mode the padding is removed so the camera preview and black screen fill the display.
     */
    private fun applySystemBarInsets() {
        val root = viewBinding.root
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (isFullscreen) {
                view.setPadding(0, 0, 0, 0)
            } else {
                // Include the cutout so the buttons clear a notch in landscape too.
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Shows or hides the status and navigation bars. Hiding uses the transient-by-swipe
     * behaviour so a user can still reveal the system bars if they need them.
     */
    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // White icons on the app's black background regardless of the system theme.
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(viewBinding.root)
    }

    private fun updateIpAddressText() {
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)
        val ipAddress = getLocalIpAddress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tlsEnabled = prefs.getBoolean("tls_enabled", true)
        val protocol = if (tlsEnabled) "https" else "http"
        val rtspProtocol = if (tlsEnabled) "rtsps" else "rtsp"
        val port = prefs.getString("server_port", "4444")?.toIntOrNull() ?: 4444
        val rtspPort = prefs.getString(RtspServer.PREF_PORT, null)?.toIntOrNull() ?: RtspServer.DEFAULT_PORT
        val rtspEnabled = prefs.getBoolean(RtspServer.PREF_ENABLED, false)
        val onvifPort = prefs.getString("onvif_port", null)?.toIntOrNull() ?: 8000
        val rtspUrls = if (rtspEnabled) {
            "\n$rtspProtocol://$ipAddress:$rtspPort${RtspServer.PATH} (& ${RtspServer.SUB_PATH})" +
                "\nONVIF $protocol://$ipAddress:$onvifPort"
        } else {
            ""
        }
        ipAddressText.text = getString(R.string.stream_urls, protocol, ipAddress, port, rtspUrls)
    }

    private fun startOpenTunnelPolling() {
        openTunnelPollJob?.cancel()
        openTunnelPollJob = lifecycleScope.launch {
            while (isActive) {
                updateOpenTunnelText()
                delay(1_000)
            }
        }
    }

    /**
     * The OpenTunnel line under the local URLs:
     *  - no identity yet: the setup hint, replaced by live progress after the first tap
     *  - registered and connected: the public HTTPS URL, tap to share
     *  - registered and connecting/reconnecting/failed: one line of status
     */
    private fun updateOpenTunnelText() {
        if (!::openTunnelText.isInitialized) return
        if (!OpenTunnelConfig.isEnabled(this)) {
            openTunnelText.visibility = View.GONE
            openTunnelText.setOnClickListener(null)
            openTunnelText.isClickable = false
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tapped = prefs.getBoolean(OpenTunnelConfig.PREF_TAPPED, false)
        val status = if (isBound) {
            streamingService?.openTunnelStatus() ?: OpenTunnelController.status(this)
        } else {
            OpenTunnelController.status(this)
        }

        val text: String?
        var openUrl: String? = null
        var connect = false
        var setup = false
        when {
            status.connected && !status.url.isNullOrBlank() -> {
                text = status.url
                openUrl = status.url
            }
            status.registered && (status.busy || status.state == OpenTunnelStatus.STATE_STOPPED) -> {
                text = status.progressText()
                connect = !status.busy
            }
            !status.registered && tapped -> {
                text = status.progressText()
                // A failed or interrupted registration can be retried by tapping.
                setup = !status.busy
            }
            !status.registered -> {
                text = "Tap to setup optional remote access"
                setup = true
            }
            else -> {
                // Registered but idle: nothing useful to show until it is connected.
                text = null
            }
        }

        if (text.isNullOrBlank()) {
            openTunnelText.visibility = View.GONE
            openTunnelText.setOnClickListener(null)
            openTunnelText.isClickable = false
            return
        }

        openTunnelText.text = text
        openTunnelText.visibility = if (userHiddenPreview) View.GONE else View.VISIBLE
        val urlToOpen = openUrl
        if (urlToOpen != null || connect || setup) {
            openTunnelText.setOnClickListener(
                if (urlToOpen != null) View.OnClickListener {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, urlToOpen)
                    }
                    try {
                        startActivity(Intent.createChooser(send, "Share tunnel URL"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not share $urlToOpen", Toast.LENGTH_SHORT).show()
                    }
                } else View.OnClickListener { onOpenTunnelTapped() }
            )
        } else {
            openTunnelText.setOnClickListener(null)
            openTunnelText.isClickable = false
        }
    }

    private fun onOpenTunnelTapped() {
        if (!OpenTunnelConfig.isEnabled(this)) return
        val problem = OpenTunnelConfig.credentialsProblem(this)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (!OpenTunnelController.isAvailable) {
            Toast.makeText(
                this,
                OpenTunnelController.unavailableReason ?: "OpenTunnel is unavailable on this build",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean(OpenTunnelConfig.PREF_TAPPED, true)
        }

        val status = if (isBound) {
            streamingService?.openTunnelStatus() ?: OpenTunnelController.status(this)
        } else {
            OpenTunnelController.status(this)
        }
        val action = if (status.registered) {
            StreamingService.ACTION_OPEN_TUNNEL_CONNECT
        } else {
            StreamingService.ACTION_OPEN_TUNNEL_REGISTER
        }
        if (isBound && streamingService != null) {
            if (status.registered) streamingService?.connectOpenTunnel()
            else streamingService?.registerOpenTunnel()
        } else {
            startService(Intent(this, StreamingService::class.java).setAction(action))
        }
        updateOpenTunnelText()
    }

    private fun checkNotificationChannelEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel = manager.getNotificationChannel("streaming_service_channel")
            if (channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                Toast.makeText(this, "Notification permissions are required for the camera server to function", Toast.LENGTH_LONG).show()
                exitApp()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        openTunnelPollJob?.cancel()
        openTunnelPollJob = null
        if (isBound) {
            streamingService?.setPreviewSurface(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        openTunnelPollJob?.cancel()
        openTunnelPollJob = null
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        unregisterReceiver(cameraRestartReceiver)
        unregisterReceiver(closeAppReceiver)
    }

    public fun exitApp() {
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP_SERVICE
        }
        startService(stopIntent)

        try {
            unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Ignore if not bound
        }
        isBound = false
        streamingService = null
        val intent = Intent(this, StreamingService::class.java)
        stopService(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                if (!hasAudioPermission()) {
                    Toast.makeText(this, "Microphone permission denied audio streaming disabled", Toast.LENGTH_LONG).show()
                }
                startService()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("Camera and notification permissions are required for the camera server to function. Please enable them in App Settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setOnCancelListener {
                        exitApp()
                    }
                    .show()
            }
        }

        checkNotificationChannelEnabled()
    }

    private fun getLocalIpAddress(): String {
        ListenInterface.specificIpv4(this)?.let { return it }
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "unknown"
    }

    private fun hideShowPreview() {
        val viewFinder = viewBinding.viewFinder
        val rootView = viewBinding.root
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)
        val urlBar = findViewById<View>(R.id.urlBar)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val hidePreviewButton = findViewById<ImageButton>(R.id.hidePreviewButton)
        val helpButton = findViewById<ImageButton>(R.id.helpButton)
        val exitButton = findViewById<ImageButton>(R.id.exitButton)

        if (!userHiddenPreview) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            setFullscreen(true)

            viewFinder.visibility = View.INVISIBLE
            ipAddressText.visibility = View.GONE
            urlBar.visibility = View.GONE
            settingsButton.visibility = View.GONE
            noClientMessage.visibility = View.GONE
            hidePreviewButton.visibility = View.GONE
            helpButton.visibility = View.GONE
            exitButton.visibility = View.GONE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
            userHiddenPreview = true
            backGestureCallback.isEnabled = true

            if (isBound) {
                streamingService?.setPreviewSurface(null)
            }

            runOnUiThread {
                Toast.makeText(this, "Black screen, swipe back to exit", Toast.LENGTH_SHORT).show()
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            setFullscreen(false)

            ipAddressText.visibility = View.VISIBLE
            urlBar.visibility = View.VISIBLE
            settingsButton.visibility = View.VISIBLE
            hidePreviewButton.visibility = View.VISIBLE
            helpButton.visibility = View.VISIBLE
            exitButton.visibility = View.VISIBLE
            updateOpenTunnelText()

            val hasClients = streamingService?.hasActiveClients() == true

            viewFinder.visibility = if (hasClients) View.INVISIBLE else View.VISIBLE
            noClientMessage.visibility = if (hasClients) View.VISIBLE else View.GONE
            noClientMessage.text = if (hasClients) getString(R.string.streaming_active) else ""
            rootView.setBackgroundColor(if (hasClients) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT)

            userHiddenPreview = false
            backGestureCallback.isEnabled = false

            if (isBound) {
                if (hasClients) {
                    streamingService?.setPreviewSurface(null)
                } else {
                    streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
                }
            }
        }
    }

    private fun showNoClientMessage(show: Boolean) {
        val rootView = viewBinding.root
        if (show) {
            noClientMessage.visibility = View.GONE
            if (!userHiddenPreview) {
                viewBinding.viewFinder.visibility = View.VISIBLE
                rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            if (isBound) {
                streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
            }
        } else {
            noClientMessage.text = getString(R.string.streaming_active)
            noClientMessage.visibility = if (!userHiddenPreview) View.VISIBLE else View.GONE
            viewBinding.viewFinder.visibility = View.INVISIBLE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(baseContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
            // WRITE_EXTERNAL_STORAGE is required on API 24-28 for the legacy
            // local MP4 recording path (scoped storage / MediaStore used on 29+).
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            else -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        private val OPTIONAL_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
}

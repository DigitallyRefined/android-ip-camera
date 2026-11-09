package com.github.digitallyrefined.androidipcamera.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import com.github.digitallyrefined.androidipcamera.helpers.ListenInterface
import com.github.digitallyrefined.androidipcamera.helpers.RelayAssociation
import com.github.digitallyrefined.androidipcamera.helpers.RtspServer
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var streamingService: StreamingService? = null
    private var isBound = false
    private var hasRequestedPermissions = false
    private var userHiddenPreview = false
    private lateinit var noClientMessage: TextView
    private lateinit var backGestureCallback: OnBackPressedCallback
    /** Pairing details from an androidipcamera://associate intent, waiting for the service. */
    private var pendingAssociation: RelayAssociation.PairingIntent? = null
    private var associationInFlight = false

    // The relay URL shown under the local URLs depends on whether this camera
    // is still associated. The relay client clears the association from a
    // background thread when the relay rejects the device, so refresh the
    // preview when that flag changes.
    private val relayPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "relay_connected") {
                runOnUiThread { updateIpAddressText() }
            }
        }

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

            // An association intent may have arrived before the service was bound.
            processPendingAssociation()
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

        // Initialize view binding first
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        noClientMessage = findViewById(R.id.noClientMessage)

        captureAssociationIntent(intent)

        backGestureCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideShowPreview()
            }
        }
        onBackPressedDispatcher.addCallback(this, backGestureCallback)

        supportActionBar?.hide()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            controller?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }

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
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(relayPreferenceListener)
        checkNotificationChannelEnabled()
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
        val relayUrl = RelayAssociation.load(this)?.serverUrl
        val tapToSetupRelayPressed = prefs.getBoolean(PREF_TAP_TO_SETUP_RELAY_PRESSED, false)
        val showRelaySetupHint = relayUrl == null && !tapToSetupRelayPressed
        if (showRelaySetupHint) {
            ipAddressText.isClickable = true
            ipAddressText.setOnClickListener {
                prefs.edit().putBoolean(PREF_TAP_TO_SETUP_RELAY_PRESSED, true).apply()
                updateIpAddressText()
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RelayAssociation.FALLBACK_SERVER_URL)))
            }
        } else {
            ipAddressText.setOnClickListener(null)
            ipAddressText.isClickable = false
        }
        val baseText = "$protocol://$ipAddress:$port$rtspUrls"
        val displayedRelayUrl = relayUrl ?: if (showRelaySetupHint) "Tap to setup optional cloud relay" else null
        ipAddressText.text = if (displayedRelayUrl == null) baseText else "$baseText\n$displayedRelayUrl"
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
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(relayPreferenceListener)
        if (isBound) {
            streamingService?.setPreviewSurface(null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureAssociationIntent(intent)
    }

    private fun captureAssociationIntent(intent: Intent?) {
        if (intent == null) return
        // Some QR scanners (e.g. LineageOS) cannot open custom-scheme links and
        // only offer to share the scanned text, so accept the link via
        // ACTION_SEND as well as the normal VIEW deep link.
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val pairing = RelayAssociation.parseIntent(intent.data)
            ?: RelayAssociation.parseSharedText(sharedText)
            ?: return
        // Consume the deep link/shared text so a configuration change does not
        // replay the one-time pairing code.
        intent.data = null
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.clipData = null
        pendingAssociation = pairing
        Log.i(TAG, "Association intent for ${pairing.serverUrl}")
        processPendingAssociation()
    }

    /** Runs once the service is bound. */
    private fun processPendingAssociation() {
        val pairing = pendingAssociation ?: return
        val service = streamingService
        if (!isBound || service == null || associationInFlight) return
        pendingAssociation = null
        associationInFlight = true
        Toast.makeText(this, "Connecting to relay…", Toast.LENGTH_SHORT).show()
        service.associateRelay(pairing.serverUrl, pairing.code) { success, message ->
            associationInFlight = false
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (success) {
                // The relay connection starts outbound; the local preview can go back to idle.
                updateIpAddressText()
            }
            // An intent that arrived while this association was running.
            processPendingAssociation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val hidePreviewButton = findViewById<ImageButton>(R.id.hidePreviewButton)
        val exitButton = findViewById<ImageButton>(R.id.exitButton)

        if (!userHiddenPreview) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            viewFinder.visibility = View.INVISIBLE
            ipAddressText.visibility = View.GONE
            settingsButton.visibility = View.GONE
            noClientMessage.visibility = View.GONE
            hidePreviewButton.visibility = View.GONE
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

            ipAddressText.visibility = View.VISIBLE
            settingsButton.visibility = View.VISIBLE
            hidePreviewButton.visibility = View.VISIBLE
            exitButton.visibility = View.VISIBLE

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
        private const val PREF_TAP_TO_SETUP_RELAY_PRESSED = "tap_to_setup_relay_pressed"
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

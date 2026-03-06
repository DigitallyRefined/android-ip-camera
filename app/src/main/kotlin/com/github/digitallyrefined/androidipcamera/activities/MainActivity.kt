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
                    showNoClientMessage(true)
                }
            }
            streamingService?.onLog = { message ->
                Log.i(TAG, "Service: $message")
            }

            // Initialize Server
            streamingService?.startStreamingServer()

            // Set Preview if needed
            if (!userHiddenPreview) {
                streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
            }

            // Check current status
            val hasClients = streamingService?.streamingServerHelper?.getClients()?.isNotEmpty() == true
            showNoClientMessage(!hasClients)
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
                        streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
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

        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)
        val ipAddress = getLocalIpAddress()
        ipAddressText.text = "https://$ipAddress:$STREAM_PORT"
        showNoClientMessage(true)

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
            streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
        }
        checkNotificationChannelEnabled()
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
        if (isBound) {
            streamingService?.setPreviewSurface(null)
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

            val hasClients = streamingService?.streamingServerHelper?.getClients()?.isNotEmpty() == true

            viewFinder.visibility = if (hasClients) View.VISIBLE else View.INVISIBLE
            noClientMessage.visibility = if (hasClients) View.GONE else View.VISIBLE
            rootView.setBackgroundColor(if (hasClients) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)

            userHiddenPreview = false
            backGestureCallback.isEnabled = false

            if (isBound && hasClients) {
                streamingService?.setPreviewSurface(viewBinding.viewFinder.surfaceProvider)
            }
        }
    }

    private fun showNoClientMessage(show: Boolean) {
        noClientMessage.visibility = if (show && !userHiddenPreview) View.VISIBLE else View.GONE
        val rootView = viewBinding.root
        if (show) {
            viewBinding.viewFinder.visibility = View.INVISIBLE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
        } else {
            if (!userHiddenPreview) {
                viewBinding.viewFinder.visibility = View.VISIBLE
                rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(baseContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_PORT = 4444
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        private val OPTIONAL_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)
    }
}

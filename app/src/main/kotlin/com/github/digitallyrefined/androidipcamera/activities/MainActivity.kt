package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import com.github.digitallyrefined.androidipcamera.helpers.CameraResolutionHelper
import com.github.digitallyrefined.androidipcamera.helpers.StreamingServerHelper
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.CertificateHelper
import com.github.digitallyrefined.androidipcamera.helpers.convertNV21toJPEG
import com.github.digitallyrefined.androidipcamera.helpers.convertYUV420toNV21
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var streamingServerHelper: StreamingServerHelper? = null
    private var hasRequestedPermissions = false
    private var cameraResolutionHelper: CameraResolutionHelper? = null
    private var lastFrameTime = 0L

    private val cameraRestartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.github.digitallyrefined.androidipcamera.RESTART_CAMERA") {
                // Restart camera with new settings
                if (allPermissionsGranted()) {
                    startCamera()
                }
            }
        }
    }

    private fun processImage(image: ImageProxy) {
        // Get delay from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L

        // Check if enough time has passed since last frame
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < delay) {
            image.close()
            return
        }
        lastFrameTime = currentTime

        // Convert YUV_420_888 to NV21
        val nv21 = convertYUV420toNV21(image)

        // Convert NV21 to JPEG
        var jpegBytes = convertNV21toJPEG(nv21, image.width, image.height)

        // Apply scaling if needed
        val scaleFactor = prefs.getString("stream_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        if (scaleFactor != 1.0f) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    val newWidth = (bitmap.width * scaleFactor).toInt()
                    val newHeight = (bitmap.height * scaleFactor).toInt()

                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    bitmap.recycle()

                    // Convert back to JPEG bytes
                    val outputStream = java.io.ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    jpegBytes = outputStream.toByteArray()
                    scaledBitmap.recycle()
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scaling image: ${e.message}")
                // Continue with original image if scaling fails
            }
        }

        streamingServerHelper?.getClients()?.let { clients ->
            val toRemove = mutableListOf<StreamingServerHelper.Client>()
            clients.forEach { client ->
                try {
                    // Send MJPEG frame
                    client.writer.print("--frame\r\n")
                    client.writer.print("Content-Type: image/jpeg\r\n")
                    client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                    client.writer.flush()
                    client.outputStream.write(jpegBytes)
                    client.outputStream.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending frame: ${e.message}")
                    try {
                        client.socket.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing client: ${e.message}")
                    }
                    toRemove.add(client)
                }
            }
            toRemove.forEach { streamingServerHelper?.removeClient(it) }
        }
    }

    private fun startStreamingServer() {
        try {
            // Personal mode: App comes with default certificate and credentials
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val certificatePath = prefs.getString("certificate_path", null)

            // Check for valid authentication credentials
            val secureStorage = SecureStorage(this)
            val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
            val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""

            // Validate stored credentials
            val username = InputValidator.validateAndSanitizeUsername(rawUsername)
            val password = InputValidator.validateAndSanitizePassword(rawPassword)

            // SECURITY: Warn user if no valid credentials are configured
            if ((username == null || password == null || username.isEmpty() || password.isEmpty()) && certificatePath == null) {
                runOnUiThread {
                    Toast.makeText(this, "SECURITY WARNING: No authentication credentials configured. All connections will be rejected until you set username/password in Settings.", Toast.LENGTH_LONG).show()
                }
            }

            // Create secure HTTPS server (certificate will be loaded from assets if needed)
            if (streamingServerHelper == null) {
                streamingServerHelper = StreamingServerHelper(
                    this,
                    onLog = { message -> Log.i(TAG, "StreamingServer: $message") }
                )
            }
            streamingServerHelper?.startStreamingServer()

            Log.i(TAG, "Requested HTTPS server start on port $STREAM_PORT")

        } catch (e: IOException) {
            Log.e(TAG, "Could not start secure server: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Failed to start secure server: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun generateRandomPassword(): String {
        // Generate a secure random password that meets validation requirements:
        // - 8-128 characters
        // - At least one uppercase letter
        // - At least one lowercase letter
        // - At least one digit
        val random = SecureRandom()
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val digits = "0123456789"
        val allChars = uppercase + lowercase + digits

        // Ensure we have at least one of each required character type
        val password = StringBuilder().apply {
            append(uppercase[random.nextInt(uppercase.length)]) // At least one uppercase
            append(lowercase[random.nextInt(lowercase.length)]) // At least one lowercase
            append(digits[random.nextInt(digits.length)])       // At least one digit
            // Add 9 more random characters for a total of 12 characters
            repeat(9) {
                append(allChars[random.nextInt(allChars.length)])
            }
        }

        // Shuffle the password to randomize the position of required characters
        val passwordArray = password.toString().toCharArray()
        for (i in passwordArray.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = passwordArray[i]
            passwordArray[i] = passwordArray[j]
            passwordArray[j] = temp
        }

        return String(passwordArray)
    }

    private fun initializeDefaultCertificateAndStartServer() {
        val secureStorage = SecureStorage(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Check if certificate already exists
        if (CertificateHelper.certificateExists(this)) {
            // Certificate exists, check if password is set
            val existingCertPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)

            if (existingCertPassword.isNullOrEmpty()) {
                // Certificate exists but password not set - we need to regenerate the certificate
                // because we can't recover the original password
                // Delete the old certificate file
                val certFile = File(filesDir, "personal_certificate.p12")
                if (certFile.exists()) {
                    certFile.delete()
                }
                // Fall through to certificate generation
            } else {
                // Certificate exists, start server immediately
                streamingServerHelper = StreamingServerHelper(
                    this,
                    onLog = { message -> Log.i(TAG, "StreamingServer: $message") }
                )
                startStreamingServer()
                return
            }
        }


        // No certificate exists - generate one, then start server
        val randomPassword = generateRandomPassword()

        // Generate certificate in background, then start server
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@MainActivity, randomPassword)

            if (certFile != null) {
                // Store the password
                secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, randomPassword)

                // Ensure certificate_path is null (use default personal certificate)
                prefs.edit().remove("certificate_path").apply()

                // Small delay to ensure storage is committed
                kotlinx.coroutines.delay(100)

                // Start server now that certificate is ready
                if (streamingServerHelper == null) {
                    streamingServerHelper = StreamingServerHelper(
                        this@MainActivity,
                        onLog = { message -> Log.i(TAG, "StreamingServer: $message") }
                    )
                }
                startStreamingServer()

                // Show toast on main thread
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Certificate generated. Configure settings if needed.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.e(TAG, "Failed to generate certificate")
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to generate certificate. Check logs.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize view binding first
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Hide the action bar
        supportActionBar?.hide()

        // Set full screen flags
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


        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions before starting camera
        if (!allPermissionsGranted() && !hasRequestedPermissions) {
            hasRequestedPermissions = true
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else if (allPermissionsGranted()) {
            startCamera()
        } else {
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Register broadcast receiver for camera restarts
        ContextCompat.registerReceiver(this, cameraRestartReceiver, IntentFilter("com.github.digitallyrefined.androidipcamera.RESTART_CAMERA"), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Initialize default certificate if not already created, then start server
        initializeDefaultCertificateAndStartServer()

        // Find the TextView
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)

        // Get and display the IP address
        val ipAddress = getLocalIpAddress()
        ipAddressText.text = "https://$ipAddress:$STREAM_PORT"

        // Add toggle preview button
        findViewById<ImageButton>(R.id.hidePreviewButton).setOnClickListener {
            hidePreview()
        }

        // Add switch camera button handler
        findViewById<ImageButton>(R.id.switchCameraButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            // Reset resolution helper to detect new camera's resolutions
            cameraResolutionHelper = null
            startCamera()
        }

        // Add settings button
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // Add this method to handle permission results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                // Show which permissions are missing
                REQUIRED_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(baseContext, it) != PackageManager.PERMISSION_GRANTED
                }
                Toast.makeText(this,
                    "Please allow camera permissions",
                    Toast.LENGTH_LONG).show()
            }
        }
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

    private fun hidePreview() {
        val viewFinder = viewBinding.viewFinder
        val rootView = viewBinding.root
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val switchCameraButton = findViewById<ImageButton>(R.id.switchCameraButton)
        val hidePreviewButton = findViewById<ImageButton>(R.id.hidePreviewButton)

        if (viewFinder.isVisible) {
            viewFinder.visibility = View.GONE
            ipAddressText.visibility = View.GONE
            settingsButton.visibility = View.GONE
            switchCameraButton.visibility = View.GONE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_slideshow) // use open eye as placeholder for closed eye
        } else {
            viewFinder.visibility = View.VISIBLE
            ipAddressText.visibility = View.VISIBLE
            settingsButton.visibility = View.VISIBLE
            switchCameraButton.visibility = View.VISIBLE
            rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_view) // open eye
        }
    }

    private fun calculateZoomCropRegion(cameraId: String, zoomFactor: Float): android.graphics.Rect? {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null

            val centerX = sensorSize.width() / 2
            val centerY = sensorSize.height() / 2
            val deltaX = (sensorSize.width() / (2 * zoomFactor)).toInt()
            val deltaY = (sensorSize.height() / (2 * zoomFactor)).toInt()

            return android.graphics.Rect(
                centerX - deltaX,
                centerY - deltaY,
                centerX + deltaX,
                centerY + deltaY
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating zoom crop region: ${e.message}")
            return null
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // Initialize camera resolution helper if not already done
            if (cameraResolutionHelper == null) {
                cameraResolutionHelper = CameraResolutionHelper(this)
                // Get camera ID based on lens facing
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = when (lensFacing) {
                    CameraSelector.DEFAULT_BACK_CAMERA -> {
                        cameraManager.cameraIdList.find { id ->
                            val characteristics = cameraManager.getCameraCharacteristics(id)
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        } ?: "0"
                    }
                    CameraSelector.DEFAULT_FRONT_CAMERA -> {
                        cameraManager.cameraIdList.find { id ->
                            val characteristics = cameraManager.getCameraCharacteristics(id)
                            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        } ?: "1"
                    }
                    else -> "0"
                }
                cameraResolutionHelper?.initializeResolutions(cameraId)
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .apply {
                    // Get resolution from preferences
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val quality = prefs.getString("camera_resolution", "low") ?: "low"

                                        // Get the appropriate resolution for the selected quality
                    val targetResolution = cameraResolutionHelper?.getResolutionForQuality(quality)

                    if (targetResolution != null) {
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(
                                targetResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            ))
                            .build()
                        setResolutionSelector(resolutionSelector)
                        Log.i(TAG, "Using ${quality} resolution: ${targetResolution.width}x${targetResolution.height}")
                    } else {
                        // Fallback to hardcoded resolutions if detection fails
                        Log.w(TAG, "No resolution found for quality: $quality, using fallback resolutions")
                        val fallbackResolution = when (quality) {
                            "high" -> Size(1280, 720)
                            "medium" -> Size(960, 720)
                            "low" -> Size(800, 600)
                            else -> Size(800, 600)
                        }
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(
                                fallbackResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            ))
                            .build()
                        setResolutionSelector(resolutionSelector)
                        Log.i(TAG, "Using fallback ${quality} resolution: ${fallbackResolution.width}x${fallbackResolution.height}")
                    }
                }
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        if (streamingServerHelper?.getClients()?.isNotEmpty() == true) {  // Only process if there are clients
                            processImage(image)
                        }
                        image.close()
                    }
                }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    preview,
                    imageAnalyzer
                )

                // Apply zoom settings to camera
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                val zoomFactor = prefs.getString("camera_zoom", "1.0")?.toFloatOrNull() ?: 1.0f

                if (zoomFactor != 1.0f) {
                    val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                    val cameraId = when (lensFacing) {
                        CameraSelector.DEFAULT_BACK_CAMERA -> {
                            cameraManager.cameraIdList.find { id ->
                                val characteristics = cameraManager.getCameraCharacteristics(id)
                                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                            } ?: "0"
                        }
                        CameraSelector.DEFAULT_FRONT_CAMERA -> {
                            cameraManager.cameraIdList.find { id ->
                                val characteristics = cameraManager.getCameraCharacteristics(id)
                                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                            } ?: "1"
                        }
                        else -> "0"
                    }

                    val cropRegion = calculateZoomCropRegion(cameraId, zoomFactor)
                    if (cropRegion != null) {
                        camera.cameraControl.setZoomRatio(zoomFactor)
                        Log.i(TAG, "Applied zoom factor: ${zoomFactor}x")
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPause() {
        super.onPause()
        // Stop streaming server when activity is paused (run on background thread to avoid NetworkOnMainThreadException)
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart streaming server when activity resumes (e.g., returning from Settings)
        if (allPermissionsGranted()) {
            startStreamingServer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // Stop streaming server (run on background thread to avoid NetworkOnMainThreadException)
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
        }
        unregisterReceiver(cameraRestartReceiver)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_PORT = 4444
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val MAX_CLIENTS = 3  // Limit concurrent connections
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
}


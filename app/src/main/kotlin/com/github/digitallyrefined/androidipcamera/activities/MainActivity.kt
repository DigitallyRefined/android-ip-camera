package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import com.github.digitallyrefined.androidipcamera.helpers.convertNV21toJPEG
import com.github.digitallyrefined.androidipcamera.helpers.convertYUV420toNV21
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
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
        val jpegBytes = convertNV21toJPEG(nv21, image.width, image.height)

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
            // Get certificate path from preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val useCertificate = prefs.getBoolean("use_certificate", false)
            val certificatePath = if (useCertificate) prefs.getString("certificate_path", null) else null
            val certificatePassword = if (useCertificate) {
                prefs.getString("certificate_password", "")?.let {
                    if (it.isEmpty()) null else it.toCharArray()
                }
            } else null

            // Create server socket with specific bind address
            streamingServerHelper = StreamingServerHelper(this)
            streamingServerHelper?.startStreamingServer()

            Log.i(TAG, "Server started on port $STREAM_PORT (${if (certificatePath != null) "HTTPS" else "HTTP"})")

        } catch (e: IOException) {
            Log.e(TAG, "Could not start server: ${e.message}")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
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

        // Start streaming server
        streamingServerHelper = StreamingServerHelper(this)
        lifecycleScope.launch(Dispatchers.IO) { streamingServerHelper?.startStreamingServer() }

        // Find the TextView
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)

        // Get and display the IP address with correct protocol
        val ipAddress = getLocalIpAddress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useCertificate = prefs.getBoolean("use_certificate", false)
        val protocol = if (useCertificate) "https" else "http"
        ipAddressText.text = "$protocol://$ipAddress:$STREAM_PORT"

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
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_view) // use open eye as placeholder for closed eye
        } else {
            viewFinder.visibility = View.VISIBLE
            ipAddressText.visibility = View.VISIBLE
            rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            hidePreviewButton.setImageResource(android.R.drawable.ic_menu_view) // open eye
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
                cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        streamingServerHelper?.closeClientConnection()
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


package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.digitallyrefined.androidipcamera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import android.view.WindowManager
import android.content.Intent
import java.net.Inet4Address
import java.net.NetworkInterface
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Base64
import android.util.Size
import android.net.Uri
import androidx.preference.PreferenceManager
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var serverSocket: ServerSocket? = null
    private var clients = mutableListOf<Client>()
    private var hasRequestedPermissions = false

    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter
    )

    private var lastFrameTime = 0L

    private fun processImage(image: ImageProxy) {
        // Get delay from preferences
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L

        // Check if enough time has passed since last frame
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < delay) {
            image.close()
            return
        }
        lastFrameTime = currentTime

        // Convert YUV_420_888 to NV21
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // Convert NV21 to JPEG
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val jpegStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, jpegStream)
        val jpegBytes = jpegStream.toByteArray()

        synchronized(clients) {
            clients.removeAll { client ->
                try {
                    // Send MJPEG frame
                    client.writer.print("--frame\r\n")
                    client.writer.print("Content-Type: image/jpeg\r\n")
                    client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                    client.writer.flush()
                    client.outputStream.write(jpegBytes)
                    client.outputStream.flush()
                    false
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending frame: ${e.message}")
                    try {
                        client.socket.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing client: ${e.message}")
                    }
                    true
                }
            }
        }
    }

    private fun handleMaxClients(socket: Socket): Boolean {
        synchronized(clients) {
            if (clients.size >= MAX_CLIENTS) {
                socket.getOutputStream().writer().use { writer ->
                    writer.write("HTTP/1.1 503 Service Unavailable\r\n\r\n")
                    writer.flush()
                }
                socket.close()
                return true
            }
        }
        return false
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
            serverSocket = if (certificatePath != null) {
                // SSL server socket creation code...
                try {
                    val uri = Uri.parse(certificatePath)
                    // Copy the certificate to app's private storage
                    val privateFile = File(filesDir, "certificate.p12")
                    try {
                        // Delete existing certificate if it exists
                        if (privateFile.exists()) {
                            privateFile.delete()
                        }

                        // Copy new certificate
                        contentResolver.openInputStream(uri)?.use { input ->
                            privateFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Failed to open certificate file")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy certificate: ${e.message}")
                        throw e
                    }

                    // Use the private copy of the certificate
                    privateFile.inputStream().use { inputStream ->
                        val keyStore = KeyStore.getInstance("PKCS12")
                        keyStore.load(inputStream, certificatePassword)

                        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        keyManagerFactory.init(keyStore, certificatePassword)

                        val sslContext = SSLContext.getInstance("TLSv1.2")  // Specify TLS version
                        sslContext.init(keyManagerFactory.keyManagers, null, null)

                        val sslServerSocketFactory = sslContext.serverSocketFactory
                        (sslServerSocketFactory.createServerSocket(STREAM_PORT, 50, null) as SSLServerSocket).apply {
                            enabledProtocols = arrayOf("TLSv1.2")
                            enabledCipherSuites = supportedCipherSuites
                            reuseAddress = true
                            soTimeout = 30000  // 30 seconds timeout
                        }
                    } ?: ServerSocket(STREAM_PORT)  // Fallback if inputStream is null
                } catch (e: Exception) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Log.e(TAG, "Failed to create SSL server socket: ${e.message}")
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to create SSL server socket: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    ServerSocket(STREAM_PORT) // Fallback to regular socket
                }
            } else {
                ServerSocket(STREAM_PORT, 50, null).apply {
                    reuseAddress = true
                    soTimeout = 30000
                }
            }

            Log.i(TAG, "Server started on port $STREAM_PORT (${if (certificatePath != null) "HTTPS" else "HTTP"})")

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val socket = serverSocket?.accept() ?: continue

                    if (handleMaxClients(socket)) {
                        continue
                    }

                    val outputStream = socket.getOutputStream()
                    val writer = PrintWriter(outputStream, true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // Get auth credentials from preferences using androidx.preference
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    val username = prefs.getString("username", "") ?: ""
                    val password = prefs.getString("password", "") ?: ""

                    // Check authentication if credentials are set
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        // Read all headers
                        val headers = mutableListOf<String>()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line.isNullOrEmpty()) break
                            headers.add(line!!)
                        }

                        // Look for auth header
                        val authHeader = headers.find { it.startsWith("Authorization: Basic ") }

                        if (authHeader == null) {
                            // No auth provided, send 401
                            writer.print("HTTP/1.1 401 Unauthorized\r\n")
                            writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                            writer.print("Connection: close\r\n\r\n")
                            writer.flush()
                            socket.close()
                            continue
                        }

                        val providedAuth = String(Base64.decode(
                            authHeader.substring(21), Base64.DEFAULT))
                        if (providedAuth != "$username:$password") {
                            // Wrong credentials
                            writer.print("HTTP/1.1 401 Unauthorized\r\n\r\n")
                            writer.flush()
                            socket.close()
                            continue
                        }
                    }

                    // Send HTTP headers for video stream
                    writer.print("HTTP/1.0 200 OK\r\n")
                    writer.print("Connection: close\r\n")
                    writer.print("Cache-Control: no-cache\r\n")
                    writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                    writer.flush()

                    synchronized(clients) {
                        clients.add(Client(socket, outputStream, writer))
                    }
                    Log.i(TAG, "Client connected")

                    // Get delay from preferences
                    val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
                    Thread.sleep(delay)
                } catch (e: IOException) {
                  // Ignore
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not start server: ${e.message}")
        }
    }

    private fun closeClientConnection() {
        synchronized(clients) {
            clients.forEach { client ->
                try {
                    client.socket.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client connection: ${e.message}")
                }
            }
            clients.clear()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize view binding first
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Hide the action bar
        supportActionBar?.hide()

        // Set full screen flags
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

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
        lifecycleScope.launch(Dispatchers.IO) {
            startStreamingServer()
        }

        // Find the TextView
        val ipAddressText = findViewById<TextView>(R.id.ipAddressText)

        // Get and display the IP address with correct protocol
        val ipAddress = getLocalIpAddress()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val useCertificate = prefs.getBoolean("use_certificate", false)
        val protocol = if (useCertificate) "https" else "http"
        ipAddressText.text = "$protocol://$ipAddress:$STREAM_PORT"

        // Add toggle preview button
        findViewById<Button>(R.id.hidePreviewButton).setOnClickListener {
            hidePreview()
        }

        // Add switch camera button handler
        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera()
        }

        // Add settings button
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
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
                val missingPermissions = REQUIRED_PERMISSIONS.filter {
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
        val settingsButton = findViewById<Button>(R.id.settingsButton)
        val switchCameraButton = findViewById<TextView>(R.id.switchCameraButton)
        val hidePreviewButton = findViewById<Button>(R.id.hidePreviewButton)

        if (viewFinder.visibility == View.VISIBLE) {
            viewFinder.visibility = View.GONE
            ipAddressText.visibility = View.GONE
            settingsButton.visibility = View.GONE
            switchCameraButton.visibility = View.GONE
            hidePreviewButton.visibility = View.GONE
            rootView.setBackgroundColor(android.graphics.Color.BLACK)
        } else {
            viewFinder.visibility = View.VISIBLE
            ipAddressText.visibility = View.VISIBLE
            settingsButton.visibility = View.VISIBLE
            switchCameraButton.visibility = View.VISIBLE
            hidePreviewButton.visibility = View.VISIBLE
            rootView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .apply {
                    // Get resolution from preferences
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val resolutionSelector = ResolutionSelector.Builder().apply {
                        when (prefs.getString("camera_resolution", "low")) {
                            "high" -> setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            "medium" -> setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(960, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            // "low" -> don't set resolution, use CameraX default
                        }
                    }.build()
                    setResolutionSelector(resolutionSelector)
                }
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        if (clients.isNotEmpty()) {  // Only process if there are clients
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
        serverSocket?.close()
        closeClientConnection()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STREAM_PORT = 4444
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val MAX_CLIENTS = 3  // Limit concurrent connections
        private val REQUIRED_PERMISSIONS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }
}

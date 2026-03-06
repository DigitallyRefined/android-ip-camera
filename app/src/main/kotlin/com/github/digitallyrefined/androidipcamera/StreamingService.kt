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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
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
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {

    private val binder = LocalBinder()
    var streamingServerHelper: StreamingServerHelper? = null
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var lastFrameTime = 0L
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraResolutionHelper: CameraResolutionHelper? = null

    // UI Callbacks
    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onCameraRestartNeeded: (() -> Unit)? = null

    // Preview surface provider
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private val notificationChannelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (intent?.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED) {
                    val channelId = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID)
                    if (channelId == CHANNEL_ID) {
                        val manager = getSystemService(NotificationManager::class.java)
                        val channel = manager.getNotificationChannel(CHANNEL_ID)
                        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                            Log.w(TAG, "Notification channel $channelId blocked by user. Stopping service.")
                            handleStopService()
                        }
                    }
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
        private const val CAMERA_FACING_BACK = "back"
        private const val CAMERA_FACING_FRONT = "front"
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopService()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_RESTART_NOTIFICATION) {
            startForegroundService()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleStopService() {
        val closeIntent = Intent("com.github.digitallyrefined.androidipcamera.CLOSE_APP")
        closeIntent.setPackage(packageName) // Ensure only our app receives this
        sendBroadcast(closeIntent)

        stopForeground(true)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startForegroundService()

        // Load saved camera facing
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        lensFacing = if (prefs.getString(PREF_LAST_CAMERA_FACING, CAMERA_FACING_BACK) == CAMERA_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val filter = IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            registerReceiver(notificationChannelReceiver, filter)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startNotificationChannelCheckFallback()
        }
    }

    private fun startNotificationChannelCheckFallback() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(TAG, "Notification channel $CHANNEL_ID blocked (fallback check). Stopping service.")
                    handleStopService()
                    break
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                unregisterReceiver(notificationChannelReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering notification receiver: ${e.message}")
            }
        }
        cameraExecutor?.shutdown()
        stopCamera()
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val restartIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_RESTART_NOTIFICATION
        }
        val restartPendingIntent = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android IP Camera Streaming")
            .setContentText("Camera server is running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "Exit App", stopPendingIntent)
            .setDeleteIntent(restartPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        if (isCameraRunning() && surfaceProvider != null) {
            // Re-bind to include preview if needed, or update preview
            // CameraX is tricky with dynamic surface updates.
            // Usually we just need to restart camera to attach new preview.
            startCamera()
        } else if (isCameraRunning() && surfaceProvider == null) {
            // If surface is null (backgrounded), we might want to unbind preview to save resources,
            // or just let it be (CameraX handles detached surfaces).
            // However, to be safe and ensure background streaming works, we should keep the camera running
            // but maybe without the Preview use case if it causes issues.
            // For now, let's just restart camera without preview if surface is null?
            // Actually, if we just unbindAll and rebind with/without Preview, that works.
            startCamera()
        }
    }

    fun isCameraRunning(): Boolean {
        return camera != null
    }

    fun getLocalIpAddress(): String {
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

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString(
                PREF_LAST_CAMERA_FACING,
                if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) CAMERA_FACING_FRONT else CAMERA_FACING_BACK
            )
            .apply()
        cameraResolutionHelper = null
        if (streamingServerHelper?.getClients()?.isNotEmpty() == true) {
            startCamera()
        }
    }

    fun startStreamingServer() {
        try {
            // Initialize Default Certificate Logic
            val secureStorage = SecureStorage(this)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)

            if (CertificateHelper.certificateExists(this)) {
                val existingCertPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
                if (existingCertPassword.isNullOrEmpty()) {
                    val certFile = File(filesDir, "personal_certificate.p12")
                    if (certFile.exists()) certFile.delete()
                    generateCertificateAndStart()
                } else {
                    initServer()
                }
            } else {
                generateCertificateAndStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server: ${e.message}")
        }
    }

    private fun generateCertificateAndStart() {
        val randomPassword = generateRandomPassword()
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@StreamingService, randomPassword)
            if (certFile != null) {
                val secureStorage = SecureStorage(this@StreamingService)
                secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, randomPassword)
                PreferenceManager.getDefaultSharedPreferences(this@StreamingService).edit().remove("certificate_path").apply()
                kotlinx.coroutines.delay(100)
                initServer()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@StreamingService, "Certificate generated", Toast.LENGTH_SHORT).show()
                }
            } else {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@StreamingService, "Failed to generate certificate", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initServer() {
        if (streamingServerHelper == null) {
            streamingServerHelper = StreamingServerHelper(
                this,
                onLog = { message ->
                    Log.i(TAG, "StreamingServer: $message")
                    onLog?.invoke(message)
                },
                onClientConnected = {
                    launchMain {
                        onClientConnected?.invoke()
                        startCameraIfNeeded()
                    }
                },
                onClientDisconnected = {
                    if (streamingServerHelper?.getClients()?.isEmpty() == true) {
                        launchMain {
                            stopCamera()
                            onClientDisconnected?.invoke()
                        }
                    }
                },
                onControlCommand = { key: String, value: String -> handleRemoteControl(key, value) }
            )
        }
        streamingServerHelper?.startStreamingServer()
        Log.i(TAG, "Requested HTTPS server start on port $STREAM_PORT")
    }

    private fun launchMain(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) {
            block()
        }
    }

    private fun startCameraIfNeeded() {
        if (!allPermissionsGranted() || isCameraRunning()) return
        startCamera()
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalyzer = null
        camera = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            this.cameraProvider = cameraProvider

            // Initialize camera resolution helper if not already done
            if (cameraResolutionHelper == null) {
                cameraResolutionHelper = CameraResolutionHelper(this)
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = getCameraId(cameraManager)
                cameraResolutionHelper?.initializeResolutions(cameraId)
            }

            // Image Analysis (Streaming)
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .apply {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@StreamingService)
                    val quality = prefs.getString("camera_resolution", "low") ?: "low"
                    val targetResolution = cameraResolutionHelper?.getResolutionForQuality(quality)

                    if (targetResolution != null) {
                        setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(targetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build()
                        )
                    } else {
                        // Fallback
                         val fallbackResolution = when (quality) {
                            "high" -> Size(1280, 720)
                            "medium" -> Size(960, 720)
                            "low" -> Size(800, 600)
                            else -> Size(800, 600)
                        }
                        setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(fallbackResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build()
                        )
                    }
                }
                .build()
                .also { analysis ->
                    cameraExecutor?.let { executor ->
                        analysis.setAnalyzer(executor) { image ->
                            if (streamingServerHelper?.getClients()?.isNotEmpty() == true) {
                                processImage(image)
                            }
                            image.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()

                // Build Use Cases
                val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalyzer!!)

                // Add Preview if surface provider is available
                if (currentSurfaceProvider != null) {
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(currentSurfaceProvider)
                    useCases.add(preview)
                }

                // Bind to Service Lifecycle
                camera = cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    *useCases.toTypedArray()
                )

                // Apply initial settings (Torch, Zoom, etc)
                applyCameraSettings()

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCameraId(cameraManager: CameraManager): String {
        return when (lensFacing) {
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
    }

    private fun applyCameraSettings() {
        val cam = camera ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Torch
        try {
            val torchPref = prefs.getString("camera_torch", "off") ?: "off"
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(torchPref == "on")
            }
        } catch (e: Exception) { Log.w(TAG, "Torch error: ${e.message}") }

        // Zoom
        val requestedZoomFactor = prefs.getString("camera_zoom", "1.0")?.toFloatOrNull() ?: 1.0f
        cam.cameraControl.setZoomRatio(requestedZoomFactor)

        // Exposure
        val exposureValue = prefs.getString("camera_exposure", "0")?.toIntOrNull() ?: 0
        cam.cameraControl.setExposureCompensationIndex(exposureValue)
    }

    private fun handleRemoteControl(key: String, value: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val cam = camera

        when (key) {
            "torch" -> {
                val current = prefs.getString("camera_torch", "off") ?: "off"
                val next = when (value.lowercase()) {
                    "on" -> "on"
                    "off" -> "off"
                    "toggle" -> if (current == "on") "off" else "on"
                    else -> return
                }
                prefs.edit().putString("camera_torch", next).apply()
                launchMain {
                    try {
                        if (cam?.cameraInfo?.hasFlashUnit() == true) {
                            cam.cameraControl.enableTorch(next == "on")
                        }
                    } catch (e: Exception) {}
                }
            }
            "zoom" -> {
                val zoomFactor = value.toFloatOrNull() ?: return
                prefs.edit().putString("camera_zoom", zoomFactor.toString()).apply()
                launchMain { cam?.cameraControl?.setZoomRatio(zoomFactor) }
            }
            "exposure" -> {
                val exposure = value.toIntOrNull() ?: return
                prefs.edit().putString("camera_exposure", exposure.toString()).apply()
                launchMain { cam?.cameraControl?.setExposureCompensationIndex(exposure) }
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return
                prefs.edit().putString("camera_contrast", contrast.toString()).apply()
                Log.i(TAG, "Remote Control: Contrast set to $contrast (software-based)")
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high")) {
                    prefs.edit().putString("camera_resolution", value).apply()
                    launchMain { startCamera() } // Restart
                }
            }
            "camera" -> {
                 switchCamera()
            }
            // Other settings like scale/delay/rotate are handled in processImage
            "scale" -> {
                val scale = value.toFloatOrNull() ?: return
                if (scale in 0.5f..2.0f) prefs.edit().putString("stream_scale", value).apply()
            }
            "delay" -> {
                val delay = value.toLongOrNull() ?: return
                if (delay in 10L..1000L) prefs.edit().putString("stream_delay", value).apply()
            }
            "rotate" -> {
                val currentRotation = prefs.getInt("camera_manual_rotate", 0)
                val nextRotation = (currentRotation + 90) % 360
                prefs.edit().putInt("camera_manual_rotate", nextRotation).apply()
            }
        }
    }

    private fun processImage(image: ImageProxy) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < delay) {
            image.close()
            return
        }
        lastFrameTime = currentTime

        val autoRotation = image.imageInfo.rotationDegrees
        val manualRotation = prefs.getInt("camera_manual_rotate", 0)
        val totalRotation = (autoRotation + manualRotation) % 360
        val scaleFactor = prefs.getString("stream_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val contrastValue = prefs.getString("camera_contrast", "0")?.toIntOrNull() ?: 0

        // Convert YUV_420_888 to NV21
        val nv21 = convertYUV420toNV21(image)

        // Convert NV21 to JPEG
        var jpegBytes = convertNV21toJPEG(nv21, image.width, image.height)

        // Apply transformations if needed (Rotation, Scaling, Contrast)
        if (totalRotation != 0 || scaleFactor != 1.0f || contrastValue != 0) {
            try {
                var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    val matrix = Matrix()

                    // Apply Rotation
                    if (totalRotation != 0) {
                        matrix.postRotate(totalRotation.toFloat())
                    }

                    // Apply Scaling
                    if (scaleFactor != 1.0f) {
                        matrix.postScale(scaleFactor, scaleFactor)
                    }

                    // Create new bitmap with rotation and scaling applied
                    val transformedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )

                    if (transformedBitmap != bitmap) {
                        bitmap.recycle()
                        bitmap = transformedBitmap
                    }

                    // Apply Contrast if needed
                    if (contrastValue != 0) {
                        val contrastFactor = 1.0f + (contrastValue / 100.0f)

                        val contrastColorMatrix = android.graphics.ColorMatrix().apply {
                            set(floatArrayOf(
                            contrastFactor, 0f, 0f, 0f, 0f,  // Red
                            0f, contrastFactor, 0f, 0f, 0f,  // Green
                            0f, 0f, contrastFactor, 0f, 0f,  // Blue
                            0f, 0f, 0f, 1f, 0f               // Alpha
                            ))
                        }

                        val paint = android.graphics.Paint().apply {
                            colorFilter = android.graphics.ColorMatrixColorFilter(contrastColorMatrix)
                        }

                        val contrastedBitmap = Bitmap.createBitmap(
                            bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(contrastedBitmap)
                        canvas.drawBitmap(bitmap, 0f, 0f, paint)

                        bitmap.recycle()
                        bitmap = contrastedBitmap
                    }

                    // Convert back to JPEG bytes
                    val outputStream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    jpegBytes = outputStream.toByteArray()
                    bitmap.recycle()
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error transforming image: ${e.message}")
                // Continue with original image if transforming image fails
            }
        }

        streamingServerHelper?.getClients()?.let { clients ->
            val toRemove = mutableListOf<StreamingServerHelper.Client>()
            clients.forEach { client ->
                try {
                    client.writer.print("--frame\r\n")
                    client.writer.print("Content-Type: image/jpeg\r\n")
                    client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                    client.writer.flush()
                    client.outputStream.write(jpegBytes)
                    client.outputStream.flush()
                } catch (e: IOException) {
                    try { client.socket.close() } catch (_: Exception) {}
                    toRemove.add(client)
                }
            }
            toRemove.forEach { streamingServerHelper?.removeClient(it) }
        }
    }

    private fun generateRandomPassword(): String {
        val random = SecureRandom()
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val digits = "0123456789"
        val allChars = uppercase + lowercase + digits
        val password = StringBuilder().apply {
            append(uppercase[random.nextInt(uppercase.length)])
            append(lowercase[random.nextInt(lowercase.length)])
            append(digits[random.nextInt(digits.length)])
            repeat(9) { append(allChars[random.nextInt(allChars.length)]) }
        }
        val passwordArray = password.toString().toCharArray()
        for (i in passwordArray.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = passwordArray[i]
            passwordArray[i] = passwordArray[j]
            passwordArray[j] = temp
        }
        return String(passwordArray)
    }

    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}

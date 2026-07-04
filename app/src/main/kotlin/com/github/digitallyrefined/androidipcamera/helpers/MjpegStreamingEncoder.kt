package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * MJPEG streaming encoder implementation.
 * Handles JPEG compression, image transformations, and MJPEG streaming to clients.
 */
class MjpegStreamingEncoder(
    private val context: Context,
    private var streamingServerHelper: StreamingServerHelper?,
    private val onLog: (String) -> Unit = {}
) : StreamingEncoder {
    companion object {
        private const val TAG = "MjpegStreamingEncoder"
    }

    private var lastFrameTime = 0L
    private var captureRunning = false

    override fun processFrame(image: ImageProxy) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastFrameTime < delay) return
        lastFrameTime = currentTime

        val autoRotation = image.imageInfo.rotationDegrees
        val rotation = prefs.getInt("camera_rotate", 0)
        val totalRotation = (autoRotation + rotation) % 360
        val scaleFactor = prefs.getString("stream_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val contrastValue = prefs.getString("camera_contrast", "0")?.toIntOrNull() ?: 0

        val nv21 = convertYUV420toNV21(image)
        var jpegBytes = convertNV21toJPEG(nv21, image.width, image.height)
        if (totalRotation != 0 || scaleFactor != 1.0f || contrastValue != 0) {
            jpegBytes = applyTransformations(jpegBytes, totalRotation, scaleFactor, contrastValue)
        }
        broadcastJpeg(jpegBytes)
    }

    /** Camera1 preview callback path (NV21 bytes, no ImageProxy). */
    fun processNv21Frame(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (!hasClients()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < delay) return
        lastFrameTime = currentTime

        val rotation = prefs.getInt("camera_rotate", 0)
        val totalRotation = (rotationDegrees + rotation) % 360
        val scaleFactor = prefs.getString("stream_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val contrastValue = prefs.getString("camera_contrast", "0")?.toIntOrNull() ?: 0

        var jpegBytes = convertNV21toJPEG(nv21, width, height)
        if (totalRotation != 0 || scaleFactor != 1.0f || contrastValue != 0) {
            jpegBytes = applyTransformations(jpegBytes, totalRotation, scaleFactor, contrastValue)
        }
        broadcastJpeg(jpegBytes)
    }

    // Latest JPEG sent to clients, so a "match stream" snapshot can reuse it without a camera rebind.
    @Volatile private var lastJpeg: ByteArray? = null
    fun lastFrame(): ByteArray? = lastJpeg

    private fun broadcastJpeg(jpegBytes: ByteArray) {
        lastJpeg = jpegBytes
        val helper = streamingServerHelper ?: return
        val clients = helper.getClients()
        if (clients.isEmpty()) return
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
        toRemove.forEach { helper.removeClient(it) }
    }

    override fun handleRemoteControl(key: String, value: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        return when (key) {
            "scale" -> {
                val scale = value.toFloatOrNull() ?: return false
                if (scale in 0.5f..2.0f) {
                    prefs.edit().putString("stream_scale", value).apply()
                    true
                } else {
                    false
                }
            }
            "delay" -> {
                val delay = value.toLongOrNull() ?: return false
                if (delay in 10L..1000L) {
                    prefs.edit().putString("stream_delay", value).apply()
                    true
                } else {
                    false
                }
            }
            "rotate" -> {
                val angle = value.toIntOrNull() ?: return false
                val norm = ((angle % 360) + 360) % 360
                prefs.edit().putInt("camera_rotate", norm).apply()
                true
            }
            "audio_gain" -> {
                val gain = value.toFloatOrNull() ?: return false
                if (gain in 0.5f..3.0f) {
                    prefs.edit().putString("audio_gain", gain.toString()).apply()
                    true
                } else {
                    false
                }
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return false
                prefs.edit().putString("camera_contrast", contrast.toString()).apply()
                Log.i(TAG, "Remote Control: Contrast set to $contrast (software-based)")
                true
            }
            else -> false
        }
    }

    override fun start() {
        captureRunning = true
    }

    override fun stop() {
        captureRunning = false
    }

    override fun hasClients(): Boolean {
        return streamingServerHelper?.getClients()?.isNotEmpty() == true
    }

    /**
     * Apply image transformations (rotation, scaling, contrast) to JPEG bytes.
     */
    private fun applyTransformations(
        jpegBytes: ByteArray,
        rotation: Int,
        scaleFactor: Float,
        contrastValue: Int
    ): ByteArray {
        try {
            var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap != null) {
                val matrix = Matrix()

                // Apply Rotation
                if (rotation != 0) {
                    matrix.postRotate(rotation.toFloat())
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
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val result = outputStream.toByteArray()
                bitmap.recycle()
                outputStream.close()
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transforming image: ${e.message}")
            // Continue with original image if transforming image fails
        }
        return jpegBytes
    }

    /**
     * Check if MJPEG encoder is running.
     */
    fun isRunning(): Boolean = captureRunning

    /**
     * Update the streaming server helper reference (called after server initialization).
     */
    fun updateServerHelper(helper: StreamingServerHelper) {
        streamingServerHelper = helper
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    private val writerQueueCapacity = DeviceMemoryHelper.mjpegWriterQueueCapacity(context)
    private val writerGeneration = AtomicLong()
    private val networkWriter = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(writerQueueCapacity),
        { runnable -> Thread(runnable, "MjpegNetworkWriter").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null

    private fun invalidatePendingWrites() {
        writerGeneration.incrementAndGet()
        networkWriter.queue.clear()
    }

    private fun prefStringWithFallback(prefs: android.content.SharedPreferences, vararg keys: String): String? {
        for (k in keys) {
            if (prefs.contains(k)) return prefs.getString(k, null)
        }
        return null
    }

    private var lastFrameTime = 0L
    private var captureRunning = false

    override fun processFrame(image: ImageProxy) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
            val fpsCoerced = fps.coerceIn(1, 60)
            val delay = try { 1000L / fpsCoerced } catch (_: Exception) { 33L }
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastFrameTime < delay) return
            lastFrameTime = currentTime

            val nv21 = convertYUV420toNV21(image, nv21Buffer, nv21RowBuffer).also {
                nv21Buffer = it
                if (nv21RowBuffer == null || nv21RowBuffer!!.size < image.planes[0].rowStride) {
                    nv21RowBuffer = ByteArray(image.planes[0].rowStride)
                }
            }
            // Grayscale is a YUV-domain operation here: keep every Y sample, neutralise U/V.
            if (prefs.getBoolean(NightMode.PREF_GRAYSCALE, false)) {
                Grayscale.neutralizeYuv420Chroma(nv21, image.cropRect.width(), image.cropRect.height())
            }
            val quality = DeviceMemoryHelper.mjpegJpegQuality(context)
            val jpegBytes = applyStreamTransformations(
                convertNV21toJPEG(nv21, image.width, image.height, quality),
                quality,
            )
            broadcastJpeg(jpegBytes)
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("processFrame")
        } catch (e: Exception) {
            Log.e(TAG, "processFrame: ${e.message}")
        }
    }

    /** Camera1 preview callback path (NV21 bytes, no ImageProxy). */
    fun processNv21Frame(nv21: ByteArray, width: Int, height: Int) {
        if (!hasClients()) return
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
            val fpsCoerced = fps.coerceIn(1, 60)
            val delay = try { 1000L / fpsCoerced } catch (_: Exception) { 33L }
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < delay) return
            lastFrameTime = currentTime

            // Grayscale is a YUV-domain operation here: keep every Y sample, neutralise U/V.
            if (prefs.getBoolean(NightMode.PREF_GRAYSCALE, false)) {
                Grayscale.neutralizeYuv420Chroma(nv21, width, height)
            }
            val quality = DeviceMemoryHelper.mjpegJpegQuality(context)
            val jpegBytes = applyStreamTransformations(
                convertNV21toJPEG(nv21, width, height, quality),
                quality,
            )
            broadcastJpeg(jpegBytes)
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("processNv21Frame")
        } catch (e: Exception) {
            Log.e(TAG, "processNv21Frame: ${e.message}")
        }
    }

    // Latest JPEG sent to clients, so a "match stream" snapshot can reuse it without a camera rebind.
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastJpegAtMs: Long = 0L
    fun lastFrame(): ByteArray? = lastJpeg

    /** Most recent streamed frame, but only if it's no older than [maxAgeMs] (0 = any age). */
    fun lastFrameFresh(maxAgeMs: Long): ByteArray? {
        val jpeg = lastJpeg ?: return null
        val age = System.currentTimeMillis() - lastJpegAtMs
        return if (age in 0..maxAgeMs) jpeg else null
    }

    private fun broadcastJpeg(jpegBytes: ByteArray) {
        lastJpeg = jpegBytes
        lastJpegAtMs = System.currentTimeMillis()
        val helper = streamingServerHelper ?: return
        val clients = helper.getClients()
        if (clients.isEmpty()) return
        val generation = writerGeneration.get()

        if (networkWriter.queue.remainingCapacity() == 0) {
            return
        }

        try {
            networkWriter.execute {
                if (generation != writerGeneration.get()) return@execute
                val toRemove = mutableListOf<StreamingServerHelper.Client>()
                clients.forEach { client ->
                    if (generation != writerGeneration.get()) return@forEach
                    try {
                        client.writer.print("--frame\r\n")
                        client.writer.print("Content-Type: image/jpeg\r\n")
                        client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                        client.writer.flush()
                        client.outputStream.write(jpegBytes)
                        client.outputStream.flush()
                        client.markWriteSuccess()
                    } catch (e: IOException) {
                        try { client.socket.close() } catch (_: Exception) {}
                        toRemove.add(client)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Handle slow network - client is not reading fast enough
                        try { client.socket.close() } catch (_: Exception) {}
                        toRemove.add(client)
                    } catch (e: Exception) {
                        try { client.socket.close() } catch (_: Exception) {}
                        toRemove.add(client)
                    }
                }
                toRemove.forEach { helper.removeClient(it) }
            }
        } catch (_: RejectedExecutionException) {
            // Drop frame if rejected
        }
    }

    override fun handleRemoteControl(key: String, value: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        return when (key) {
            "scale" -> {
                val scale = value.toFloatOrNull() ?: return false
                if (scale in 0.5f..2.0f) {
                    val camId = prefs.getString("camera_id", null)
                    camId?.let {
                        prefs.edit { putString("stream_scale_$it", value) }
                        val phys = it.substringAfter(':', it)
                        if (phys.isNotBlank() && phys != it) prefs.edit { putString("stream_scale_$phys", value) }
                    }
                    true
                } else {
                    false
                }
            }
            "fps" -> {
                val fps = value.toIntOrNull() ?: return false
                if (fps in 1..60) {
                    prefs.edit { putString("stream_fps", value) }
                    true
                } else {
                    false
                }
            }
            "audio_gain" -> {
                val gain = value.toFloatOrNull() ?: return false
                if (gain in 0.5f..3.0f) {
                    prefs.edit { putString("audio_gain", gain.toString()) }
                    true
                } else {
                    false
                }
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return false
                val camId = prefs.getString("camera_id", null)
                camId?.let {
                    prefs.edit { putString("camera_contrast_$it", contrast.toString()) }
                    val phys = it.substringAfter(':', it)
                    if (phys.isNotBlank() && phys != it) prefs.edit { putString("camera_contrast_$phys", contrast.toString()) }
                }
                Log.i(TAG, "Remote Control: Contrast set to $contrast (software-based)")
                true
            }
            "mirror" -> {
                if (value in listOf("true", "false")) {
                    val camId = prefs.getString("camera_id", null)
                    camId?.let {
                        prefs.edit { putString("mirror_$it", value) }
                        val phys = it.substringAfter(':', it)
                        if (phys.isNotBlank() && phys != it) prefs.edit { putString("mirror_$phys", value) }
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    override fun start() {
        captureRunning = true
    }

    override fun stop() {
        captureRunning = false
        invalidatePendingWrites()
    }

    override fun hasClients(): Boolean {
        return streamingServerHelper?.getClients()?.isNotEmpty() == true
    }

    /** Called by [StreamingService] when Android reports low memory. */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_CRITICAL still arrives on older devices (< API 34).
    fun onMemoryPressure(severe: Boolean) {
        invalidatePendingWrites()
        lastJpeg = null
        nv21Buffer = null
        nv21RowBuffer = null
        if (severe) {
            DeviceMemoryHelper.updateMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
    }

    private fun handleOutOfMemory(source: String) {
        Log.e(TAG, "Out of memory in $source")
        onMemoryPressure(severe = true)
        try {
            System.gc()
        } catch (_: Exception) {
        }
    }

    /**
     * Broadcast a ready-made JPEG (night mode stills). Unlike camera frames it has not been through
     * the MJPEG post-processing yet, so apply the same rotate/scale/contrast/mirror/crop settings
     * before sending. [maxWidth]×[maxHeight] caps the result so a full-sensor night photo does not
     * make the stream much larger than the selected resolution.
     */
    fun processJpegFrame(jpeg: ByteArray, maxWidth: Int = 0, maxHeight: Int = 0) {
        if (!hasClients()) return
        try {
            val quality = DeviceMemoryHelper.mjpegJpegQuality(context)
            val grayscale = NightMode.isGrayscaleEnabled(context)
            broadcastJpeg(applyStreamTransformations(jpeg, quality, maxWidth, maxHeight, grayscale))
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("processJpegFrame")
        } catch (e: Exception) {
            Log.e(TAG, "processJpegFrame: ${e.message}")
        }
    }

    private fun applyStreamTransformations(
        jpegBytes: ByteArray,
        jpegQuality: Int,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        grayscale: Boolean = false,
    ): ByteArray {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val camId = prefs.getString("camera_id", null)
        // Rotation is controlled only by the per-camera rotate= setting; the camera's
        // sensor orientation is intentionally ignored.
        val rotation = if (camId != null) {
            val phys = camId.substringAfter(':', camId)
            when {
                prefs.contains("camera_rotate_$camId") -> prefs.getInt("camera_rotate_$camId", 0)
                prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
                else -> 0
            }
        } else 0
        val totalRotation = ((rotation % 360) + 360) % 360
        val scaleFactor = if (camId != null) {
            val phys = camId.substringAfter(':', camId)
            prefStringWithFallback(prefs, "stream_scale_$camId", "stream_scale_$phys")?.toFloatOrNull() ?: 1.0f
        } else 1.0f
        val contrastValue = if (camId != null) {
            val phys = camId.substringAfter(':', camId)
            prefStringWithFallback(prefs, "camera_contrast_$camId", "camera_contrast_$phys")?.toIntOrNull() ?: 0
        } else 0
        val mirror = if (camId != null) {
            val phys = camId.substringAfter(':', camId)
            prefStringWithFallback(prefs, "mirror_$camId", "mirror_$phys")?.toBoolean() ?: false
        } else false
        val crop = CropRegion.read(prefs, camId)
        val needsTransform = totalRotation != 0 || scaleFactor != 1.0f || contrastValue != 0 ||
            mirror || crop != null || grayscale || (maxWidth > 0 && maxHeight > 0)
        return if (needsTransform) {
            applyTransformations(
                jpegBytes, totalRotation, scaleFactor, contrastValue, jpegQuality, mirror, crop,
                maxWidth, maxHeight, grayscale,
            )
        } else {
            jpegBytes
        }
    }

    private fun applyTransformations(
        jpegBytes: ByteArray,
        rotation: Int,
        scaleFactor: Float,
        contrastValue: Int,
        jpegQuality: Int,
        mirror: Boolean = false,
        crop: CropRegion? = null,
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        grayscale: Boolean = false,
    ): ByteArray {
        var bitmap: Bitmap? = null
        try {
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = if (DeviceMemoryHelper.isUnderMemoryPressure()) {
                    Bitmap.Config.RGB_565
                } else {
                    Bitmap.Config.ARGB_8888
                }
            }
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            if (bitmap != null) {
                val matrix = Matrix()

                // Apply Mirror (horizontal flip) before rotation
                if (mirror) {
                    matrix.postScale(-1f, 1f)
                    matrix.postTranslate(bitmap.width.toFloat(), 0f)
                }

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

                // Apply the crop after mirror/rotation/scale, matching the rectangle the user drew
                // over the displayed preview. The output takes the cropped pixel dimensions.
                if (crop != null) {
                    val bounds = crop.pixelBounds(bitmap.width, bitmap.height)
                    if (bounds.width < bitmap.width || bounds.height < bitmap.height) {
                        val cropped = Bitmap.createBitmap(
                            bitmap, bounds.left, bounds.top, bounds.width, bounds.height
                        )
                        if (cropped !== bitmap) {
                            bitmap.recycle()
                            bitmap = cropped
                        }
                    }
                }

                // Fit a full-sensor night photo into the stream's resolution. Applied last so the
                // user's Scale control is still relative to the rotated picture.
                if (maxWidth > 0 && maxHeight > 0) {
                    // Fit a full-sensor night photo into the stream's resolution using the long
                    // side, so a rotated portrait still is not squashed into a landscape box.
                    val limit = maxOf(maxWidth, maxHeight)
                    val longest = maxOf(bitmap.width, bitmap.height)
                    if (longest > limit) {
                        val fit = limit.toFloat() / longest
                        val scaled = bitmap.scale(
                            (bitmap.width * fit).toInt().coerceAtLeast(1),
                            (bitmap.height * fit).toInt().coerceAtLeast(1),
                            true,
                        )
                        if (scaled !== bitmap) {
                            bitmap.recycle()
                            bitmap = scaled
                        }
                    }
                }

                // Contrast and grayscale share one colour-matrix pass, so enabling grayscale does
                // not add another full-size bitmap. Contrast is still skipped under memory
                // pressure, but grayscale is the feature the user asked for and must stay on.
                val applyContrast = contrastValue != 0 && !DeviceMemoryHelper.isUnderMemoryPressure()
                if (applyContrast || grayscale) {
                    val colorMatrix = android.graphics.ColorMatrix()
                    if (applyContrast) {
                        val contrastFactor = 1.0f + (contrastValue / 100.0f)
                        colorMatrix.postConcat(android.graphics.ColorMatrix().apply {
                            setScale(contrastFactor, contrastFactor, contrastFactor, 1f)
                        })
                    }
                    // Applied after contrast, matching the GL shader's final output step.
                    if (grayscale) colorMatrix.postConcat(Grayscale.colorMatrix())

                    val paint = android.graphics.Paint().apply {
                        colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                    }

                    val processedBitmap = createBitmap(
                        bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(processedBitmap)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)

                    bitmap.recycle()
                    bitmap = processedBitmap
                }

                // Convert back to JPEG bytes
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                val result = outputStream.toByteArray()
                bitmap.recycle()
                bitmap = null
                outputStream.close()
                return result
            }
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("applyTransformations")
        } catch (e: Exception) {
            Log.e(TAG, "Error transforming image: ${e.message}")
            // Continue with original image if transforming image fails
        } finally {
            bitmap?.recycle()
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
        invalidatePendingWrites()
        streamingServerHelper = helper
    }
}

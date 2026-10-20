package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pseudo-encoder that keeps the camera running for motion detection and ONVIF/RTSP clients,
 * and turns analysis frames into motion events and on-demand JPEG snapshots.
 * "Has clients" while the always-on ONVIF/motion mode is enabled, so the service keeps frames flowing.
 */
class MotionStreamingEncoder(
    private val context: Context,
    private val onMotion: (Boolean) -> Unit,
) : StreamingEncoder {
    companion object {
        private const val SAMPLE_INTERVAL_MS = 200L
    }

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    val detector = MotionDetectorCore(
        sensitivity = { prefs.getString("motion_sensitivity", "5")?.toIntOrNull() ?: 5 },
        onMotion = onMotion,
    )
    @Volatile private var lastSampleMs = 0L
    @Volatile private var running = false
    @Volatile private var snapshotLatch: CountDownLatch? = null
    @Volatile private var snapshotJpeg: ByteArray? = null

    val motionEnabled get() = prefs.getBoolean("motion_detection_enabled", true)
    /** True when ONVIF/RTSP always-on mode is on; keeps the camera open with no HTTP viewers. */
    val alwaysOn get() = prefs.getBoolean("onvif_enabled", false)

    override fun processFrame(image: ImageProxy) {
        // Never let motion analysis take the streaming service down with it.
        try { analyse(image) } catch (e: Exception) { android.util.Log.w("MotionEncoder", "frame skipped: ${e.message}") }
    }

    private fun analyse(image: ImageProxy) {
        val now = System.currentTimeMillis()
        snapshotLatch?.let { latch ->
            try {
                val nv21 = convertYUV420toNV21(image)
                val crop = image.cropRect
                snapshotJpeg = rotateJpeg(convertNV21toJPEG(nv21, crop.width(), crop.height(), 85), image.imageInfo.rotationDegrees)
            } catch (_: Exception) {}
            latch.countDown()
        }
        if (!motionEnabled || now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        val plane = image.planes[0]
        // Absolute reads: other encoders may already have moved the buffer's position this frame.
        val buf = plane.buffer
        val limit = buf.limit()
        val crop = image.cropRect
        val offset = crop.top * plane.rowStride + crop.left * plane.pixelStride
        detector.process({ i -> val idx = offset + i; if (idx < limit) buf.get(idx).toInt() and 0xFF else 0 },
            crop.width(), crop.height(), plane.rowStride, plane.pixelStride, now)
    }

    /** Camera1 path: NV21 preview frames (luma is the first width*height bytes). */
    fun processNv21Frame(nv21: ByteArray, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        snapshotLatch?.let { latch ->
            try { snapshotJpeg = convertNV21toJPEG(nv21, width, height, 85) } catch (_: Exception) {}
            latch.countDown()
        }
        if (!motionEnabled || now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        lastSampleMs = now
        detector.process({ i -> nv21[i].toInt() and 0xFF }, width, height, width, 1, now)
    }

    /** JPEG of the next analysed frame, or null if the camera produces nothing within [timeoutMs]. */
    fun snapshot(timeoutMs: Long = 2_000L): ByteArray? {
        if (!running) return null
        val latch = CountDownLatch(1)
        snapshotJpeg = null
        snapshotLatch = latch
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        snapshotLatch = null
        return snapshotJpeg
    }

    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray {
        if (degrees % 360 == 0) return jpeg
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle(); if (rotated !== bmp) rotated.recycle()
        return out.toByteArray()
    }

    override fun handleRemoteControl(key: String, value: String): Boolean = false
    override fun start() { running = true }
    override fun stop() { running = false; detector.reset(); snapshotLatch?.countDown() }
    override fun hasClients(): Boolean = alwaysOn
}

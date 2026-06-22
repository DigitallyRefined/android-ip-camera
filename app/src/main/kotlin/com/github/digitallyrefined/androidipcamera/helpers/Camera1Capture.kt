package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Legacy backend. Camera1 reaches true 1920×1080 preview on old HALs (where CameraX caps at 720p),
 * and — with recordingHint on — supports a full-resolution "video snapshot": takePicture during the
 * live preview, so a 13MP still can be grabbed without stopping the stream.
 *
 * Instance = the live stream camera (feeds the GL pipe). [captureStill] grabs from it.
 * [Companion.oneShot] is a standalone open→still→release for the OTHER (or idle) camera.
 */
@Suppress("DEPRECATION")
class Camera1Capture(private val cameraId: Int, targetW: Int, targetH: Int) : CaptureBackend {
    private val camera: Camera = openWithRetry(cameraId)
    var chosenW = targetW; private set
    var chosenH = targetH; private set
    override val width get() = chosenW
    override val height get() = chosenH

    init {
        camera.parameters.supportedPreviewSizes?.let { sizes ->
            val pick = sizes.firstOrNull { it.width == targetW && it.height == targetH }
                ?: sizes.minByOrNull { abs(it.width * it.height - targetW * targetH) }
            pick?.let { chosenW = it.width; chosenH = it.height }
        }
    }

    fun start(st: SurfaceTexture, fps: Int = 30) {
        val p = camera.parameters
        p.setRecordingHint(true)                         // video mode → enables concurrent video snapshot
        p.setPreviewSize(chosenW, chosenH)
        p.supportedPreviewFpsRange?.let { ranges ->
            val want = fps * 1000
            (ranges.filter { it[1] >= want }.minByOrNull { it[0] } ?: ranges.maxByOrNull { it[1] })
                ?.let { p.setPreviewFpsRange(it[0], it[1]) }
        }
        listOf(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
               Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
               Camera.Parameters.FOCUS_MODE_AUTO)
            .firstOrNull { p.supportedFocusModes?.contains(it) == true }?.let { p.focusMode = it }
        if (p.supportedWhiteBalance?.contains(Camera.Parameters.WHITE_BALANCE_AUTO) == true)
            p.whiteBalance = Camera.Parameters.WHITE_BALANCE_AUTO
        p.supportedPictureSizes?.maxByOrNull { it.width * it.height }?.let { p.setPictureSize(it.width, it.height) }
        p.setRotation(jpegRotation(cameraId))
        try { p.set("jpeg-quality", "95") } catch (_: Exception) {}
        camera.parameters = p
        camera.setPreviewTexture(st)
        camera.startPreview()
        Log.i(TAG, "Camera1[$cameraId] preview ${chosenW}x$chosenH recHint focus=${p.focusMode}")
    }

    /** Full-res JPEG while streaming (video snapshot). [onJpeg] fires on the camera's looper thread. */
    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        try {
            camera.takePicture(null, null, Camera.PictureCallback { data, cam ->
                try { cam.startPreview() } catch (_: Exception) {}   // resume the GL feed if the HAL paused it
                onJpeg(data)
            })
        } catch (e: Exception) {
            Log.e(TAG, "takePicture: ${e.message}")
            try { camera.startPreview() } catch (_: Exception) {}
            onJpeg(null)
        }
    }

    override fun setTorch(on: Boolean) = live { p ->
        val m = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
        if (p.supportedFlashModes?.contains(m) == true) p.flashMode = m
    }
    override fun setExposure(ev: Int) = live { p ->
        val lo = p.minExposureCompensation; val hi = p.maxExposureCompensation
        if (lo != hi) p.exposureCompensation = ev.coerceIn(lo, hi)
    }
    override fun setZoom(ratio: Float) = live { p ->
        if (p.isZoomSupported) {
            val want = (ratio * 100).toInt(); val r = p.zoomRatios
            if (r != null) p.zoom = r.indices.minByOrNull { abs(r[it] - want) } ?: 0
        }
    }
    /** Robust AF: continuous mode ignores autoFocus(), so switch to AUTO, scan, then restore continuous. */
    override fun triggerAutoFocus() {
        try {
            val p = camera.parameters
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) {
                p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO; camera.parameters = p
            }
            camera.cancelAutoFocus()
            camera.autoFocus { _, c ->
                try {
                    val pp = c.parameters
                    listOf(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO, Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
                        .firstOrNull { pp.supportedFocusModes?.contains(it) == true }?.let { pp.focusMode = it; c.parameters = pp }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    private fun live(block: (Camera.Parameters) -> Unit) {
        try { val p = camera.parameters; block(p); camera.parameters = p } catch (_: Exception) {}
    }

    override fun stop() {
        try { camera.stopPreview() } catch (_: Exception) {}
        try { camera.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "Camera1Capture"

        private fun openWithRetry(id: Int): Camera {
            var last: Exception? = null
            repeat(5) { try { return Camera.open(id) } catch (e: Exception) { last = e; Thread.sleep(250) } }
            throw last ?: RuntimeException("Camera.open($id) failed")
        }

        private fun jpegRotation(id: Int): Int {
            val info = Camera.CameraInfo(); Camera.getCameraInfo(id, info); return info.orientation
        }

        /** Standalone full-res still from [cameraId]: open → brief 3A settle → takePicture → release. */
        fun oneShot(cameraId: Int, timeoutMs: Long = 6000): ByteArray? {
            val thread = HandlerThread("c1snap").apply { start() }
            val h = Handler(thread.looper)
            val done = CountDownLatch(1); val out = arrayOfNulls<ByteArray>(1)
            val cam = arrayOfNulls<Camera>(1); val tex = arrayOfNulls<SurfaceTexture>(1)
            h.post {
                try {
                    val c = openWithRetry(cameraId); cam[0] = c
                    val p = c.parameters
                    p.supportedPictureSizes?.maxByOrNull { it.width * it.height }?.let { p.setPictureSize(it.width, it.height) }
                    p.setRotation(jpegRotation(cameraId))
                    listOf(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE, Camera.Parameters.FOCUS_MODE_AUTO)
                        .firstOrNull { p.supportedFocusModes?.contains(it) == true }?.let { p.focusMode = it }
                    try { p.set("jpeg-quality", "95") } catch (_: Exception) {}
                    c.parameters = p
                    val st = SurfaceTexture(0); tex[0] = st
                    c.setPreviewTexture(st); c.startPreview()
                    h.postDelayed({
                        try { c.takePicture(null, null, Camera.PictureCallback { data, _ -> out[0] = data; done.countDown() }) }
                        catch (e: Exception) { Log.e(TAG, "oneshot takePicture: ${e.message}"); done.countDown() }
                    }, 500)
                } catch (e: Exception) { Log.e(TAG, "oneshot: ${e.message}"); done.countDown() }
            }
            try { done.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            val rel = CountDownLatch(1)
            h.post {
                try { cam[0]?.stopPreview() } catch (_: Exception) {}
                try { cam[0]?.release() } catch (_: Exception) {}
                try { tex[0]?.release() } catch (_: Exception) {}
                rel.countDown()
            }
            try { rel.await(2, TimeUnit.SECONDS) } catch (_: Exception) {}
            thread.quitSafely()
            return out[0]
        }
    }
}

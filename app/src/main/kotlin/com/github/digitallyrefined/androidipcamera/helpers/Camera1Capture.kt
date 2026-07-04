package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Legacy backend. Camera1 reaches true 1920×1080 preview on old HALs (where CameraX caps at 720p),
 * and — with recordingHint on — supports a full-resolution "video snapshot": takePicture during the
 * live preview, so a 13MP still can be grabbed without stopping the stream. [captureStill] grabs it.
 */
@Suppress("DEPRECATION")
class Camera1Capture(private val cameraId: Int, targetW: Int, targetH: Int) : CaptureBackend {
    private val camera: Camera = openWithRetry(cameraId)
    var chosenW = targetW; private set
    var chosenH = targetH; private set
    override val width get() = chosenW
    override val height get() = chosenH
    val previewRotation: Int get() = jpegRotation(cameraId)

    private var previewBuffers: Array<ByteArray>? = null
    private var onPreviewFrame: ((ByteArray) -> Unit)? = null
    @Volatile private var torchEnabled = false

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

    /** NV21 preview frames for MJPEG when the GL pipe feeds H.264 only. */
    fun setPreviewFrameCallback(callback: ((ByteArray) -> Unit)?) {
        onPreviewFrame = callback
        if (callback == null) {
            try { camera.setPreviewCallbackWithBuffer(null) } catch (_: Exception) {}
            previewBuffers = null
            return
        }
        val bufferSize = chosenW * chosenH * 3 / 2
        previewBuffers = Array(3) { ByteArray(bufferSize) }
        camera.setPreviewCallbackWithBuffer { data, cam ->
            onPreviewFrame?.invoke(data)
            cam.addCallbackBuffer(data)
        }
        previewBuffers?.forEach { camera.addCallbackBuffer(it) }
    }

    /**
     * Autofocus, then a full-res JPEG the instant focus locks (video snapshot) — fast AND sharp, vs a
     * fixed focus delay. Falls back to capturing anyway if the AF callback never fires. [onJpeg] on the
     * camera looper thread.
     */
    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        val fired = AtomicBoolean(false)
        val shoot = {
            if (fired.compareAndSet(false, true)) {
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
        }
        try {
            camera.cancelAutoFocus()
            camera.autoFocus { _, _ -> shoot() }                              // capture the instant AF locks
            Handler(Looper.getMainLooper()).postDelayed({ shoot() }, 1200)    // fallback if AF never calls back
        } catch (e: Exception) { shoot() }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) = live { p ->
        torchEnabled = on
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

    /**
     * Best-effort manual focus. The Camera1 API has no focus-distance control, so this only maps to
     * coarse modes (no gradation across the 0..1 range): [distance] < 0 restores continuous AF;
     * ~0 uses INFINITY (far); any other value uses FIXED — the lens' fixed factory position (often
     * hyperfocal/infinity), not the current AF position — where the hardware supports it.
     */
    override fun setManualFocus(distance: Float) = live { p ->
        val modes = p.supportedFocusModes
        if (distance < 0f) {
            listOf(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO, Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
                .firstOrNull { modes?.contains(it) == true }?.let { p.focusMode = it }
        } else {
            val mode = when {
                distance <= 0.05f && modes?.contains(Camera.Parameters.FOCUS_MODE_INFINITY) == true ->
                    Camera.Parameters.FOCUS_MODE_INFINITY
                modes?.contains(Camera.Parameters.FOCUS_MODE_FIXED) == true ->
                    Camera.Parameters.FOCUS_MODE_FIXED
                modes?.contains(Camera.Parameters.FOCUS_MODE_INFINITY) == true ->
                    Camera.Parameters.FOCUS_MODE_INFINITY
                else -> null
            }
            mode?.let { p.focusMode = it }
        }
    }

    private fun live(block: (Camera.Parameters) -> Unit) {
        try { val p = camera.parameters; block(p); camera.parameters = p } catch (_: Exception) {}
    }

    override fun stop() {
        setPreviewFrameCallback(null)
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
    }
}

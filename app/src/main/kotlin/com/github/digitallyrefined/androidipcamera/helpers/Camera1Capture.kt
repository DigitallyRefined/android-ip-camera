package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Legacy backend. Camera1 reaches true 1920×1080 preview on old HALs (where CameraX caps at 720p),
 * and — with recordingHint on — supports a full-resolution "video snapshot": takePicture during the
 * live preview, so a 13MP still can be grabbed without stopping the stream. [captureStill] grabs it.
 * Opening, starting and closing block on the HAL for many seconds on some phones (24 s seen), so they
 * run one at a time on a background thread, see [open], [start] and [stop]. That thread has no Looper,
 * so Camera1 still delivers preview frames and its autofocus and picture callbacks on the main looper,
 * where every control call runs too.
 */
@Suppress("DEPRECATION")
class Camera1Capture private constructor(private val cameraId: Int, targetW: Int, targetH: Int) : CaptureBackend {
    private val camera: Camera = openWithRetry(cameraId)
    var chosenW = targetW; private set
    var chosenH = targetH; private set
    override val width get() = chosenW
    override val height get() = chosenH

    private var previewBuffers: Array<ByteArray>? = null
    private var onPreviewFrame: ((ByteArray) -> Unit)? = null
    @Volatile private var torchEnabled = false
    @Volatile private var stopped = false
    /** This lens can only drive the flash if the HAL advertises FLASH_MODE_TORCH. */
    override val hasFlashUnit: Boolean

    init {
        camera.parameters.supportedPreviewSizes?.let { sizes ->
            val pick = sizes.firstOrNull { it.width == targetW && it.height == targetH }
                ?: sizes.minByOrNull { abs(it.width * it.height - targetW * targetH) }
            pick?.let { chosenW = it.width; chosenH = it.height }
        }
        hasFlashUnit = try {
            camera.parameters.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true
        } catch (_: Exception) { false }
    }

    /** Starts the preview into [st] on the Camera1 thread. [onFrame] gets the NV21 preview frames. */
    suspend fun start(st: SurfaceTexture, fps: Int = 30, onFrame: ((ByteArray) -> Unit)? = null) =
        worker.call { startPreview(st, fps, onFrame) }

    private fun startPreview(st: SurfaceTexture, fps: Int, onFrame: ((ByteArray) -> Unit)?) {
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
        // Rotation is manual-only: never bake the sensor orientation into stills.
        p.setRotation(0)
        try { p.set("jpeg-quality", "95") } catch (_: Exception) {}
        camera.parameters = p
        camera.setPreviewTexture(st)
        // Installed before the preview starts, so the HAL configures its streams once. The first
        // callback buffer added to a running preview reconfigures them, as slow as a start on some HALs.
        if (onFrame != null) setPreviewFrameCallback(onFrame)
        camera.startPreview()
        Log.i(TAG, "Camera1[$cameraId] preview ${chosenW}x$chosenH recHint focus=${p.focusMode}")
    }

    /** NV21 preview frames for MJPEG and motion detection, delivered on the main looper. */
    private fun setPreviewFrameCallback(callback: (ByteArray) -> Unit) {
        onPreviewFrame = callback
        val bufferSize = chosenW * chosenH * 3 / 2
        previewBuffers = Array(3) { ByteArray(bufferSize) }
        camera.setPreviewCallbackWithBuffer { data, cam ->
            // A frame queued before stop() must not touch a camera the Camera1 thread is releasing.
            if (stopped) return@setPreviewCallbackWithBuffer
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
        if (stopped) { onJpeg(null); return }
        // One completion is guaranteed: either the picture callback fires, takePicture throws, or a
        // watchdog bails out — so the caller can never hang and the preview is always resumed.
        val fired = AtomicBoolean(false)   // guards the AF-callback vs 1200ms-fallback double shoot
        val done = AtomicBoolean(false)    // guards single completion (picture, error, or timeout)
        val handler = Handler(Looper.getMainLooper())
        fun finish(result: ByteArray?) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            onJpeg(result)
        }
        val shoot = shoot@{
            if (fired.compareAndSet(false, true)) {
                // stop() may have released the camera while AF was pending (a snapshot racing a
                // backend swap/restart/fallback) — never touch a released camera, fail fast instead.
                if (stopped) { finish(null); return@shoot }
                try {
                    camera.takePicture(null, null, Camera.PictureCallback { data, cam ->
                        if (!stopped) try { cam.startPreview() } catch (_: Exception) {}   // resume the GL feed if the HAL paused it
                        finish(data)
                    })
                    // Some HALs never call back; bail out rather than strand the camera in "capturing".
                    handler.postDelayed({
                        Log.w(TAG, "Camera1 capture timeout — resuming preview")
                        if (!stopped) try { camera.startPreview() } catch (_: Exception) {}
                        finish(null)
                    }, 3000)
                } catch (e: Exception) {
                    Log.e(TAG, "takePicture: ${e.message}")
                    try { camera.startPreview() } catch (_: Exception) {}
                    finish(null)
                }
            }
        }
        try {
            camera.cancelAutoFocus()
            camera.autoFocus { _, _ -> shoot() }                              // capture the instant AF locks
            handler.postDelayed({ shoot() }, 1200)                            // fallback if AF never calls back
        } catch (e: Exception) { shoot() }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) = live { p ->
        // Lenses without a flash unit (ultra-wide/depth) must report OFF even when asked for ON,
        // so the service knows to route the torch through a flash-capable camera instead.
        torchEnabled = on && hasFlashUnit
        if (!hasFlashUnit) return@live
        p.flashMode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
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
        if (stopped) return
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
        if (stopped) return
        try { val p = camera.parameters; block(p); camera.parameters = p } catch (_: Exception) {}
    }

    override fun stop() = stop(afterPreview = null)

    /**
     * Stops the camera without blocking the caller. The preview stops and the device is released on the
     * Camera1 thread, where some HALs take 20 s or more. [afterPreview] runs there once the preview has
     * stopped and before the slow release, for the GL pipe the preview drew into.
     */
    fun stop(afterPreview: (() -> Unit)?) {
        if (stopped) return
        stopped = true
        worker.release { close(afterPreview) }
    }

    /** Stops the preview and releases the device. Blocks, so it runs on the Camera1 thread only. */
    private fun close(afterPreview: (() -> Unit)?) {
        val startedAt = SystemClock.elapsedRealtime()
        try { camera.setPreviewCallbackWithBuffer(null) } catch (_: Exception) {}
        try { camera.stopPreview() } catch (_: Exception) {}
        try { afterPreview?.invoke() } catch (_: Exception) {}
        try { camera.release() } catch (_: Exception) {}
        val endedAt = SystemClock.elapsedRealtime()
        val took = endedAt - startedAt
        if (took > 1000) Log.w(TAG, "Camera1[$cameraId] close took ${took}ms, HAL is slow to release resources")
        if (took > SLOW_RELEASE_MS) slowReleaseEndedAt = endedAt
    }

    companion object {
        private const val TAG = "Camera1Capture"

        /** Every Camera1 open, preview start and release, one at a time and in order. */
        private val worker = CameraWorker("Camera1")

        /** A release slower than this can take cameraserver down with it: its watchdog aborts it. */
        private const val SLOW_RELEASE_MS = 3_000L
        /** Elapsed realtime when the last release slower than [SLOW_RELEASE_MS] ended, 0 if none did. */
        @Volatile private var slowReleaseEndedAt = 0L

        /**
         * Opens the camera [pickCamera] names on the Camera1 thread, after any release still running
         * there. [pickCamera] runs there too, since the camera list comes from cameraserver, which can
         * take seconds to answer while it restarts. A caller cancelled meanwhile never leaks the
         * device: it is released there once the open ends, before anything queued after it.
         */
        suspend fun open(pickCamera: () -> Int, targetW: Int, targetH: Int): Camera1Capture =
            worker.call(discard = { it.stopped = true; it.close(null) }) { Camera1Capture(pickCamera(), targetW, targetH) }

        /**
         * Waits until every Camera1 open and release queued so far has finished, an open whose caller
         * gave up included. False after [timeoutMs].
         */
        suspend fun awaitReleased(timeoutMs: Long): Boolean = worker.awaitIdle(timeoutMs)

        /**
         * True while a Camera1 open, start or release runs, or for [withinMs] after a release slower
         * than [SLOW_RELEASE_MS]. cameraserver may then still hold the device or be coming back from an
         * abort (about 6 s seen), so a CameraX start without frames meanwhile says nothing about Camera2.
         */
        fun unsettled(withinMs: Long): Boolean {
            if (worker.busy) return true
            val endedAt = slowReleaseEndedAt
            return endedAt > 0L && SystemClock.elapsedRealtime() - endedAt < withinMs
        }

        private fun openWithRetry(id: Int): Camera {
            var last: Exception? = null
            // Give the HAL a beat to fully tear down the previous client's device. Reopening too fast
            // can hand back a handle whose preview lock never re-acquires, so takePicture then fails
            // with "attempt to use a locked camera from a different process".
            try { Thread.sleep(OPEN_SETTLE_MS) } catch (_: Exception) {}
            repeat(5) { try { return Camera.open(id) } catch (e: Exception) { last = e; Thread.sleep(250) } }
            throw last ?: RuntimeException("Camera.open($id) failed")
        }

        private const val OPEN_SETTLE_MS = 400L
    }
}

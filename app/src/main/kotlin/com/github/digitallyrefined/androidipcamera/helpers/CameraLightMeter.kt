package com.github.digitallyrefined.androidipcamera.helpers

import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * Live camera brightness telemetry for the Camera AE auto-night-mode trigger.
 *
 * Two sources feed it:
 *  - Camera2 `CaptureResult` values (ISO / exposure / frame duration) on CameraX/manual sessions.
 *  - Average luma from live preview frames or night stills when capture results are unavailable
 *    (notably the legacy Camera1 stream).
 *
 * The decision uses hysteresis and dwell times so a passing light or a single dark frame cannot
 * flap night mode:
 *  - Enter night: ISO >= threshold% of maximum AND exposure >= 90% of the frame duration,
 *    OR a clearly dark frame luma (<= 40).
 *  - Exit day: low ISO/exposure AND a bright frame luma (>= 70). When luma is unavailable the
 *    AE decision is used alone.
 *  - Luma-only fallback: enter below 40, exit above 70 (0..255 scale).
 *  - 15 seconds of continuous darkness to enter, 60 seconds of continuous brightness to leave.
 */
object CameraLightMeter {
    data class Sample(
        val iso: Int?,
        val isoMax: Int?,
        val exposureNs: Long?,
        val frameDurationNs: Long?,
        val luma: Float?,
        val atMs: Long,
    )

    const val DEFAULT_THRESHOLD_PERCENT = 90
    const val MIN_THRESHOLD_PERCENT = 50
    const val MAX_THRESHOLD_PERCENT = 100

    private const val SAMPLE_STALE_MS = 15_000L
    private const val LUMA_SAMPLE_INTERVAL_MS = 1_000L
    private const val ENTER_DWELL_MS = 15_000L
    private const val EXIT_DWELL_MS = 60_000L
    private const val ENTER_EXPOSURE_FRACTION = 0.90
    private const val EXIT_ISO_FRACTION = 0.35
    private const val EXIT_EXPOSURE_NS = 40_000_000L
    private const val LUMA_ENTER_MAX = 40f
    private const val LUMA_EXIT_MIN = 70f
    private const val LUMA_GRID_X = 64
    private const val LUMA_GRID_Y = 36

    private val lock = Any()
    private var iso: Int? = null
    private var isoMax: Int? = null
    private var exposureNs: Long? = null
    private var frameDurationNs: Long? = null
    private var luma: Float? = null
    private var sampleAtMs = 0L
    private var lastLumaSampleAtMs = 0L

    /** Last settled decision. Starts as day so entering a dark scene waits for the entry dwell. */
    private var decidedNight = false
    private var pendingNight = false
    private var pendingSinceMs = 0L

    fun reportAe(iso: Int?, isoMax: Int?, exposureNs: Long?, frameDurationNs: Long?, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            this.iso = iso?.takeIf { it > 0 }
            this.isoMax = isoMax?.takeIf { it > 0 }
            this.exposureNs = exposureNs?.takeIf { it > 0 }
            this.frameDurationNs = frameDurationNs?.takeIf { it > 0 }
            sampleAtMs = nowMs
        }
    }

    fun reportLuma(luma: Float?, nowMs: Long = System.currentTimeMillis(), clearAe: Boolean = false) {
        synchronized(lock) {
            if (luma != null && luma.isFinite() && luma >= 0f) this.luma = luma
            if (clearAe) {
                iso = null
                isoMax = null
                exposureNs = null
                frameDurationNs = null
            }
            sampleAtMs = nowMs
        }
    }

    /** Sparse luma from a Camera1 NV21 preview frame. Throttled to once a second. */
    fun reportNv21Luma(data: ByteArray, width: Int, height: Int, nowMs: Long = System.currentTimeMillis()) {
        if (width <= 0 || height <= 0 || data.size < width * height) return
        synchronized(lock) {
            if (nowMs - lastLumaSampleAtMs < LUMA_SAMPLE_INTERVAL_MS) return
            lastLumaSampleAtMs = nowMs
        }
        var sum = 0L
        var count = 0
        val stepX = max(1, width / LUMA_GRID_X)
        val stepY = max(1, height / LUMA_GRID_Y)
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                sum += data[row + x].toInt() and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count > 0) reportLuma(sum.toFloat() / count, nowMs, clearAe = true)
    }

    /** Sparse luma from a CameraX YUV analysis frame. Throttled to once a second. */
    fun reportYuvLuma(image: ImageProxy, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (nowMs - lastLumaSampleAtMs < LUMA_SAMPLE_INTERVAL_MS) return
            lastLumaSampleAtMs = nowMs
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val crop = image.cropRect
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val stepX = max(1, crop.width() / LUMA_GRID_X)
            val stepY = max(1, crop.height() / LUMA_GRID_Y)
            var sum = 0L
            var count = 0
            var y = 0
            while (y < crop.height()) {
                val row = (crop.top + y) * rowStride + crop.left * pixelStride
                var x = 0
                while (x < crop.width()) {
                    val index = row + x * pixelStride
                    if (index < buffer.limit()) {
                        sum += buffer.get(index).toInt() and 0xFF
                        count++
                    }
                    x += stepX
                }
                y += stepY
            }
            if (count > 0) reportLuma(sum.toFloat() / count, nowMs)
        } catch (_: Exception) {
            // A closing ImageProxy can invalidate its buffer; the next frame will supply a sample.
        }
    }

    /** Report the average of an already-decoded night still's luma plane. */
    fun reportLumaAverage(average: Float, nowMs: Long = System.currentTimeMillis(), clearAe: Boolean = false) {
        reportLuma(average, nowMs, clearAe)
    }

    fun sample(): Sample? = synchronized(lock) {
        if (sampleAtMs == 0L) return null
        Sample(iso, isoMax, exposureNs, frameDurationNs, luma, sampleAtMs)
    }

    /** Last settled decision for display. Does not advance the state machine. */
    fun currentDecision(): Boolean? = synchronized(lock) {
        if (sampleAtMs == 0L) null else decidedNight
    }

    /** The settled decision, or null when the latest sample is too old to trust. */
    fun decide(thresholdPercent: Int, nowMs: Long = System.currentTimeMillis()): Boolean? = synchronized(lock) {
        if (sampleAtMs == 0L || nowMs - sampleAtMs > SAMPLE_STALE_MS) return null
        val threshold = thresholdPercent.coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT) / 100.0
        val isoValue = iso
        val isoMaxValue = isoMax
        val exposureValue = exposureNs
        val frameValue = frameDurationNs

        val aeUsable = isoValue != null && isoMaxValue != null && exposureValue != null &&
            frameValue != null && frameValue > 0
        val lumaValue = luma
        val lumaEnter = lumaValue != null && lumaValue <= LUMA_ENTER_MAX
        val lumaExit = lumaValue != null && lumaValue >= LUMA_EXIT_MIN

        // A visibly dark frame must be able to override AE telemetry. Some HALs stay well below
        // the ISO/exposure ceilings even when the picture is almost black, and relying on AE alone
        // left Camera AE stuck in day mode on real devices.
        val enterDark: Boolean
        val exitDay: Boolean
        if (aeUsable) {
            val isoFraction = isoValue.toDouble() / isoMaxValue.toDouble()
            val exposureFraction = exposureValue.toDouble() / frameValue.toDouble()
            enterDark = (isoFraction >= threshold && exposureFraction >= ENTER_EXPOSURE_FRACTION) || lumaEnter
            // Leaving night mode requires the picture to be bright again, not just low AE values.
            exitDay = (isoFraction < EXIT_ISO_FRACTION || exposureValue < EXIT_EXPOSURE_NS) &&
                (lumaValue == null || lumaExit)
        } else {
            val value = lumaValue ?: return null
            enterDark = value <= LUMA_ENTER_MAX
            exitDay = value >= LUMA_EXIT_MIN
        }

        val target = if (decidedNight) !exitDay else enterDark
        if (target == decidedNight) {
            pendingNight = decidedNight
            pendingSinceMs = nowMs
            return decidedNight
        }
        if (pendingNight != target) {
            pendingNight = target
            pendingSinceMs = nowMs
        }
        val dwell = if (target) ENTER_DWELL_MS else EXIT_DWELL_MS
        if (nowMs - pendingSinceMs >= dwell) {
            decidedNight = target
            pendingSinceMs = nowMs
        }
        return decidedNight
    }

    fun reset() {
        synchronized(lock) {
            iso = null
            isoMax = null
            exposureNs = null
            frameDurationNs = null
            luma = null
            sampleAtMs = 0L
            lastLumaSampleAtMs = 0L
            decidedNight = false
            pendingNight = false
            pendingSinceMs = 0L
        }
    }
}

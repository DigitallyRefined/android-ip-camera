package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Encoder frame sizing and bandwidth estimates shared by the capture pipeline and the web UI's
 * device-info payload. The encoder caps are passed in (instead of queried here) so the same
 * functions serve production code and JVM tests, and so the estimate shown in the UI can never
 * drift from the bitrate the encoder is actually configured with.
 */
object StreamSizing {
    private val EXPLICIT_SIZE = Regex("(\\d+)x(\\d+)")

    /** Quality preset used when the stream size is left on Auto. */
    fun presetSize(quality: String): Pair<Int, Int> = when (quality) {
        "high" -> 1280 to 720
        "medium" -> 960 to 720
        else -> 800 to 600
    }

    /** Scale [w]×[h] down to fit the encoder caps without changing its aspect ratio. */
    fun fitToCaps(w: Int, h: Int, caps: H264HardwareEncoder.Companion.Caps): Pair<Int, Int> {
        if (w <= caps.maxW && h <= caps.maxH) return w to h
        val scale = minOf(caps.maxW.toDouble() / w, caps.maxH.toDouble() / h)
        fun even(v: Double) = ((v.toInt().coerceAtLeast(2)) / 2) * 2
        return even(w * scale) to even(h * scale)
    }

    /**
     * Effective encoder frame size for the stored stream preferences: an explicit `WxH`
     * [streamRes] value, or the Auto preset selected by [quality], fitted to the encoder caps.
     */
    fun effectiveSize(streamRes: String, quality: String, caps: H264HardwareEncoder.Companion.Caps): Pair<Int, Int> {
        if (streamRes == "auto") {
            val (w, h) = presetSize(quality)
            return fitToCaps(w, h, caps)
        }
        val match = EXPLICIT_SIZE.find(streamRes)
        val w = match?.groupValues?.get(1)?.toIntOrNull() ?: 1920
        val h = match?.groupValues?.get(2)?.toIntOrNull() ?: 1080
        return fitToCaps(w, h, caps)
    }

    /**
     * The size a camera will actually use for a requested [w]×[h] frame, given the sizes the active
     * capture path supports. Prefers an exact match, then the closest larger size in the same
     * aspect ratio, then the closest smaller one — the same "closest higher, then lower" rule
     * CameraX applies. Returns the request unchanged when the path reports no sizes.
     */
    fun negotiateSize(w: Int, h: Int, supported: Collection<Pair<Int, Int>>): Pair<Int, Int> {
        if (supported.isEmpty()) return w to h
        if (supported.any { it.first == w && it.second == h }) return w to h

        val aspect = w.toDouble() / h
        val sameAspect = supported.filter { (sw, sh) ->
            sh > 0 && abs(sw.toDouble() / sh - aspect) <= ASPECT_TOLERANCE
        }
        val candidates = sameAspect.ifEmpty { supported }
        val wantedArea = w.toLong() * h
        return candidates
            .filter { it.first.toLong() * it.second >= wantedArea }
            .minByOrNull { it.first.toLong() * it.second }
            ?: candidates.maxByOrNull { it.first.toLong() * it.second }
            ?: (w to h)
    }

    /** Relative tolerance for treating two frame sizes as the same shape (16:9, 4:3, …). */
    private const val ASPECT_TOLERANCE = 0.02

    /**
     * Auto target bitrate in bits per second: ~0.2 bits per pixel per frame at the configured
     * [fps]. At 30 fps this is the historical w*h*6 formula; at lower frame rates it scales down
     * instead of wasting bandwidth on frames the stream never sends.
     */
    fun autoBps(w: Int, h: Int, fps: Int, caps: H264HardwareEncoder.Companion.Caps): Int =
        clampBps((w.toDouble() * h * fps.coerceAtLeast(1) * 0.2).roundToLong(), caps)

    /** Auto target bitrate in kbps for a [w]×[h] frame at [fps]. */
    fun estimateKbps(w: Int, h: Int, fps: Int, caps: H264HardwareEncoder.Companion.Caps): Int =
        (autoBps(w, h, fps, caps) / 1000)

    /**
     * Effective target in bits per second: a manual [manualKbps] when set, otherwise Auto.
     * Both paths are clamped to the device encoder's supported range.
     */
    fun targetBps(
        w: Int,
        h: Int,
        fps: Int,
        manualKbps: Int?,
        caps: H264HardwareEncoder.Companion.Caps,
    ): Int = if (manualKbps != null) {
        clampBps(manualKbps.toLong() * 1000, caps)
    } else {
        autoBps(w, h, fps, caps)
    }

    /** [bitsPerSecond] clamped to the device encoder's range. */
    fun clampBps(bitsPerSecond: Long, caps: H264HardwareEncoder.Companion.Caps): Int =
        bitsPerSecond.coerceIn(caps.minBitrate.toLong(), caps.maxBitrate.toLong()).toInt()

    /**
     * Approximate MJPEG bitrate in kbps for an output frame of [w]×[h] at [fps] with the
     * `Bitmap.compress` JPEG [quality] (1..100). Unlike the H.264 target, MJPEG has no bitrate
     * setting: every frame is an independent JPEG, so the rate scales directly with fps.
     */
    fun estimateMjpegKbps(w: Int, h: Int, fps: Int, quality: Int): Int {
        val bitsPerFrame = w.toDouble() * h * mjpegBitsPerPixel(quality)
        return (bitsPerFrame * fps.coerceAtLeast(1) / 1000.0).roundToInt()
    }

    /**
     * Rough JPEG bits-per-pixel for a `Bitmap.compress` quality. Calibrated from sample frames;
     * real output varies with scene detail (motion and foliage raise it, flat walls lower it).
     */
    fun mjpegBitsPerPixel(quality: Int): Double = when {
        quality >= 90 -> 1.2
        quality >= 80 -> 0.8
        quality >= 70 -> 0.6
        quality >= 60 -> 0.45
        else -> 0.35
    }
}

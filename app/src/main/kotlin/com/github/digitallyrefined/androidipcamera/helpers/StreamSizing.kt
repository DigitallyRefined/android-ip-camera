package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.roundToInt

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

    /** Target bitrate in kbps for a [w]×[h] frame, clamped to the device encoder's range. */
    fun estimateKbps(w: Int, h: Int, caps: H264HardwareEncoder.Companion.Caps): Int =
        (clampBps(w, h, caps) / 1000)

    /** Target bitrate in bits per second; the formula behind [H264HardwareEncoder.bitrateFor]. */
    fun clampBps(w: Int, h: Int, caps: H264HardwareEncoder.Companion.Caps): Int =
        (w.toLong() * h * 6).coerceIn(caps.minBitrate.toLong(), caps.maxBitrate.toLong()).toInt()

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

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.SharedPreferences
import android.media.MediaCodecInfo

/**
 * Main H.264 stream encoder settings shared by the capture pipeline, the web UI and ONVIF.
 *
 * The stored bitrate is a kbps string or [AUTO]. Auto keeps the historical 0.2 bits per pixel
 * per frame target, now scaled by the configured frame rate instead of assuming 30 fps.
 */
object StreamEncoding {
    const val PREF_BITRATE = "stream_bitrate"
    const val PREF_BITRATE_MODE = "stream_bitrate_mode"
    const val PREF_KEYFRAME_INTERVAL = "stream_keyframe_interval"

    const val AUTO = "auto"
    const val MODE_VBR = "vbr"
    const val MODE_CBR = "cbr"

    /** Manual bitrate range offered by the web UI, in kbps. */
    const val MIN_BITRATE_KBPS = 500
    const val MAX_BITRATE_KBPS = 8000

    /** Keyframe interval range offered by the web UI, in seconds. */
    const val MIN_KEYFRAME_INTERVAL_SEC = 1
    const val MAX_KEYFRAME_INTERVAL_SEC = 120
    const val DEFAULT_KEYFRAME_INTERVAL_SEC = 1

    data class EncoderConfig(
        val bitrateBps: Int,
        val bitrateMode: Int,
        val keyframeIntervalSec: Int,
    )

    /** Manual target in kbps, or null for Auto (including a missing/invalid stored value). */
    fun parseBitrateKbps(value: String?): Int? {
        if (value == null) return null
        val raw = value.trim().lowercase()
        if (raw.isEmpty() || raw == AUTO) return null
        return raw.toIntOrNull()?.takeIf { it in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS }
    }

    /** "vbr" or "cbr"; anything else falls back to VBR. */
    fun normalizeMode(value: String?): String =
        if (value?.trim()?.lowercase() == MODE_CBR) MODE_CBR else MODE_VBR

    fun parseKeyframeIntervalSec(value: String?): Int =
        value?.trim()?.toIntOrNull()?.coerceIn(MIN_KEYFRAME_INTERVAL_SEC, MAX_KEYFRAME_INTERVAL_SEC)
            ?: DEFAULT_KEYFRAME_INTERVAL_SEC

    fun modeConstant(mode: String): Int =
        if (normalizeMode(mode) == MODE_CBR) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR

    /**
     * The encoder settings for a [w]×[h] main-stream frame at [fps]. Manual bitrates are clamped
     * to the device encoder's supported range; Auto scales with resolution and fps.
     */
    fun encoderConfig(prefs: SharedPreferences, w: Int, h: Int, fps: Int): EncoderConfig {
        val manualKbps = parseBitrateKbps(prefs.getString(PREF_BITRATE, null))
        val caps = H264HardwareEncoder.caps()
        return EncoderConfig(
            bitrateBps = StreamSizing.targetBps(w, h, fps, manualKbps, caps),
            bitrateMode = modeConstant(prefs.getString(PREF_BITRATE_MODE, null).orEmpty()),
            keyframeIntervalSec = parseKeyframeIntervalSec(
                prefs.getString(PREF_KEYFRAME_INTERVAL, null)
            ),
        )
    }
}

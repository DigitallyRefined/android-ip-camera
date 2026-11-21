package com.github.digitallyrefined.androidipcamera.helpers

import android.content.SharedPreferences

/**
 * Spatial noise reduction applied by the GL pipe on the phone (the "server" side).
 *
 * The web UI and the remote-control API use a 0–100 level so the slider can grow the filter
 * radius in small steps: 0 disables the filter, and 100 is the widest, strongest setting. The
 * stored value is a string to match the other stream settings.
 */
object NoiseReduction {
    const val PREF = "stream_denoise"

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 100
    const val DEFAULT_LEVEL = 0

    /** A valid 0–100 level, or null when [value] is missing or not a number. */
    fun parse(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** The persisted level, falling back to off when the preference is missing or invalid. */
    fun stored(prefs: SharedPreferences): Int =
        parse(prefs.getString(PREF, null)) ?: DEFAULT_LEVEL

    /** Normalised filter strength for a 0–100 [level], used to scale the shader radius. */
    fun strength(level: Int): Float =
        level.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat() / MAX_LEVEL
}

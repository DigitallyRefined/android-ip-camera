package com.github.digitallyrefined.androidipcamera.helpers

import android.content.SharedPreferences

/**
 * Spatial noise reduction applied by the GL pipe on the phone (the "server" side).
 *
 * The web UI and the remote-control API use a 0–20 level so the control is a simple slider:
 * 0 disables the filter, 10 is the original maximum, and 20 widens the filter for stronger
 * smoothing. The stored value is a string to match the other stream settings.
 */
object NoiseReduction {
    const val PREF = "stream_denoise"

    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 20
    const val DEFAULT_LEVEL = 0

    /** Levels per strength unit: level 10 is strength 1 (the original maximum). */
    private const val LEVELS_PER_UNIT = 10f

    /** A valid 0–20 level, or null when [value] is missing or not a number. */
    fun parse(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** The persisted level, falling back to off when the preference is missing or invalid. */
    fun stored(prefs: SharedPreferences): Int =
        parse(prefs.getString(PREF, null)) ?: DEFAULT_LEVEL

    /** GL filter strength for a 0–20 [level]: 10 is 1.0 (the original maximum), 20 is 2.0. */
    fun strength(level: Int): Float =
        level.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat() / LEVELS_PER_UNIT
}

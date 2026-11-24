package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Optional battery-temperature guard for 24/7 installations. When the battery reaches the
 * configured threshold, streaming is stopped through the same gate as `/control/stop`. The service
 * resumes it after the battery cools by [RESUME_HYSTERESIS_C].
 */
object OverheatProtection {
    const val PREF = "overheat_pause_c"

    const val DISABLED = 0
    const val MIN_C = 0
    const val MAX_C = 100
    const val RESUME_HYSTERESIS_C = 3

    /** A valid 0–100 °C threshold, or null when [value] is missing or not a number. */
    fun parse(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.coerceIn(MIN_C, MAX_C)

    /** Persisted threshold in °C; 0 disables the guard. */
    fun stored(prefs: SharedPreferences): Int =
        parse(prefs.getString(PREF, null)) ?: DISABLED

    fun stored(context: Context): Int =
        stored(PreferenceManager.getDefaultSharedPreferences(context))
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Battery-temperature guard for 24/7 installations. When the battery reaches the configured
 * threshold, streaming is stopped through the same gate as `/control/stop`. The service resumes it
 * after the battery cools by [RESUME_HYSTERESIS_C]. The guard is on by default at [DEFAULT_C] °C
 * until the user chooses a threshold; storing [DISABLED] turns it off.
 */
object OverheatProtection {
    const val PREF = "overheat_pause_c"

    const val DISABLED = 0
    const val DEFAULT_C = 45
    const val MIN_C = 0
    const val MAX_C = 100
    const val RESUME_HYSTERESIS_C = 3

    /** A valid 0–100 °C threshold, or null when [value] is missing or not a number. */
    fun parse(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.coerceIn(MIN_C, MAX_C)

    /**
     * [parse], falling back to [DEFAULT_C] so an unset or corrupt preference still protects the
     * device. An explicit 0 still disables the guard.
     */
    fun resolve(value: String?): Int = parse(value) ?: DEFAULT_C

    /** Persisted threshold in °C; [DISABLED] only when the user explicitly chose 0. */
    fun stored(prefs: SharedPreferences): Int =
        resolve(prefs.getString(PREF, null))

    fun stored(context: Context): Int =
        stored(PreferenceManager.getDefaultSharedPreferences(context))
}

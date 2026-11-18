package com.github.digitallyrefined.androidipcamera.helpers

/**
 * When CameraX binds but never delivers a frame (the Camera2 session did not configure), starts
 * that need frames use Camera1 for a while instead of paying the frame grace wait every time. One
 * miss can be a false alarm (the camera service restarting, another app holding the camera), so the
 * hold expires: 10 minutes after the first consecutive miss, doubling with each further miss up to
 * a day. A start that gets frames clears the misses. The record is kept per camera, since one lens
 * (often a physical auxiliary one) can fail to configure where the others work.
 */
object CameraXFallback {
    /** Prefixes of the per-camera keys, see [missesKey] and [missedAtKey]. */
    const val PREF_MISSES = "camera2_misses"
    const val PREF_MISSED_AT = "camera2_missed_at"
    /** Earlier versions set this after one miss and never cleared it. Removed when the service starts. */
    const val LEGACY_PREF = "camera2_unusable"
    const val FIRST_HOLD_MS = 10 * 60 * 1000L
    const val MAX_HOLD_MS = 24 * 60 * 60 * 1000L

    /** Consecutive misses of [camera] (a Camera2 id, or "front"/"back"). */
    fun missesKey(camera: String) = "${PREF_MISSES}_$camera"

    /** Wall-clock time of the last miss of [camera]. */
    fun missedAtKey(camera: String) = "${PREF_MISSED_AT}_$camera"

    /** True for every key of the record, whatever the camera. */
    fun isRecordKey(key: String) = key.startsWith(PREF_MISSES) || key.startsWith(PREF_MISSED_AT)

    /** How long [misses] consecutive misses keep frame-needing starts on Camera1. */
    fun holdMs(misses: Int): Long =
        if (misses <= 0) 0L else minOf(MAX_HOLD_MS, FIRST_HOLD_MS shl (misses - 1).coerceAtMost(16))

    /** True while the last miss, at [missedAt], still holds at [now]. A clock set back ends the hold. */
    fun useCamera1(misses: Int, missedAt: Long, now: Long): Boolean =
        misses > 0 && now >= missedAt && now - missedAt < holdMs(misses)
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager

/**
 * Runs frame-difference motion detection on the frames the streaming service already receives.
 *
 * This is deliberately not an encoder: it produces no video and is independent of the H.264
 * pipeline. When enabled it keeps the camera open (see StreamingService.hasActiveClients()) so
 * motion can be detected with no viewer connected. The resulting motion state is published to the
 * ONVIF server as motion events and used to start/stop motion-triggered recordings.
 */
class MotionMonitor(
    private val context: Context,
    private val onMotion: (Boolean) -> Unit,
) {
    companion object {
        const val PREF_ENABLED = "motion_detection_enabled"
        const val PREF_SENSITIVITY = "motion_sensitivity"
        const val PREF_STOP_DELAY = "motion_record_stop_delay"
        const val DEFAULT_SENSITIVITY = 5
        const val DEFAULT_STOP_DELAY = 10
        const val MAX_STOP_DELAY = 300
        private const val TAG = "MotionMonitor"
        private const val SAMPLE_INTERVAL_MS = 200L
    }

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    val detector = MotionDetectorCore(
        sensitivity = { prefs.getString(PREF_SENSITIVITY, "5")?.toIntOrNull() ?: DEFAULT_SENSITIVITY },
        onMotion = onMotion,
    )
    @Volatile private var lastSampleMs = 0L

    val enabled get() = prefs.getBoolean(PREF_ENABLED, false)

    /** Seconds to keep recording after motion has cleared (0 = stop immediately). */
    val stopDelaySeconds: Int
        get() = (prefs.getString(PREF_STOP_DELAY, DEFAULT_STOP_DELAY.toString())?.toIntOrNull()
            ?: DEFAULT_STOP_DELAY).coerceIn(0, MAX_STOP_DELAY)

    @Synchronized
    fun processFrame(image: ImageProxy) {
        if (!enabled) return
        // Never let motion analysis take the camera pipeline down with it.
        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now
            val plane = image.planes[0]
            // Absolute reads: the encoders may already have moved the buffer's position this frame.
            val buf = plane.buffer
            val limit = buf.limit()
            val crop = image.cropRect
            val offset = crop.top * plane.rowStride + crop.left * plane.pixelStride
            detector.process(
                { i -> val idx = offset + i; if (idx < limit) buf.get(idx).toInt() and 0xFF else 0 },
                crop.width(), crop.height(), plane.rowStride, plane.pixelStride, now
            )
        } catch (e: Exception) {
            Log.w(TAG, "motion frame skipped: ${e.message}")
        }
    }

    /** Camera1 path: NV21 preview frames (luma is the first width*height bytes). */
    @Synchronized
    fun processNv21Frame(nv21: ByteArray, width: Int, height: Int) {
        if (!enabled) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now
            detector.process({ i -> nv21[i].toInt() and 0xFF }, width, height, width, 1, now)
        } catch (e: Exception) {
            Log.w(TAG, "motion frame skipped: ${e.message}")
        }
    }

    /** Clears the background and ends an active motion event (e.g. motion turned off). */
    @Synchronized
    fun reset() {
        lastSampleMs = 0L
        detector.reset()
    }

    /** Apply a live setting change: turning motion off also ends any active event. */
    fun onSettingsChanged() {
        if (!enabled) reset()
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Runs frame-difference motion detection on the frames the streaming service already receives.
 *
 * This is deliberately not an encoder: it produces no video and is independent of the H.264
 * pipeline. The camera is kept open whenever a recording mode other than "off" is selected (see
 * StreamingService.hasActiveClients()), so motion can be detected with no viewer connected. The
 * resulting motion state is published to the ONVIF server as motion events, used for motion
 * notifications and used to start/stop motion-triggered recordings.
 */
class MotionMonitor(
    private val context: Context,
    private val onMotion: (Boolean) -> Unit,
) {
    companion object {
        const val PREF_ENABLED = "motion_detection_enabled"
        const val PREF_SENSITIVITY = "motion_sensitivity"
        const val PREF_STOP_DELAY = "motion_record_stop_delay"

        /** Recording mode: camera behaviour with no viewer connected. */
        const val PREF_MODE = "recording_mode"
        const val PREF_SEGMENT_MINUTES = "recording_segment_minutes"
        const val PREF_STORAGE_LIMIT_PERCENT = "recording_storage_limit_percent"

        const val MODE_OFF = "off"
        const val MODE_NOTIFY = "notify"
        const val MODE_MOTION = "motion"
        const val MODE_CONTINUOUS = "continuous"
        const val DEFAULT_MODE = MODE_OFF

        const val DEFAULT_SENSITIVITY = 5
        const val DEFAULT_STOP_DELAY = 10
        const val MAX_STOP_DELAY = 300

        const val DEFAULT_SEGMENT_MINUTES = 5
        const val MIN_SEGMENT_MINUTES = 1
        const val MAX_SEGMENT_MINUTES = 120

        const val DEFAULT_STORAGE_LIMIT_PERCENT = 90
        const val MIN_STORAGE_LIMIT_PERCENT = 50
        const val MAX_STORAGE_LIMIT_PERCENT = 99

        private const val TAG = "MotionMonitor"
        private const val SAMPLE_INTERVAL_MS = 200L

        /** Returns the canonical mode name, or null when [raw] is not a known mode. */
        fun normalizeMode(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
            MODE_OFF, "on_demand", "ondemand", "demand", "false", "0", "off_camera" -> MODE_OFF
            MODE_NOTIFY, "notification", "notify_only", "notify-only", "true", "1", "on" -> MODE_NOTIFY
            MODE_MOTION, "record_on_motion", "motion_clips", "motion_clip" -> MODE_MOTION
            MODE_CONTINUOUS, "continuous_recording", "always_record", "record" -> MODE_CONTINUOUS
            else -> null
        }

        /**
         * The stored mode, with a one-time migration from the old Record on Motion checkbox:
         * motion detection on meant motion-triggered clips, off meant the camera slept until a
         * viewer connected.
         */
        fun modeFor(context: Context): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            normalizeMode(prefs.getString(PREF_MODE, null))?.let { return it }
            return if (prefs.getBoolean(PREF_ENABLED, false)) MODE_MOTION else DEFAULT_MODE
        }

        /** True when the camera must stay on with no viewer (every mode except [MODE_OFF]). */
        fun isEnabled(context: Context): Boolean = modeFor(context) != MODE_OFF
    }

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)
    val detector = MotionDetectorCore(
        sensitivity = { prefs.getString(PREF_SENSITIVITY, "5")?.toIntOrNull() ?: DEFAULT_SENSITIVITY },
        onMotion = onMotion,
    )
    @Volatile private var lastSampleMs = 0L

    val mode get() = modeFor(context)

    /** Camera stays on with no viewer (notification, motion clips or continuous recording). */
    val enabled get() = mode != MODE_OFF

    /** Motion starts a clip (the "Motion clips" mode). */
    val recordingOnMotion get() = mode == MODE_MOTION

    /** Continuous segmented recording is active. */
    val continuousRecording get() = mode == MODE_CONTINUOUS

    /** Every always-on mode (everything except [MODE_OFF]) sends a notification on motion. */
    val notifyOnMotion get() = enabled

    /** Seconds to keep recording after motion has cleared (0 = stop immediately). */
    val stopDelaySeconds: Int
        get() = (prefs.getString(PREF_STOP_DELAY, DEFAULT_STOP_DELAY.toString())?.toIntOrNull()
            ?: DEFAULT_STOP_DELAY).coerceIn(0, MAX_STOP_DELAY)

    /** Length of one continuous-recording clip, in minutes. */
    val segmentMinutes: Int
        get() = (prefs.getString(PREF_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES.toString())?.toIntOrNull()
            ?: DEFAULT_SEGMENT_MINUTES).coerceIn(MIN_SEGMENT_MINUTES, MAX_SEGMENT_MINUTES)

    /** Delete the oldest recordings once the drive reaches this usage percentage. */
    val storageLimitPercent: Int
        get() = (prefs.getString(PREF_STORAGE_LIMIT_PERCENT, DEFAULT_STORAGE_LIMIT_PERCENT.toString())?.toIntOrNull()
            ?: DEFAULT_STORAGE_LIMIT_PERCENT).coerceIn(MIN_STORAGE_LIMIT_PERCENT, MAX_STORAGE_LIMIT_PERCENT)

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

    /**
     * Night mode streams still JPEGs instead of video frames. Decode a small copy and feed its
     * luma into the same detector, so motion-triggered recordings and flash keep working while
     * night mode owns the camera.
     */
    @Synchronized
    fun processJpegFrame(jpeg: ByteArray) {
        if (!enabled) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 180) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return
            try {
                val width = bitmap.width
                val height = bitmap.height
                if (width < MotionDetectorCore.GRID_W || height < MotionDetectorCore.GRID_H) return
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val luma = ByteArray(pixels.size)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    luma[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
                }
                detector.process({ i -> luma[i].toInt() and 0xFF }, width, height, width, 1, now)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "motion JPEG frame skipped: ${e.message}")
        }
    }

    /** Clears the background and ends an active motion event (e.g. motion turned off). */
    @Synchronized
    fun reset() {
        lastSampleMs = 0L
        detector.reset()
    }

    /** Apply a live setting change: re-seed after a mode switch and end an active event when off. */
    fun onSettingsChanged() {
        reset()
    }
}

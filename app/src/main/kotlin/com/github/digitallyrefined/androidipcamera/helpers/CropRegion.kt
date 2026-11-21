package com.github.digitallyrefined.androidipcamera.helpers

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A crop rectangle in the displayed (rotated and mirrored) video frame.
 *
 * Coordinates are normalised to 0..1 with a top-left origin, which keeps the stored crop valid
 * across resolutions and snapshot sizes. The web UI draws the rectangle over the live preview and
 * sends it as `crop=left,top,right,bottom`; the encoders and still-snapshot path apply it after
 * rotation, so what the user selects is exactly what stays visible.
 */
data class CropRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when this region covers (almost) the whole frame, so callers can skip the crop work. */
    fun isFullFrame(): Boolean = width >= 1f - FULL_FRAME_EPSILON && height >= 1f - FULL_FRAME_EPSILON

    /** Integer pixel bounds inside a [width]×[height] frame, with even dimensions for encoders. */
    fun pixelBounds(width: Int, height: Int): PixelBounds {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        // Left/top and the size are even so NV12/I420 chroma planes map 1:1 without rounding.
        val l = (floor(left * safeW).toInt() / 2 * 2).coerceIn(0, safeW - 2)
        val t = (floor(top * safeH).toInt() / 2 * 2).coerceIn(0, safeH - 2)
        val r = ceil(right * safeW).toInt().coerceIn(l + 2, safeW)
        val b = ceil(bottom * safeH).toInt().coerceIn(t + 2, safeH)
        var w = r - l
        var h = b - t
        if (w % 2 != 0) w--
        if (h % 2 != 0) h--
        return PixelBounds(l, t, w.coerceAtLeast(2), h.coerceAtLeast(2))
    }

    /** Output size for a cropped [width]×[height] picture: the selected pixel bounds, even-sized. */
    fun outputSize(width: Int, height: Int): PixelBounds = pixelBounds(width, height)

    /** Value stored in SharedPreferences and returned in `lensSettings.crop`. */
    fun format(): String = String.format(
        Locale.US,
        "%.4f,%.4f,%.4f,%.4f",
        left,
        top,
        right,
        bottom,
    )

    data class PixelBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    companion object {
        const val PREF_PREFIX = "crop_"
        /** Crop regions smaller than this are treated as invalid (a stray click, not a selection). */
        const val MIN_SIZE = 0.02f
        private const val FULL_FRAME_EPSILON = 0.001f

        /**
         * Parse `left,top,right,bottom` normalised coordinates. Returns null for malformed or
         * degenerate values, so a bad request can never persist a crop that hides the whole stream.
         */
        fun parse(value: String?): CropRegion? {
            val parts = value?.split(',')?.map { it.trim() } ?: return null
            if (parts.size != 4) return null
            val l = parts[0].toFloatOrNull() ?: return null
            val t = parts[1].toFloatOrNull() ?: return null
            val r = parts[2].toFloatOrNull() ?: return null
            val b = parts[3].toFloatOrNull() ?: return null
            if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) return null
            if (l < 0f || t < 0f || r > 1f || b > 1f) return null
            // A drag can arrive as start→end in any direction; normalise the corners.
            return fromCorners(l, t, r, b)
        }

        /** Per-camera crop preference, trying the selected camera token then its physical id. */
        fun read(prefs: SharedPreferences, cameraId: String?): CropRegion? {
            if (cameraId.isNullOrBlank()) return null
            val phys = cameraId.substringAfter(':', cameraId)
            val raw = when {
                prefs.contains("$PREF_PREFIX$cameraId") -> prefs.getString("$PREF_PREFIX$cameraId", null)
                prefs.contains("$PREF_PREFIX$phys") -> prefs.getString("$PREF_PREFIX$phys", null)
                else -> null
            }
            return parse(raw)
        }

        /**
         * Persist [region] for [cameraId], or remove it when [region] is null. Writes the physical
         * fallback key too, matching the other per-camera controls.
         */
        fun write(prefs: SharedPreferences, cameraId: String?, region: CropRegion?) {
            if (cameraId.isNullOrBlank()) return
            val phys = cameraId.substringAfter(':', cameraId)
            val editor = prefs.edit()
            if (region == null) {
                editor.remove("$PREF_PREFIX$cameraId")
                if (phys.isNotBlank() && phys != cameraId) editor.remove("$PREF_PREFIX$phys")
            } else {
                val formatted = region.format()
                editor.putString("$PREF_PREFIX$cameraId", formatted)
                if (phys.isNotBlank() && phys != cameraId) {
                    editor.putString("$PREF_PREFIX$phys", formatted)
                }
            }
            editor.apply()
        }

        /** Format an arbitrary numeric rectangle for storage, clamping and normalising the corners. */
        fun fromCorners(x1: Float, y1: Float, x2: Float, y2: Float): CropRegion? {
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
            val l = minOf(x1, x2).coerceIn(0f, 1f)
            val t = minOf(y1, y2).coerceIn(0f, 1f)
            val r = maxOf(x1, x2).coerceIn(0f, 1f)
            val b = maxOf(y1, y2).coerceIn(0f, 1f)
            if (r - l < MIN_SIZE || b - t < MIN_SIZE) return null
            return CropRegion(l, t, r, b)
        }
    }
}

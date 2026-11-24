package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-screen display (OSD) settings. The overlay is burned into the video itself, so RTSP/ONVIF
 * viewers, NVRs and local recordings see the same text as the web preview.
 */
object CameraOsd {
    const val PREF_ENABLED = "osd_enabled"
    const val PREF_TIME = "osd_time"
    const val PREF_DATE = "osd_date"
    const val PREF_BATTERY = "osd_battery"
    const val PREF_WIFI = "osd_wifi"
    const val PREF_TEMPERATURE = "osd_temperature"
    const val PREF_CAMERA_NAME_ENABLED = "osd_camera_name_enabled"
    const val PREF_CAMERA_NAME = "osd_camera_name"

    const val MAX_CAMERA_NAME_LENGTH = 40

    data class Settings(
        val enabled: Boolean,
        val time: Boolean,
        val date: Boolean,
        val battery: Boolean,
        val wifi: Boolean,
        val temperature: Boolean,
        val cameraNameEnabled: Boolean,
        val cameraName: String,
    ) {
        val hasAnyElement: Boolean
            get() = time || date || battery || wifi || temperature || cameraNameEnabled
    }

    fun read(prefs: SharedPreferences): Settings = Settings(
        enabled = prefs.getBoolean(PREF_ENABLED, false),
        time = prefs.getBoolean(PREF_TIME, true),
        date = prefs.getBoolean(PREF_DATE, true),
        battery = prefs.getBoolean(PREF_BATTERY, true),
        wifi = prefs.getBoolean(PREF_WIFI, true),
        temperature = prefs.getBoolean(PREF_TEMPERATURE, true),
        cameraNameEnabled = prefs.getBoolean(PREF_CAMERA_NAME_ENABLED, true),
        cameraName = sanitizeName(prefs.getString(PREF_CAMERA_NAME, "").orEmpty()),
    )

    fun read(context: Context): Settings =
        read(PreferenceManager.getDefaultSharedPreferences(context))

    fun sanitizeName(value: String): String =
        value.trim().take(MAX_CAMERA_NAME_LENGTH)

    fun parseBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        "on", "true", "1" -> true
        "off", "false", "0" -> false
        else -> null
    }
}

/**
 * Renders the OSD text to a small transparent bitmap. A single instance is shared by the MJPEG
 * encoder, the H.264 GL pipe and the CPU H.264 encoder, so they all show identical text.
 *
 * The bitmap is rebuilt at most once per second (and only when the output size or settings change).
 * Each distinct output size gets its own cached frame because the main and RTSP sub streams have
 * different resolutions and GL textures.
 */
class CameraOverlayRenderer(
    private val context: Context,
    private val cameraNameFallback: () -> String,
) {
    data class Frame(
        val bitmap: Bitmap,
        /** Unpremultiplied ARGB pixels, used when blending into YUV planes. */
        val pixels: IntArray,
        val x: Int,
        val y: Int,
        val generation: Long,
    )

    private data class CacheKey(val width: Int, val height: Int)

    private val lock = Any()
    private val frames = HashMap<CacheKey, Frame>()
    private var lines: List<String> = emptyList()
    private var lastSettings: CameraOsd.Settings? = null
    private var lastCameraName = ""
    private var lastSecond = Long.MIN_VALUE
    private var generation = 0L

    /** True when the OSD is enabled and at least one element is selected. */
    fun isActive(): Boolean {
        val settings = CameraOsd.read(context)
        return settings.enabled && settings.hasAnyElement
    }

    /** Current OSD frame for a [width]×[height] output, or null when the overlay is off. */
    fun frame(width: Int, height: Int): Frame? {
        if (width < 2 || height < 2) return null
        val settings = CameraOsd.read(context)
        if (!settings.enabled || !settings.hasAnyElement) return null
        val fallbackName = CameraOsd.sanitizeName(cameraNameFallback())
        val second = System.currentTimeMillis() / 1000L

        synchronized(lock) {
            if (settings != lastSettings || fallbackName != lastCameraName || second != lastSecond) {
                lastSettings = settings
                lastCameraName = fallbackName
                lastSecond = second
                lines = buildLines(settings, fallbackName, Date())
                generation++
            }
            if (lines.isEmpty()) return null

            val key = CacheKey(width, height)
            val cached = frames[key]
            if (cached != null && cached.generation == generation) return cached
            val frame = render(key)
            frames[key] = frame
            return frame
        }
    }

    /** Draw the current overlay onto an existing frame canvas, if it is enabled. */
    fun draw(canvas: Canvas, width: Int, height: Int) {
        val frame = frame(width, height) ?: return
        canvas.drawBitmap(frame.bitmap, frame.x.toFloat(), frame.y.toFloat(), null)
    }

    private fun buildLines(
        settings: CameraOsd.Settings,
        fallbackName: String,
        date: Date,
    ): List<String> {
        val result = ArrayList<String>(3)
        if (settings.cameraNameEnabled) {
            val name = settings.cameraName.ifBlank { fallbackName }
            if (name.isNotBlank()) result.add("📷 $name")
        }

        val status = ArrayList<String>(3)
        if (settings.battery) {
            val battery = DeviceStatus.batteryPercent(context)
            if (battery >= 0) {
                val emoji = if (battery < 15) "🪫" else "🔋"
                status.add("$emoji $battery%")
            }
        }
        if (settings.wifi) {
            val wifi = DeviceStatus.wifiStrength(context)
            if (wifi >= 0) status.add("🛜 $wifi%")
        }
        if (settings.temperature) {
            val temperature = DeviceStatus.temperatureC(context)
            if (temperature != null) status.add("🌡️ ${temperature.roundToInt()}°C")
        }
        if (status.isNotEmpty()) result.add(status.joinToString("  "))

        val timeDate = ArrayList<String>(2)
        if (settings.time) {
            val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeDate.add("🕒 " + format.format(date))
        }
        if (settings.date) {
            val format = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            timeDate.add("📅 " + format.format(date))
        }
        if (timeDate.isNotEmpty()) result.add(timeDate.joinToString("  "))

        return result
    }

    private fun render(key: CacheKey): Frame {
        var fontPx = (min(key.width, key.height) * 0.024f).roundToInt().coerceIn(9, 40)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = fontPx.toFloat()
        }
        var pad = max(5, (fontPx * 0.45f).roundToInt())
        var widest = lines.maxOf { textPaint.measureText(it) }
        // Keep the block inside very narrow streams (e.g. the RTSP sub stream) without wrapping.
        while (widest + pad * 2 > key.width - 4 && fontPx > 9) {
            fontPx--
            textPaint.textSize = fontPx.toFloat()
            pad = max(4, (fontPx * 0.45f).roundToInt())
            widest = lines.maxOf { textPaint.measureText(it) }
        }

        val lineHeight = (fontPx * 1.35f).roundToInt()
        val blockW = (widest.roundToInt() + pad * 2).coerceAtMost(key.width)
        val blockH = lineHeight * lines.size + pad * 2
        val narrow = key.width < 600
        val x = pad.coerceAtMost(max(0, key.width - blockW))
        val y = if (narrow) {
            (key.height - blockH - pad).coerceAtLeast(0)
        } else {
            pad.coerceAtMost(max(0, key.height - blockH))
        }

        val bitmap = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
        }
        val radius = max(4f, fontPx * 0.4f)
        canvas.drawRoundRect(0f, 0f, blockW.toFloat(), blockH.toFloat(), radius, radius, background)
        for ((index, line) in lines.withIndex()) {
            canvas.drawText(line, pad.toFloat(), (pad + fontPx + index * lineHeight).toFloat(), textPaint)
        }

        val pixels = IntArray(blockW * blockH)
        bitmap.getPixels(pixels, 0, blockW, 0, 0, blockW, blockH)
        return Frame(bitmap, pixels, x, y, generation)
    }
}

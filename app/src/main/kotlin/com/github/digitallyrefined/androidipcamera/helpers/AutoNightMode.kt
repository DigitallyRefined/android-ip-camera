package com.github.digitallyrefined.androidipcamera.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Automatic night mode. It can follow the sun using the NOAA solar calculator (based on the Jean
 * Meeus *Astronomical Algorithms* formulas), the phone's ambient-light sensor, or either method:
 *
 *  - GPS: night mode turns on when the sun drops below -6° (the standard end of civil twilight)
 *    and off again once it rises back above that line.
 *  - LUX: night mode turns on below the configured ambient-light threshold and off at or above it.
 *  - GPS or LUX: night mode is on when either method says it is dark.
 *
 * The trigger is evaluated from the phone's location for GPS modes. [offsetMinutes] shifts both
 * solar transitions: a positive value samples the sky that many minutes in the past, so both dusk
 * and dawn happen later; a negative value makes them happen earlier.
 *
 * The position is requested once and cached for 48 hours. Until it expires the automation only
 * reads the cached latitude/longitude, so it does not touch GPS on every poll.
 */
object AutoNightMode {
    const val PREF_MODE = "auto_night_mode"
    const val PREF_SWITCHING = "auto_night_switching"
    const val PREF_OFFSET_MINUTES = "auto_night_offset_minutes"
    const val PREF_LUX_THRESHOLD = "auto_night_lux_threshold"
    const val PREF_SCHEDULE_START_MINUTES = "auto_night_schedule_start"
    const val PREF_SCHEDULE_END_MINUTES = "auto_night_schedule_end"
    const val PREF_CAMERA_AE_THRESHOLD = "auto_night_camera_ae_threshold"
    private const val PREF_LATITUDE = "auto_night_latitude"
    private const val PREF_LONGITUDE = "auto_night_longitude"
    private const val PREF_LOCATION_AT = "auto_night_location_at"
    private const val PREF_LOCATION_ATTEMPT = "auto_night_location_attempt"

    const val MODE_OFF = "off"
    const val MODE_GPS = "gps"
    const val MODE_LUX = "lux"
    const val MODE_GPS_LUX = "gps_lux"
    const val MODE_SCHEDULE = "schedule"
    const val MODE_CAMERA_AE = "camera_ae"

    /** NOAA's civil twilight angle. Below this elevation it is dark enough for night mode. */
    const val CIVIL_TWILIGHT_ELEVATION_DEGREES = -6.0

    const val DEFAULT_OFFSET_MINUTES = 0
    const val MIN_OFFSET_MINUTES = -999
    const val MAX_OFFSET_MINUTES = 999

    const val DEFAULT_LUX_THRESHOLD = 5
    const val MIN_LUX_THRESHOLD = 0
    const val MAX_LUX_THRESHOLD = 100_000

    const val DEFAULT_SCHEDULE_START_MINUTES = 21 * 60
    const val DEFAULT_SCHEDULE_END_MINUTES = 6 * 60

    private const val TAG = "AutoNightMode"
    private const val DAY_MS = 86_400_000L
    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    /** A position fix. [atMs] is when this app obtained it (not necessarily the provider time). */
    data class GeoPoint(val latitude: Double, val longitude: Double, val atMs: Long)

    /** The start and end of a night-mode period, in epoch milliseconds. */
    data class NightWindow(val startMs: Long, val endMs: Long)

    /** A position fix is kept for this long; until it expires no GPS request is made. */
    private const val LOCATION_CACHE_MAX_AGE_MS = 48 * 60 * 60 * 1000L

    /** True while [resolveLocation] is actively waiting for a provider fix. */
    @Volatile
    private var acquiringLocation = false

    /** Last ambient-light reading reported to the web UI. */
    @Volatile
    private var cachedLux: Float? = null
    private var luxManager: SensorManager? = null
    private var luxListener: SensorEventListener? = null

    /** Do not ask the provider again for this long after a failed attempt. */
    private const val LOCATION_RETRY_MS = 15 * 60 * 1000L

    /** How long to wait for a fresh GPS/network fix before falling back to a cached position. */
    private const val LOCATION_FIX_TIMEOUT_MS = 10_000L

    /** Solar schedule search granularity and limit. */
    private const val TRANSITION_STEP_MS = 10 * 60 * 1000L
    private const val TRANSITION_SEARCH_MS = 2 * 24 * 60 * 60 * 1000L

    /**
     * The stored trigger mode. An explicitly selected mode is returned as-is. Values from the
     * earlier boolean preference are migrated to [MODE_GPS] (true) or [MODE_OFF] (false).
     * Automatic mode is off until the user chooses a trigger.
     */
    fun mode(context: Context): String {
        val prefs = prefs(context)
        val raw = prefs.all[PREF_MODE]
        return when (raw) {
            is Boolean -> {
                val mode = if (raw) MODE_GPS else MODE_OFF
                prefs.edit { putString(PREF_MODE, mode) }
                mode
            }
            is String -> {
                val mode = normalizeMode(raw)
                if (raw != mode) prefs.edit { putString(PREF_MODE, mode) }
                mode
            }
            else -> MODE_OFF
        }
    }

    fun setMode(context: Context, mode: String) {
        prefs(context).edit { putString(PREF_MODE, normalizeMode(mode)) }
        syncLuxMonitor(context)
    }

    fun normalizeMode(value: String?): String = when (value?.trim()?.lowercase()) {
        MODE_GPS, "true", "1", "on", "gps_only" -> MODE_GPS
        MODE_LUX, "light", "lux_only" -> MODE_LUX
        MODE_GPS_LUX, "both", "gps_or_lux", "gps+lux", "lux_or_gps" -> MODE_GPS_LUX
        MODE_SCHEDULE, "timer", "time" -> MODE_SCHEDULE
        MODE_CAMERA_AE, "camera", "ae", "camera_light" -> MODE_CAMERA_AE
        else -> MODE_OFF
    }

    /** True when automatic switching is active in any mode. */
    fun isEnabled(context: Context): Boolean = mode(context) != MODE_OFF

    /**
     * Whether the schedule is allowed to switch night mode itself. The schedule still runs for
     * torch-on-motion when this is off; only the automatic night-mode toggling is suppressed.
     */
    fun isSwitchingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_SWITCHING, false)

    fun setSwitchingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_SWITCHING, enabled) }
    }

    fun modeIncludesGps(mode: String): Boolean =
        mode == MODE_GPS || mode == MODE_GPS_LUX

    fun modeIncludesLux(mode: String): Boolean =
        mode == MODE_LUX || mode == MODE_GPS_LUX

    fun modeIncludesCameraAe(mode: String): Boolean = mode == MODE_CAMERA_AE

    fun modeIncludesSchedule(mode: String): Boolean = mode == MODE_SCHEDULE

    fun scheduleStartMinutes(context: Context): Int =
        prefs(context).getInt(PREF_SCHEDULE_START_MINUTES, DEFAULT_SCHEDULE_START_MINUTES)
            .coerceIn(0, 24 * 60 - 1)

    fun scheduleEndMinutes(context: Context): Int =
        prefs(context).getInt(PREF_SCHEDULE_END_MINUTES, DEFAULT_SCHEDULE_END_MINUTES)
            .coerceIn(0, 24 * 60 - 1)

    fun setSchedule(context: Context, startMinutes: Int, endMinutes: Int) {
        prefs(context).edit {
            putInt(PREF_SCHEDULE_START_MINUTES, startMinutes.coerceIn(0, 24 * 60 - 1))
            putInt(PREF_SCHEDULE_END_MINUTES, endMinutes.coerceIn(0, 24 * 60 - 1))
        }
    }

    fun cameraAeThreshold(context: Context): Int =
        prefs(context).getInt(PREF_CAMERA_AE_THRESHOLD, CameraLightMeter.DEFAULT_THRESHOLD_PERCENT)
            .coerceIn(CameraLightMeter.MIN_THRESHOLD_PERCENT, CameraLightMeter.MAX_THRESHOLD_PERCENT)

    fun setCameraAeThreshold(context: Context, percent: Int): Int {
        val clamped = percent.coerceIn(
            CameraLightMeter.MIN_THRESHOLD_PERCENT,
            CameraLightMeter.MAX_THRESHOLD_PERCENT,
        )
        prefs(context).edit { putInt(PREF_CAMERA_AE_THRESHOLD, clamped) }
        return clamped
    }

    /** Static night window. Start is inclusive and end is exclusive; windows may cross midnight. */
    fun scheduleNight(nowMs: Long, startMinutes: Int, endMinutes: Int): Boolean {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = nowMs
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(java.util.Calendar.MINUTE)
        return scheduleNightMinute(minute, startMinutes, endMinutes)
    }

    fun scheduleNightMinute(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean =
        if (startMinutes <= endMinutes) {
            minuteOfDay >= startMinutes && minuteOfDay < endMinutes
        } else {
            minuteOfDay >= startMinutes || minuteOfDay < endMinutes
        }

    fun formatScheduleTime(minutes: Int): String =
        String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    /**
     * Combine the two darkness signals for [mode]. Null means the selected method has no usable
     * input yet (no location fix or no sensor reading), so the current state should be left alone.
     */
    fun shouldBeNight(mode: String, gpsNight: Boolean?, luxNight: Boolean?): Boolean? = when (mode) {
        MODE_GPS -> gpsNight
        MODE_LUX -> luxNight
        MODE_GPS_LUX -> when {
            gpsNight != null && luxNight != null -> gpsNight || luxNight
            else -> gpsNight ?: luxNight
        }
        else -> null
    }

    fun offsetMinutes(context: Context): Int =
        prefs(context).getInt(PREF_OFFSET_MINUTES, DEFAULT_OFFSET_MINUTES)
            .coerceIn(MIN_OFFSET_MINUTES, MAX_OFFSET_MINUTES)

    fun setOffsetMinutes(context: Context, minutes: Int): Int {
        val clamped = minutes.coerceIn(MIN_OFFSET_MINUTES, MAX_OFFSET_MINUTES)
        prefs(context).edit { putInt(PREF_OFFSET_MINUTES, clamped) }
        return clamped
    }

    fun luxThreshold(context: Context): Int =
        prefs(context).getInt(PREF_LUX_THRESHOLD, DEFAULT_LUX_THRESHOLD)
            .coerceIn(MIN_LUX_THRESHOLD, MAX_LUX_THRESHOLD)

    fun setLuxThreshold(context: Context, lux: Int): Int {
        val clamped = lux.coerceIn(MIN_LUX_THRESHOLD, MAX_LUX_THRESHOLD)
        prefs(context).edit { putInt(PREF_LUX_THRESHOLD, clamped) }
        return clamped
    }

    /** True when the phone exposes an ambient-light sensor for LUX automation. */
    fun hasLightSensor(context: Context): Boolean = try {
        sensorManager(context)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Keep the cached light-sensor listener in step with the selected trigger. It runs only while
     * LUX (or GPS or LUX) is selected, so the web UI can show the current level without blocking
     * the HTTP thread on a fresh sensor event.
     */
    @Synchronized
    fun syncLuxMonitor(context: Context) {
        if (modeIncludesLux(mode(context))) startLuxMonitor(context) else stopLuxMonitor()
    }

    /** The last ambient-light reading, or null when LUX automation is not selected. */
    fun currentLux(context: Context): Float? {
        if (!modeIncludesLux(mode(context))) return null
        startLuxMonitor(context)
        cachedLux?.let { return it }
        // Some sensors do not emit their initial value on registration. Pay one short blocking read
        // the first time the web UI asks, then serve the cached listener value from then on.
        return readLux(context, 500L)?.also { cachedLux = it }
    }

    @Synchronized
    private fun startLuxMonitor(context: Context) {
        if (luxListener != null) return
        val manager = sensorManager(context) ?: return
        val sensor = try { manager.getDefaultSensor(Sensor.TYPE_LIGHT) } catch (_: Exception) { null } ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                if (lux.isFinite() && lux >= 0f) cachedLux = lux
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            luxManager = manager
            luxListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "Unable to monitor the light sensor: ${e.message}")
        }
    }

    /** Stop the cached light-sensor listener (service stops or a non-LUX trigger is selected). */
    @Synchronized
    fun stopLuxMonitor() {
        val manager = luxManager
        val listener = luxListener
        if (manager != null && listener != null) {
            try { manager.unregisterListener(listener) } catch (_: Exception) {}
        }
        luxManager = null
        luxListener = null
        cachedLux = null
    }

    /**
     * Read the current ambient light level from the phone's light sensor. Blocks for at most
     * [timeoutMs] waiting for the first event, so call it from a background thread.
     */
    @Synchronized
    fun readLux(context: Context, timeoutMs: Long = 2_000L): Float? {
        val manager = sensorManager(context) ?: return null
        val sensor = try { manager.getDefaultSensor(Sensor.TYPE_LIGHT) } catch (_: Exception) { null } ?: return null
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Float>(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                if (lux.isFinite() && lux >= 0f) {
                    result[0] = lux
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        return try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result[0]
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the light sensor: ${e.message}")
            null
        } finally {
            try { manager.unregisterListener(listener) } catch (_: Exception) {}
        }
    }

    /** True when the app has a location permission that can produce a position. */
    fun hasLocationPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when night mode should be on at [timeMs] for a position. */
    fun isNight(
        timeMs: Long,
        latitude: Double,
        longitude: Double,
        offsetMinutes: Int = DEFAULT_OFFSET_MINUTES,
    ): Boolean {
        val sampleMs = timeMs - offsetMinutes.toLong() * 60_000L
        return solarElevationDegrees(sampleMs, latitude, longitude) < CIVIL_TWILIGHT_ELEVATION_DEGREES
    }

    /**
     * Solar elevation in degrees for a UTC timestamp and geographic position.
     *
     * Implements the NOAA solar calculator: Julian day, geometric mean longitude/anomaly,
     * equation of centre, apparent longitude, obliquity, declination, equation of time and the
     * true solar time hour angle. Accurate to well under a degree, which is far more than the
     * -6° trigger needs.
     */
    fun solarElevationDegrees(timeMs: Long, latitude: Double, longitude: Double): Double {
        val julianDay = timeMs / DAY_MS.toDouble() + 2_440_587.5
        val t = (julianDay - 2_451_545.0) / 36_525.0

        val l0 = normalizeDegrees(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val meanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val meanAnomalyRad = meanAnomaly * DEG_TO_RAD
        val equationOfCentre =
            sin(meanAnomalyRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * meanAnomalyRad) * (0.019993 - 0.000101 * t) +
                sin(3 * meanAnomalyRad) * 0.000289

        val omega = 125.04 - 1934.136 * t
        val omegaRad = omega * DEG_TO_RAD
        val apparentLongitude = l0 + equationOfCentre - 0.00569 - 0.00478 * sin(omegaRad)
        val apparentLongitudeRad = apparentLongitude * DEG_TO_RAD

        val meanObliquity = 23.0 +
            (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = meanObliquity + 0.00256 * cos(omegaRad)
        val obliquityRad = obliquity * DEG_TO_RAD

        val declination = asin(sin(obliquityRad) * sin(apparentLongitudeRad))

        val l0Rad = l0 * DEG_TO_RAD
        val y = tan(obliquityRad / 2.0).let { it * it }
        val equationOfTimeMinutes = 4.0 * (
            y * sin(2 * l0Rad) -
                2 * eccentricity * sin(meanAnomalyRad) +
                4 * eccentricity * y * sin(meanAnomalyRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) -
                1.25 * eccentricity * eccentricity * sin(2 * meanAnomalyRad)
            ) * RAD_TO_DEG

        val utcMinutes = ((timeMs % DAY_MS + DAY_MS) % DAY_MS) / 60_000.0
        val trueSolarTimeMinutes = utcMinutes + equationOfTimeMinutes + 4.0 * longitude
        val hourAngle = (trueSolarTimeMinutes / 4.0 - 180.0) * DEG_TO_RAD

        val latitudeRad = latitude * DEG_TO_RAD
        val cosZenith = sin(latitudeRad) * sin(declination) +
            cos(latitudeRad) * cos(declination) * cos(hourAngle)
        val zenith = acos(cosZenith.coerceIn(-1.0, 1.0))
        return 90.0 - zenith * RAD_TO_DEG
    }

    /**
     * The cached position, regardless of age, for display without touching any location provider.
     * Returns null when no latitude/longitude has been stored yet.
     */
    fun cachedLocation(context: Context): GeoPoint? = rememberedLocation(context)

    /** True while a GPS/network fix is actively being requested. */
    fun isAcquiringLocation(): Boolean = acquiringLocation

    /**
     * The cached position, or a fresh one when the cache is missing or older than 48 hours.
     * Blocking for a few seconds only when a request is actually needed, so call it from a
     * background thread. Repeated failures are throttled rather than retried on every poll.
     */
    @Synchronized
    fun resolveLocation(context: Context, nowMs: Long = System.currentTimeMillis()): GeoPoint? {
        if (!hasLocationPermission(context)) return null
        val remembered = rememberedLocation(context)
        if (remembered != null && nowMs - remembered.atMs <= LOCATION_CACHE_MAX_AGE_MS) return remembered

        val prefs = prefs(context)
        val lastAttempt = prefs.getLong(PREF_LOCATION_ATTEMPT, 0L)
        if (nowMs - lastAttempt < LOCATION_RETRY_MS) return remembered
        prefs.edit { putLong(PREF_LOCATION_ATTEMPT, nowMs) }

        // Ask for a fresh fix once, then fall back to the provider's last known position. This only
        // runs when there is no cached position or the 48-hour cache has expired.
        acquiringLocation = true
        val location = try {
            requestSingleFix(context) ?: lastKnownLocation(context)
        } finally {
            acquiringLocation = false
        }
        if (location == null) return remembered
        val point = GeoPoint(location.latitude, location.longitude, nowMs)
        prefs.edit {
            putString(PREF_LATITUDE, point.latitude.toString())
            putString(PREF_LONGITUDE, point.longitude.toString())
            putLong(PREF_LOCATION_AT, point.atMs)
        }
        return point
    }

    /**
     * The current or upcoming night-mode interval for a position. When it is night, [startMs] is
     * when the current night began and [endMs] is when it ends; during the day it is the next dusk
     * and the following dawn. Null when no -6° transition occurs within the search window (for
     * example during polar day or polar night).
     */
    fun nightWindow(
        timeMs: Long,
        latitude: Double,
        longitude: Double,
        offsetMinutes: Int = DEFAULT_OFFSET_MINUTES,
    ): NightWindow? {
        val night = isNight(timeMs, latitude, longitude, offsetMinutes)
        val next = stateChange(timeMs, latitude, longitude, offsetMinutes, forward = true) ?: return null
        return if (night) {
            val start = stateChange(timeMs, latitude, longitude, offsetMinutes, forward = false)
                ?: return null
            NightWindow(start, next)
        } else {
            // Start just after the dusk transition, when the state has definitely flipped to night.
            val end = stateChange(next + 1, latitude, longitude, offsetMinutes, forward = true)
                ?: return null
            NightWindow(next, end)
        }
    }

    /** Format an epoch timestamp using the phone's locale and 12/24-hour setting. */
    fun formatLocalTime(context: Context, timeMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(timeMs))

    private fun stateChange(
        fromMs: Long,
        latitude: Double,
        longitude: Double,
        offsetMinutes: Int,
        forward: Boolean,
    ): Long? {
        val initial = isNight(fromMs, latitude, longitude, offsetMinutes)
        val direction = if (forward) 1L else -1L
        val limit = fromMs + direction * TRANSITION_SEARCH_MS
        var current = fromMs
        while (if (forward) current < limit else current > limit) {
            val next = current + direction * TRANSITION_STEP_MS
            if (isNight(next, latitude, longitude, offsetMinutes) != initial) {
                return refineTransition(current, next, latitude, longitude, offsetMinutes)
            }
            current = next
        }
        return null
    }

    /** First millisecond of the state after the boundary between [a] and [b]. */
    private fun refineTransition(
        a: Long,
        b: Long,
        latitude: Double,
        longitude: Double,
        offsetMinutes: Int,
    ): Long {
        var low = minOf(a, b)
        var high = maxOf(a, b)
        val lowState = isNight(low, latitude, longitude, offsetMinutes)
        while (high - low > 1_000L) {
            val mid = low + (high - low) / 2
            if (isNight(mid, latitude, longitude, offsetMinutes) == lowState) {
                low = mid
            } else {
                high = mid
            }
        }
        return high
    }

    private fun rememberedLocation(context: Context): GeoPoint? {
        val prefs = prefs(context)
        val latitude = prefs.getString(PREF_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(PREF_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude, prefs.getLong(PREF_LOCATION_AT, 0L))
    }

    @SuppressLint("MissingPermission") // Callers go through hasLocationPermission().
    private fun lastKnownLocation(context: Context): Location? {
        val manager = locationManager(context) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } }
                .filter { isUsable(it) }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the last known location: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission") // Callers go through hasLocationPermission().
    @Suppress("DEPRECATION")
    private fun requestSingleFix(context: Context): Location? {
        val manager = locationManager(context) ?: return null
        val providers = try { manager.getProviders(true) } catch (_: Exception) { emptyList() }
        if (providers.isEmpty()) return null

        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Location>(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isUsable(location)) {
                    result[0] = location
                    latch.countDown()
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Required by LocationListener on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            providers.forEach { provider ->
                try {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                    // Provider unavailable or permission was revoked between checks.
                }
            }
            latch.await(LOCATION_FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to request a location fix: ${e.message}")
        } finally {
            try { manager.removeUpdates(listener) } catch (_: Exception) {}
        }
        return result[0]?.takeIf { isUsable(it) }
    }

    private fun locationManager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun sensorManager(context: Context): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private fun isUsable(location: Location): Boolean =
        location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0

    private fun normalizeDegrees(value: Double): Double {
        var result = value % 360.0
        if (result < 0) result += 360.0
        return result
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
}

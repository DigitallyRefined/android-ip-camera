package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class AutoNightModeTest {
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun assertNear(expected: Long, actual: Long, toleranceMs: Long = 120_000L) {
        assertTrue(
            "Expected $expected ± $toleranceMs ms but was $actual",
            abs(expected - actual) <= toleranceMs,
        )
    }

    private val londonLat = 51.5074
    private val londonLon = -0.1278

    @Test
    fun `summer noon elevation matches the NOAA solar calculator`() {
        val elevation = AutoNightMode.solarElevationDegrees(
            at("2025-06-21T12:00:00Z"),
            londonLat,
            londonLon,
        )
        assertEquals(61.9, elevation, 0.5)
    }

    @Test
    fun `civil twilight starts at six degrees below the horizon`() {
        // London, 2025-06-21: civil twilight runs from about 21:10 to 04:45 UTC.
        assertFalse(AutoNightMode.isNight(at("2025-06-21T21:00:00Z"), londonLat, londonLon))
        assertTrue(AutoNightMode.isNight(at("2025-06-21T21:10:00Z"), londonLat, londonLon))
        assertTrue(AutoNightMode.isNight(at("2025-06-21T22:00:00Z"), londonLat, londonLon))
    }

    @Test
    fun `positive offset delays both transitions`() {
        val justAfterDusk = at("2025-06-21T21:10:00Z")
        // At the unshifted dusk line it is already night.
        assertTrue(AutoNightMode.isNight(justAfterDusk, londonLat, londonLon, offsetMinutes = 0))
        // +30 minutes evaluates 20:40, which is still civil daylight.
        assertFalse(AutoNightMode.isNight(justAfterDusk, londonLat, londonLon, offsetMinutes = 30))
        // -30 minutes evaluates 21:40, which is firmly night.
        assertTrue(AutoNightMode.isNight(justAfterDusk, londonLat, londonLon, offsetMinutes = -30))
    }

    @Test
    fun `polar day and polar night are handled`() {
        // Svalbard: the midnight sun stays above -6° in June, while December noon stays below it.
        val svalbardLat = 78.2232
        val svalbardLon = 15.6469
        assertFalse(AutoNightMode.isNight(at("2025-06-21T23:00:00Z"), svalbardLat, svalbardLon))
        assertTrue(AutoNightMode.isNight(at("2025-12-21T12:00:00Z"), svalbardLat, svalbardLon))
    }

    @Test
    fun `night window reports the current night start and end`() {
        val dusk = at("2025-06-21T21:09:24Z")
        val dawn = at("2025-06-22T02:55:36Z")
        val window = AutoNightMode.nightWindow(at("2025-06-21T23:00:00Z"), londonLat, londonLon)
        assertTrue(window != null)
        assertNear(dusk, window!!.startMs)
        assertNear(dawn, window.endMs)
    }

    @Test
    fun `daytime window is the next dusk and the following dawn`() {
        val dusk = at("2025-06-21T21:09:24Z")
        val dawn = at("2025-06-22T02:55:36Z")
        val window = AutoNightMode.nightWindow(at("2025-06-21T12:00:00Z"), londonLat, londonLon)
        assertTrue(window != null)
        assertNear(dusk, window!!.startMs)
        assertNear(dawn, window.endMs)
    }

    @Test
    fun `offset shifts the displayed window by the same amount`() {
        val now = at("2025-06-21T12:00:00Z")
        val base = AutoNightMode.nightWindow(now, londonLat, londonLon, offsetMinutes = 0)
        val shifted = AutoNightMode.nightWindow(now, londonLat, londonLon, offsetMinutes = 30)
        assertTrue(base != null)
        assertTrue(shifted != null)
        assertNear(base!!.startMs + 30 * 60_000L, shifted!!.startMs, toleranceMs = 2_000L)
        assertNear(base.endMs + 30 * 60_000L, shifted.endMs, toleranceMs = 2_000L)
    }

    @Test
    fun `polar day has no night window`() {
        assertNull(
            AutoNightMode.nightWindow(
                at("2025-06-21T23:00:00Z"),
                78.2232,
                15.6469,
            )
        )
    }

    @Test
    fun `modes and legacy booleans normalize to the expected trigger`() {
        assertEquals(AutoNightMode.MODE_OFF, AutoNightMode.normalizeMode(null))
        assertEquals(AutoNightMode.MODE_OFF, AutoNightMode.normalizeMode("false"))
        assertEquals(AutoNightMode.MODE_GPS, AutoNightMode.normalizeMode("true"))
        assertEquals(AutoNightMode.MODE_GPS, AutoNightMode.normalizeMode("gps"))
        assertEquals(AutoNightMode.MODE_LUX, AutoNightMode.normalizeMode("lux"))
        assertEquals(AutoNightMode.MODE_GPS_LUX, AutoNightMode.normalizeMode("gps_lux"))
        assertEquals(AutoNightMode.MODE_GPS_LUX, AutoNightMode.normalizeMode("both"))
        assertEquals(AutoNightMode.MODE_OFF, AutoNightMode.normalizeMode("unknown"))
    }

    @Test
    fun `gps or lux turns night mode on when either signal says it is dark`() {
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, false, true) == true)
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, true, false) == true)
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, true, true) == true)
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, false, false) == false)
        // A missing signal falls back to the other one.
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, null, true) == true)
        assertTrue(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, false, null) == false)
        assertNull(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS_LUX, null, null))
        // Single-signal modes ignore the other input.
        assertNull(AutoNightMode.shouldBeNight(AutoNightMode.MODE_GPS, null, true))
        assertNull(AutoNightMode.shouldBeNight(AutoNightMode.MODE_LUX, true, null))
        assertNull(AutoNightMode.shouldBeNight(AutoNightMode.MODE_OFF, true, true))
    }

    @Test
    fun `southern hemisphere seasons are not inverted`() {
        val sydneyLat = -33.8688
        val sydneyLon = 151.2093
        assertFalse(AutoNightMode.isNight(at("2025-12-21T02:00:00Z"), sydneyLat, sydneyLon))
        assertTrue(AutoNightMode.isNight(at("2025-12-21T13:00:00Z"), sydneyLat, sydneyLon))
    }
}

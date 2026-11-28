package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraLightMeterTest {
    @Before
    fun reset() {
        CameraLightMeter.reset()
    }

    private fun dark(at: Long) {
        CameraLightMeter.reportAe(
            iso = 6300,
            isoMax = 6400,
            exposureNs = 1_000_000_000,
            frameDurationNs = 1_000_000_000,
            nowMs = at,
        )
    }

    @Test
    fun `camera ae defaults to a balanced iso threshold`() {
        assertEquals(75, CameraLightMeter.DEFAULT_THRESHOLD_PERCENT)
    }

    private fun bright(at: Long) {
        CameraLightMeter.reportAe(
            iso = 1000,
            isoMax = 6400,
            exposureNs = 10_000_000,
            frameDurationNs = 33_333_333,
            nowMs = at,
        )
    }

    @Test
    fun `camera ae enters night after the darkness dwell`() {
        val start = 1_000_000L
        dark(start)
        assertFalse(CameraLightMeter.decide(95, start) == true)
        dark(start + 14_999)
        assertFalse(CameraLightMeter.decide(95, start + 14_999) == true)
        dark(start + 15_000)
        assertTrue(CameraLightMeter.decide(95, start + 15_000) == true)
    }

    @Test
    fun `camera ae exits day after the brightness dwell`() {
        val night = 2_000_000L
        dark(night)
        CameraLightMeter.decide(95, night)
        dark(night + 15_000)
        assertTrue(CameraLightMeter.decide(95, night + 15_000) == true)

        val brightStart = night + 20_000
        bright(brightStart)
        assertTrue(CameraLightMeter.decide(95, brightStart) == true)
        bright(brightStart + 59_999)
        assertTrue(CameraLightMeter.decide(95, brightStart + 59_999) == true)
        bright(brightStart + 60_000)
        assertFalse(CameraLightMeter.decide(95, brightStart + 60_000) == true)
    }

    @Test
    fun `luma fallback uses its own hysteresis`() {
        val start = 3_000_000L
        CameraLightMeter.reportLuma(20f, start, clearAe = true)
        assertFalse(CameraLightMeter.decide(95, start) == true)
        CameraLightMeter.reportLuma(20f, start + 15_000)
        assertTrue(CameraLightMeter.decide(95, start + 15_000) == true)

        val bright = start + 30_000
        CameraLightMeter.reportLuma(90f, bright)
        assertTrue(CameraLightMeter.decide(95, bright) == true)
        CameraLightMeter.reportLuma(90f, bright + 60_000)
        assertFalse(CameraLightMeter.decide(95, bright + 60_000) == true)
    }

    @Test
    fun `luma-only exit fires on a dimly lit legacy sensor`() {
        val start = 8_000_000L
        CameraLightMeter.reportLuma(20f, start, clearAe = true)
        CameraLightMeter.decide(90, start)
        CameraLightMeter.reportLuma(20f, start + 15_000)
        assertTrue(CameraLightMeter.decide(90, start + 15_000) == true)

        // 55 is below the exit threshold and must not flap night mode off.
        CameraLightMeter.reportLuma(55f, start + 30_000)
        CameraLightMeter.reportLuma(55f, start + 90_000)
        assertTrue(CameraLightMeter.decide(90, start + 90_000) == true)

        // A genuinely lit frame (legacy sensors often top out around 65) exits after the dwell.
        CameraLightMeter.reportLuma(65f, start + 100_000)
        assertTrue(CameraLightMeter.decide(90, start + 100_000) == true)
        CameraLightMeter.reportLuma(65f, start + 159_999)
        assertTrue(CameraLightMeter.decide(90, start + 159_999) == true)
        CameraLightMeter.reportLuma(65f, start + 160_000)
        assertFalse(CameraLightMeter.decide(90, start + 160_000) == true)
    }

    @Test
    fun `stale luma cannot keep night mode latched on`() {
        val start = 9_000_000L
        CameraLightMeter.reportLuma(20f, start, clearAe = true)
        CameraLightMeter.decide(90, start)
        CameraLightMeter.reportLuma(20f, start + 15_000)
        assertTrue(CameraLightMeter.decide(90, start + 15_000) == true)

        // No new stills arrive (capture timers out); the last luma must not be reused forever.
        assertNull(CameraLightMeter.decide(90, start + 26_000))
    }

    @Test
    fun `dark frame luma enters night even when ae is not at its ceiling`() {
        val start = 5_000_000L
        CameraLightMeter.reportAe(200, 6400, 30_000_000, 33_000_000, start)
        CameraLightMeter.reportLuma(20f, start)
        assertFalse(CameraLightMeter.decide(90, start) == true)

        CameraLightMeter.reportAe(200, 6400, 30_000_000, 33_000_000, start + 15_000)
        CameraLightMeter.reportLuma(20f, start + 15_000)
        assertTrue(CameraLightMeter.decide(90, start + 15_000) == true)
    }

    @Test
    fun `dark luma keeps night mode on even when ae values are low`() {
        val start = 6_000_000L
        dark(start)
        CameraLightMeter.decide(90, start)
        dark(start + 15_000)
        assertTrue(CameraLightMeter.decide(90, start + 15_000) == true)

        val after = start + 20_000
        CameraLightMeter.reportAe(200, 6400, 30_000_000, 33_000_000, after)
        CameraLightMeter.reportLuma(20f, after)
        assertTrue(CameraLightMeter.decide(90, after) == true)
        CameraLightMeter.reportAe(200, 6400, 30_000_000, 33_000_000, after + 60_000)
        CameraLightMeter.reportLuma(20f, after + 60_000)
        assertTrue(CameraLightMeter.decide(90, after + 60_000) == true)
    }

    @Test
    fun `almost white frame exits night mode even when ae still looks dark`() {
        val start = 7_000_000L
        dark(start)
        CameraLightMeter.decide(90, start)
        dark(start + 15_000)
        assertTrue(CameraLightMeter.decide(90, start + 15_000) == true)

        val whiteStart = start + 20_000
        fun whiteFrame(at: Long) {
            CameraLightMeter.reportAe(6300, 6400, 1_000_000_000, 1_000_000_000, at)
            CameraLightMeter.reportLuma(255f, at)
        }
        whiteFrame(whiteStart)
        assertTrue(CameraLightMeter.decide(90, whiteStart) == true)
        whiteFrame(whiteStart + 9_999)
        assertTrue(CameraLightMeter.decide(90, whiteStart + 9_999) == true)
        whiteFrame(whiteStart + 10_000)
        assertFalse(CameraLightMeter.decide(90, whiteStart + 10_000) == true)
    }

    @Test
    fun `stale telemetry produces no decision`() {
        val start = 4_000_000L
        dark(start)
        assertNull(CameraLightMeter.decide(95, start + 16_000))
    }
}

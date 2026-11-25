package com.github.digitallyrefined.androidipcamera.helpers

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
    fun `stale telemetry produces no decision`() {
        val start = 4_000_000L
        dark(start)
        assertNull(CameraLightMeter.decide(95, start + 16_000))
    }
}

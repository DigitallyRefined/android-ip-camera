package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMonitorModeTest {
    @Test
    fun `canonical mode names pass through`() {
        assertEquals(MotionMonitor.MODE_OFF, MotionMonitor.normalizeMode("off"))
        assertEquals(MotionMonitor.MODE_ALWAYS_ON, MotionMonitor.normalizeMode("always_on"))
        assertEquals(MotionMonitor.MODE_MOTION, MotionMonitor.normalizeMode("motion"))
        assertEquals(MotionMonitor.MODE_CONTINUOUS, MotionMonitor.normalizeMode("continuous"))
    }

    @Test
    fun `legacy notify mode migrates to always on with notifications`() {
        assertEquals(MotionMonitor.MODE_ALWAYS_ON, MotionMonitor.normalizeMode("notify"))
        assertTrue(MotionMonitor.isLegacyNotifyMode("notify"))
        assertFalse(MotionMonitor.isLegacyNotifyMode("always_on"))
        assertTrue(MotionMonitor.legacyNotifyDefault("notify", MotionMonitor.MODE_ALWAYS_ON))
        assertTrue(MotionMonitor.legacyNotifyDefault(null, MotionMonitor.MODE_MOTION))
        assertTrue(MotionMonitor.legacyNotifyDefault(null, MotionMonitor.MODE_CONTINUOUS))
        assertFalse(MotionMonitor.legacyNotifyDefault(null, MotionMonitor.MODE_ALWAYS_ON))
        assertFalse(MotionMonitor.legacyNotifyDefault(null, MotionMonitor.MODE_OFF))
    }

    @Test
    fun `mode names are case and whitespace tolerant`() {
        assertEquals(MotionMonitor.MODE_CONTINUOUS, MotionMonitor.normalizeMode(" Continuous "))
        assertEquals(MotionMonitor.MODE_MOTION, MotionMonitor.normalizeMode("Record_On_Motion"))
        assertEquals(MotionMonitor.MODE_ALWAYS_ON, MotionMonitor.normalizeMode(" NVR "))
    }

    @Test
    fun `motion detection follows the mode and notify switch`() {
        assertTrue(MotionMonitor.isCameraAlwaysOn(MotionMonitor.MODE_ALWAYS_ON))
        assertFalse(MotionMonitor.isCameraAlwaysOn(MotionMonitor.MODE_OFF))

        assertFalse(MotionMonitor.isMotionDetection(MotionMonitor.MODE_OFF, false))
        assertFalse(MotionMonitor.isMotionDetection(MotionMonitor.MODE_OFF, true))
        assertFalse(MotionMonitor.isMotionDetection(MotionMonitor.MODE_ALWAYS_ON, false))
        assertTrue(MotionMonitor.isMotionDetection(MotionMonitor.MODE_ALWAYS_ON, true))
        // Motion clips needs detection for the clip itself, even if a stale notify flag is false.
        assertTrue(MotionMonitor.isMotionDetection(MotionMonitor.MODE_MOTION, false))
        assertTrue(MotionMonitor.isMotionDetection(MotionMonitor.MODE_MOTION, true))
        assertFalse(MotionMonitor.isMotionDetection(MotionMonitor.MODE_CONTINUOUS, false))
        assertTrue(MotionMonitor.isMotionDetection(MotionMonitor.MODE_CONTINUOUS, true))
    }

    @Test
    fun `unknown modes are rejected`() {
        assertNull(MotionMonitor.normalizeMode(null))
        assertNull(MotionMonitor.normalizeMode(""))
        assertNull(MotionMonitor.normalizeMode("sometimes"))
    }
}

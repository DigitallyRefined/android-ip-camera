package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionMonitorModeTest {
    @Test
    fun `canonical mode names pass through`() {
        assertEquals(MotionMonitor.MODE_OFF, MotionMonitor.normalizeMode("off"))
        assertEquals(MotionMonitor.MODE_NOTIFY, MotionMonitor.normalizeMode("notify"))
        assertEquals(MotionMonitor.MODE_MOTION, MotionMonitor.normalizeMode("motion"))
        assertEquals(MotionMonitor.MODE_CONTINUOUS, MotionMonitor.normalizeMode("continuous"))
    }

    @Test
    fun `mode names are case and whitespace tolerant`() {
        assertEquals(MotionMonitor.MODE_CONTINUOUS, MotionMonitor.normalizeMode(" Continuous "))
        assertEquals(MotionMonitor.MODE_MOTION, MotionMonitor.normalizeMode("Record_On_Motion"))
    }

    @Test
    fun `unknown modes are rejected`() {
        assertNull(MotionMonitor.normalizeMode(null))
        assertNull(MotionMonitor.normalizeMode(""))
        assertNull(MotionMonitor.normalizeMode("sometimes"))
        assertNull(MotionMonitor.normalizeMode("always"))
    }
}

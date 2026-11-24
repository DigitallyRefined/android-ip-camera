package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverheatProtectionTest {
    @Test
    fun `parses thresholds inside the allowed range`() {
        assertEquals(0, OverheatProtection.parse("0"))
        assertEquals(45, OverheatProtection.parse(" 45 "))
        assertEquals(100, OverheatProtection.parse("100"))
    }

    @Test
    fun `clamps values outside the allowed range`() {
        assertEquals(0, OverheatProtection.parse("-5"))
        assertEquals(100, OverheatProtection.parse("999"))
    }

    @Test
    fun `rejects missing and non-numeric values`() {
        assertNull(OverheatProtection.parse(null))
        assertNull(OverheatProtection.parse(""))
        assertNull(OverheatProtection.parse("hot"))
    }

    @Test
    fun `parses overlay booleans`() {
        assertEquals(true, CameraOsd.parseBoolean("on"))
        assertEquals(true, CameraOsd.parseBoolean("TRUE"))
        assertEquals(false, CameraOsd.parseBoolean("off"))
        assertEquals(false, CameraOsd.parseBoolean("0"))
        assertNull(CameraOsd.parseBoolean("maybe"))
        assertNull(CameraOsd.parseBoolean(null))
    }

    @Test
    fun `trims and limits the camera name`() {
        assertEquals("Front door", CameraOsd.sanitizeName("  Front door  "))
        assertEquals(40, CameraOsd.sanitizeName("x".repeat(80)).length)
    }
}

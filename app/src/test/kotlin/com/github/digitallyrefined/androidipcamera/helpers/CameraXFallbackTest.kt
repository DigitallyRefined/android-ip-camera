package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXFallbackTest {
    private val minute = 60 * 1000L
    private val missedAt = 1_700_000_000_000L

    @Test
    fun `hold doubles from ten minutes up to a day`() {
        assertEquals(0L, CameraXFallback.holdMs(0))
        assertEquals(0L, CameraXFallback.holdMs(-1))
        assertEquals(10 * minute, CameraXFallback.holdMs(1))
        assertEquals(20 * minute, CameraXFallback.holdMs(2))
        assertEquals(40 * minute, CameraXFallback.holdMs(3))
        assertEquals(1280 * minute, CameraXFallback.holdMs(8))
        assertEquals(CameraXFallback.MAX_HOLD_MS, CameraXFallback.holdMs(9))
        assertEquals(CameraXFallback.MAX_HOLD_MS, CameraXFallback.holdMs(64))
        assertEquals(CameraXFallback.MAX_HOLD_MS, CameraXFallback.holdMs(Int.MAX_VALUE))
    }

    @Test
    fun `no misses never hold`() {
        assertFalse(CameraXFallback.useCamera1(0, missedAt, missedAt))
        assertFalse(CameraXFallback.useCamera1(0, 0L, missedAt))
    }

    @Test
    fun `one miss holds for ten minutes`() {
        assertTrue(CameraXFallback.useCamera1(1, missedAt, missedAt))
        assertTrue(CameraXFallback.useCamera1(1, missedAt, missedAt + 10 * minute - 1000))
        assertFalse(CameraXFallback.useCamera1(1, missedAt, missedAt + 10 * minute))
    }

    @Test
    fun `two misses hold for twenty minutes`() {
        assertTrue(CameraXFallback.useCamera1(2, missedAt, missedAt + 20 * minute - 1))
        assertFalse(CameraXFallback.useCamera1(2, missedAt, missedAt + 20 * minute))
    }

    @Test
    fun `many misses hold for a day at most`() {
        assertTrue(CameraXFallback.useCamera1(50, missedAt, missedAt + CameraXFallback.MAX_HOLD_MS - 1))
        assertFalse(CameraXFallback.useCamera1(50, missedAt, missedAt + CameraXFallback.MAX_HOLD_MS))
    }

    @Test
    fun `each camera keeps its own record`() {
        assertEquals("camera2_misses_0", CameraXFallback.missesKey("0"))
        assertEquals("camera2_missed_at_0", CameraXFallback.missedAtKey("0"))
        assertFalse(CameraXFallback.missesKey("0") == CameraXFallback.missesKey("0:2"))
        assertFalse(CameraXFallback.missedAtKey("back") == CameraXFallback.missedAtKey("front"))
    }

    @Test
    fun `record keys are told apart from other prefs`() {
        for (camera in listOf("0", "1", "0:2", "front", "back")) {
            assertTrue(CameraXFallback.isRecordKey(CameraXFallback.missesKey(camera)))
            assertTrue(CameraXFallback.isRecordKey(CameraXFallback.missedAtKey(camera)))
        }
        assertFalse(CameraXFallback.isRecordKey(CameraXFallback.LEGACY_PREF))
        assertFalse(CameraXFallback.isRecordKey("camera_id"))
        assertFalse(CameraXFallback.isRecordKey("capture_api"))
    }

    @Test
    fun `a clock set back ends the hold`() {
        assertFalse(CameraXFallback.useCamera1(1, missedAt, missedAt - 1))
        assertFalse(CameraXFallback.useCamera1(50, missedAt, 0L))
    }
}

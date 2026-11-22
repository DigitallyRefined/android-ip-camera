package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFpsTest {
    private fun range(lower: Int, upper: Int) = FpsRange(lower, upper)

    @Test
    fun `an exact fixed range wins over a wider containing range`() {
        val ranges = listOf(range(7, 30), range(15, 30), range(30, 30))
        assertEquals(range(30, 30), CaptureFps.select(ranges, 30))
    }

    @Test
    fun `widest range is never preferred`() {
        // The old CameraX choice picked [15,60] (and Camera1 picked [7,30]), letting AE fall far
        // below the requested rate.
        val ranges = listOf(range(15, 60), range(15, 30), range(30, 30), range(60, 60))
        assertEquals(range(30, 30), CaptureFps.select(ranges, 30))
        assertEquals(range(60, 60), CaptureFps.select(ranges, 60))
    }

    @Test
    fun `the narrowest containing range is chosen when no fixed range exists`() {
        val ranges = listOf(range(15, 30), range(24, 30), range(15, 60))
        assertEquals(range(24, 30), CaptureFps.select(ranges, 30))
    }

    @Test
    fun `an unsupported target falls back below before above`() {
        val ranges = listOf(range(15, 25), range(50, 60))
        assertEquals(range(15, 25), CaptureFps.select(ranges, 30))
    }

    @Test
    fun `a target below every range uses the lowest range`() {
        val ranges = listOf(range(15, 30), range(30, 30))
        assertEquals(range(15, 30), CaptureFps.select(ranges, 10))
    }

    @Test
    fun `a target above every range uses the highest range`() {
        val ranges = listOf(range(15, 30), range(24, 30))
        assertEquals(range(24, 30), CaptureFps.select(ranges, 60))
    }

    @Test
    fun `selection does not depend on advertisement order`() {
        val ranges = listOf(range(30, 30), range(15, 60), range(15, 30), range(7, 30))
        for (permutation in listOf(
            ranges,
            ranges.reversed(),
            listOf(ranges[1], ranges[3], ranges[0], ranges[2]),
        )) {
            assertEquals(range(30, 30), CaptureFps.select(permutation, 30))
        }
    }

    @Test
    fun `invalid ranges are ignored`() {
        val ranges = listOf(range(0, 0), range(30, 15), range(30, 30))
        assertEquals(range(30, 30), CaptureFps.select(ranges, 30))
    }

    @Test
    fun `a non-positive target or no valid ranges selects nothing`() {
        assertNull(CaptureFps.select(listOf(range(30, 30)), 0))
        assertNull(CaptureFps.select(emptyList(), 30))
        assertNull(CaptureFps.select(listOf(range(0, 0), range(30, 15)), 30))
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseReductionTest {
    @Test
    fun `parses levels inside the slider range`() {
        assertEquals(0, NoiseReduction.parse("0"))
        assertEquals(7, NoiseReduction.parse(" 7 "))
        assertEquals(50, NoiseReduction.parse("50"))
        assertEquals(100, NoiseReduction.parse("100"))
    }

    @Test
    fun `clamps values outside the slider range`() {
        assertEquals(0, NoiseReduction.parse("-3"))
        assertEquals(100, NoiseReduction.parse("999"))
    }

    @Test
    fun `rejects missing and non-numeric values`() {
        assertNull(NoiseReduction.parse(null))
        assertNull(NoiseReduction.parse(""))
        assertNull(NoiseReduction.parse("high"))
    }

    @Test
    fun `maps levels to normalised GL strength`() {
        assertEquals(0f, NoiseReduction.strength(0), 0f)
        assertEquals(0.25f, NoiseReduction.strength(25), 0f)
        assertEquals(0.5f, NoiseReduction.strength(50), 0f)
        assertEquals(1f, NoiseReduction.strength(100), 0f)
    }
}

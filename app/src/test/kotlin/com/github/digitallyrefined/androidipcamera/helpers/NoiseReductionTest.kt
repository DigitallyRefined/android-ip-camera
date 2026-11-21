package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseReductionTest {
    @Test
    fun `parses levels inside the slider range`() {
        assertEquals(0, NoiseReduction.parse("0"))
        assertEquals(7, NoiseReduction.parse(" 7 "))
        assertEquals(10, NoiseReduction.parse("10"))
        assertEquals(20, NoiseReduction.parse("20"))
    }

    @Test
    fun `clamps values outside the slider range`() {
        assertEquals(0, NoiseReduction.parse("-3"))
        assertEquals(20, NoiseReduction.parse("99"))
    }

    @Test
    fun `rejects missing and non-numeric values`() {
        assertNull(NoiseReduction.parse(null))
        assertNull(NoiseReduction.parse(""))
        assertNull(NoiseReduction.parse("high"))
    }

    @Test
    fun `maps levels to GL strength`() {
        assertEquals(0f, NoiseReduction.strength(0), 0f)
        assertEquals(0.5f, NoiseReduction.strength(5), 0f)
        assertEquals(1f, NoiseReduction.strength(10), 0f)
        assertEquals(2f, NoiseReduction.strength(20), 0f)
    }
}

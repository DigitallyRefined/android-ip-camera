package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRegionTest {
    @Test
    fun `parse accepts a valid normalised rectangle`() {
        val region = CropRegion.parse("0.1000,0.2000,0.9000,0.8000")
        assertEquals(0.1f, region?.left ?: Float.NaN, 0.0001f)
        assertEquals(0.2f, region?.top ?: Float.NaN, 0.0001f)
        assertEquals(0.9f, region?.right ?: Float.NaN, 0.0001f)
        assertEquals(0.8f, region?.bottom ?: Float.NaN, 0.0001f)
    }

    @Test
    fun `parse rejects malformed and degenerate rectangles`() {
        assertNull(CropRegion.parse(null))
        assertNull(CropRegion.parse(""))
        assertNull(CropRegion.parse("0.1,0.2,0.9"))
        assertNull(CropRegion.parse("0.1,0.2,0.9,1.2"))
        assertNull(CropRegion.parse("-0.1,0.2,0.9,0.8"))
        assertNull(CropRegion.parse("0.1,0.2,0.11,0.8"))
        assertNull(CropRegion.parse("nope"))
    }

    @Test
    fun `parse normalises start and end coordinates from either drag direction`() {
        val region = CropRegion.parse("0.8,0.7,0.2,0.3")
        assertEquals(0.2f, region?.left ?: Float.NaN, 0.0001f)
        assertEquals(0.3f, region?.top ?: Float.NaN, 0.0001f)
        assertEquals(0.8f, region?.right ?: Float.NaN, 0.0001f)
        assertEquals(0.7f, region?.bottom ?: Float.NaN, 0.0001f)
    }

    @Test
    fun `corners are normalised regardless of drag direction`() {
        val region = CropRegion.fromCorners(0.8f, 0.7f, 0.2f, 0.3f)
        assertEquals(0.2f, region?.left ?: Float.NaN, 0.0001f)
        assertEquals(0.3f, region?.top ?: Float.NaN, 0.0001f)
        assertEquals(0.8f, region?.right ?: Float.NaN, 0.0001f)
        assertEquals(0.7f, region?.bottom ?: Float.NaN, 0.0001f)
    }

    @Test
    fun `pixel bounds are even and stay inside the frame`() {
        val region = CropRegion.parse("0.1000,0.2000,0.9000,0.8000")!!
        val bounds = region.pixelBounds(1920, 1080)
        assertEquals(192, bounds.left)
        assertEquals(216, bounds.top)
        assertEquals(1536, bounds.width)
        assertEquals(648, bounds.height)
        assertEquals(1728, bounds.right)
        assertEquals(864, bounds.bottom)
        assertEquals(0, bounds.width % 2)
        assertEquals(0, bounds.height % 2)
    }

    @Test
    fun `pixel bounds clamp a region that rounds past the frame`() {
        val region = CropRegion.parse("0.0000,0.0000,1.0000,1.0000")!!
        val bounds = region.pixelBounds(641, 481)
        assertTrue(bounds.left >= 0)
        assertTrue(bounds.top >= 0)
        assertTrue(bounds.right <= 641)
        assertTrue(bounds.bottom <= 481)
        assertEquals(0, bounds.width % 2)
        assertEquals(0, bounds.height % 2)
    }

    @Test
    fun `output size follows the selected rectangle`() {
        val region = CropRegion.parse("0.2500,0.2500,0.7500,0.7500")!!
        val bounds = region.outputSize(800, 600)
        assertEquals(400, bounds.width)
        assertEquals(300, bounds.height)
    }

    @Test
    fun `full frame detection ignores a rounding sliver`() {
        assertTrue(CropRegion(0f, 0f, 1f, 1f).isFullFrame())
        assertTrue(CropRegion(0f, 0f, 0.9995f, 1f).isFullFrame())
        assertFalse(CropRegion(0.1f, 0f, 1f, 1f).isFullFrame())
    }

    @Test
    fun `format round trips through parse`() {
        val region = CropRegion(0.1234f, 0.2345f, 0.8765f, 0.7654f)
        val parsed = CropRegion.parse(region.format())
        assertEquals(region.left, parsed?.left ?: Float.NaN, 0.0001f)
        assertEquals(region.top, parsed?.top ?: Float.NaN, 0.0001f)
        assertEquals(region.right, parsed?.right ?: Float.NaN, 0.0001f)
        assertEquals(region.bottom, parsed?.bottom ?: Float.NaN, 0.0001f)
    }
}

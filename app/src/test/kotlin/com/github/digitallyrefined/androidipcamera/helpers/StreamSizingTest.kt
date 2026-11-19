package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSizingTest {
    private val caps = H264HardwareEncoder.Companion.Caps(
        maxW = 1920,
        maxH = 1080,
        minBitrate = 100_000,
        maxBitrate = 20_000_000,
    )

    @Test
    fun `presets map low medium high`() {
        assertEquals(800 to 600, StreamSizing.presetSize("low"))
        assertEquals(960 to 720, StreamSizing.presetSize("medium"))
        assertEquals(1280 to 720, StreamSizing.presetSize("high"))
        assertEquals(800 to 600, StreamSizing.presetSize("unknown"))
    }

    @Test
    fun `fitToCaps leaves in-range sizes untouched`() {
        assertEquals(1280 to 720, StreamSizing.fitToCaps(1280, 720, caps))
        assertEquals(801 to 601, StreamSizing.fitToCaps(801, 601, caps))
    }

    @Test
    fun `fitToCaps preserves aspect ratio while shrinking 4k`() {
        assertEquals(1920 to 1080, StreamSizing.fitToCaps(3840, 2160, caps))
    }

    @Test
    fun `fitToCaps keeps portrait shape and even dimensions`() {
        val (w, h) = StreamSizing.fitToCaps(1080, 1920, caps)
        assertTrue(w <= caps.maxW)
        assertTrue(h <= caps.maxH)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        // Same aspect ratio as the source (within even-rounding).
        assertTrue(kotlin.math.abs(w.toDouble() / h - 1080.0 / 1920.0) < 0.01)
    }

    @Test
    fun `effectiveSize resolves auto through the preset and caps`() {
        assertEquals(1280 to 720, StreamSizing.effectiveSize("auto", "high", caps))
        val small = H264HardwareEncoder.Companion.Caps(640, 480, 100_000, 20_000_000)
        assertEquals(640 to 360, StreamSizing.effectiveSize("auto", "high", small))
    }

    @Test
    fun `effectiveSize parses an explicit size`() {
        assertEquals(1920 to 1080, StreamSizing.effectiveSize("1920x1080", "low", caps))
    }

    @Test
    fun `estimateKbps follows the encoder formula`() {
        assertEquals(2880, StreamSizing.estimateKbps(800, 600, caps))
        assertEquals(5529, StreamSizing.estimateKbps(1280, 720, caps))
    }

    @Test
    fun `estimateKbps clamps to the device range`() {
        assertEquals(100, StreamSizing.estimateKbps(10, 10, caps))
        assertEquals(20000, StreamSizing.estimateKbps(4000, 3000, caps))
    }

    @Test
    fun `mjpeg estimate scales with resolution fps and quality`() {
        // 800x600 at q70 (0.6 bpp) and 15 fps: 480000 * 0.6 * 15 / 1000 = 4320 kbps.
        assertEquals(4320, StreamSizing.estimateMjpegKbps(800, 600, 15, 70))
        // 1280x720 at q80 (0.8 bpp) and 30 fps: 921600 * 0.8 * 30 / 1000 = 22118.4 -> 22118.
        assertEquals(22118, StreamSizing.estimateMjpegKbps(1280, 720, 30, 80))
    }

    @Test
    fun `mjpeg bits-per-pixel rises with quality`() {
        val qualities = listOf(50, 60, 70, 80, 90)
        val bpp = qualities.map { StreamSizing.mjpegBitsPerPixel(it) }
        assertEquals(bpp.sorted(), bpp)
        assertTrue(bpp.all { it > 0.0 })
    }
}

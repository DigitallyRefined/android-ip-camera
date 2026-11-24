package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GrayscaleTest {
    @Test
    fun `chroma neutralisation keeps every luma sample`() {
        val width = 4
        val height = 4
        val ySize = width * height
        val yuv = ByteArray(ySize + ySize / 2) { ((it * 7 + 3) and 0xff).toByte() }
        val luma = yuv.copyOf(ySize)

        Grayscale.neutralizeYuv420Chroma(yuv, width, height)

        assertArrayEquals(luma, yuv.copyOf(ySize))
        for (i in ySize until yuv.size) {
            assertEquals(Grayscale.NEUTRAL_CHROMA.toByte(), yuv[i])
        }
    }

    @Test
    fun `neutralising already neutrally chroma is idempotent`() {
        val width = 8
        val height = 6
        val yuv = ByteArray(width * height * 3 / 2) { 42 }
        Grayscale.neutralizeYuv420Chroma(yuv, width, height)
        val once = yuv.copyOf()
        Grayscale.neutralizeYuv420Chroma(yuv, width, height)
        assertArrayEquals(once, yuv)
    }

    @Test
    fun `a truncated chroma buffer is neutralised only within its bounds`() {
        val width = 4
        val height = 2
        val ySize = width * height
        val yuv = ByteArray(ySize + 1) { 1 }

        Grayscale.neutralizeYuv420Chroma(yuv, width, height)

        assertEquals(128.toByte(), yuv[ySize])
        assertEquals(1.toByte(), yuv[ySize - 1])
    }
}

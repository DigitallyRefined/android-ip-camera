package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264BitstreamTest {
    private val sps = bytes(0x67, 0x64, 0x00, 0x1f)
    private val pps = bytes(0x68, 0xee, 0x3c, 0x80)
    private val idr = bytes(0x65, 0x11, 0x22, 0x33)
    private val pFrame = bytes(0x41, 0x44, 0x55)

    @Test
    fun `normalizes mixed Annex-B start codes`() {
        val input = concat(bytes(0, 0, 1), sps, bytes(0, 0, 0, 1), pps)

        val normalized = H264Bitstream.normalize(input)!!

        assertEquals(listOf(7, 8), nalTypes(normalized.bytes))
        assertArrayEquals(annexB(sps, pps), normalized.bytes)
    }

    @Test
    fun `normalizes every supported length prefix size`() {
        for (lengthSize in 1..4) {
            val input = concat(lengthPrefix(sps.size, lengthSize), sps, lengthPrefix(pps.size, lengthSize), pps)

            val normalized = H264Bitstream.normalize(input, lengthSize)!!

            assertEquals(lengthSize, normalized.nalLengthSize)
            assertArrayEquals(annexB(sps, pps), normalized.bytes)
        }
    }

    @Test
    fun `parses avcC and preserves its NAL length size`() {
        val avcC = concat(
            bytes(1, 0x64, 0, 0x1f, 0xff, 0xe1),
            bytes(0, sps.size), sps,
            bytes(1, 0, pps.size), pps
        )

        val normalized = H264Bitstream.normalize(avcC)!!

        assertEquals(4, normalized.nalLengthSize)
        assertArrayEquals(annexB(sps, pps), normalized.bytes)
    }

    @Test
    fun `separate codec config is cached and omitted until a picture arrives`() {
        val cache = H264ParameterSetCache()

        assertNull(cache.prepare(annexB(sps), false))
        assertNull(cache.prepare(annexB(pps), false))

        val accessUnit = cache.prepare(annexB(idr), false)!!
        assertTrue(accessUnit.isKeyframe)
        assertArrayEquals(annexB(sps, pps, idr), accessUnit.bytes)
    }

    @Test
    fun `config and IDR in one buffer produces one self-contained keyframe`() {
        val cache = H264ParameterSetCache()

        val accessUnit = cache.prepare(annexB(sps, pps, idr), true)!!

        assertTrue(accessUnit.isKeyframe)
        assertEquals(listOf(7, 8, 5), nalTypes(accessUnit.bytes))
    }

    @Test
    fun `IDR detection does not depend on MediaCodec keyframe flag`() {
        val cache = H264ParameterSetCache()
        cache.prepare(annexB(sps, pps), false)

        val accessUnit = cache.prepare(annexB(idr), false)!!

        assertTrue(accessUnit.isKeyframe)
    }

    @Test
    fun `incomplete codec config never releases a waiting client`() {
        val cache = H264ParameterSetCache()
        cache.prepare(annexB(sps), false)

        val accessUnit = cache.prepare(annexB(idr), true)!!

        assertFalse(accessUnit.isKeyframe)
        assertEquals(listOf(5), nalTypes(accessUnit.bytes))
    }

    @Test
    fun `ordinary inter frame is preserved and not marked key`() {
        val cache = H264ParameterSetCache()

        val accessUnit = cache.prepare(annexB(pFrame), false)!!

        assertFalse(accessUnit.isKeyframe)
        assertArrayEquals(annexB(pFrame), accessUnit.bytes)
    }

    @Test
    fun `malformed and truncated inputs are rejected`() {
        assertNull(H264Bitstream.normalize(bytes(0, 0, 0, 5, 0x65)))
        assertNull(H264Bitstream.normalize(bytes(0x80, 1, 2, 3)))
        assertNull(H264Bitstream.normalize(byteArrayOf()))
    }

    @Test
    fun `partial MediaCodec buffers are emitted only after the final part`() {
        val assembler = H264AccessUnitAssembler()
        val first = bytes(1, 2)
        val second = bytes(3, 4)

        assertNull(assembler.append(first, true))
        assertArrayEquals(concat(first, second), assembler.append(second, false))
    }

    private fun nalTypes(data: ByteArray): List<Int> = H264Bitstream.nalRanges(data).map { it.type }

    private fun annexB(vararg nals: ByteArray): ByteArray = H264Bitstream.joinNals(nals.toList())

    private fun lengthPrefix(length: Int, size: Int): ByteArray =
        ByteArray(size) { index -> ((length ushr (8 * (size - index - 1))) and 0xff).toByte() }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import java.io.ByteArrayOutputStream

/** Pure helpers for turning MediaCodec AVC output into self-contained Annex-B access units. */
internal object H264Bitstream {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    data class Normalized(val bytes: ByteArray, val nalLengthSize: Int? = null)

    data class NalRange(
        val start: Int,
        val end: Int,
        val type: Int,
        val startCodeStart: Int,
        val startCodeLength: Int
    )

    /**
     * Accept Annex-B, AVCDecoderConfigurationRecord (avcC), a length-prefixed access unit, or one
     * raw NAL unit. The result always uses four-byte Annex-B start codes.
     */
    fun normalize(input: ByteArray, nalLengthSizeHint: Int? = null): Normalized? {
        if (input.isEmpty()) return null

        annexBRanges(input)?.let { ranges ->
            val alreadyCanonical = ranges.first().startCodeStart == 0 &&
                ranges.all { it.startCodeLength == 4 }
            return Normalized(if (alreadyCanonical) input else joinRanges(input, ranges))
        }

        parseAvcConfiguration(input)?.let { return it }

        // AVC normally uses four-byte lengths. Other sizes are only safe to infer from avcC,
        // otherwise a raw NAL header can be mistaken for a one-byte length.
        val lengthSizes = if (nalLengthSizeHint != null && nalLengthSizeHint in 1..4) {
            listOf(nalLengthSizeHint)
        } else {
            listOf(4)
        }
        for (lengthSize in lengthSizes) {
            parseLengthPrefixed(input, lengthSize)?.let { nals ->
                return Normalized(joinNals(nals), lengthSize)
            }
        }

        if (!isValidNal(input)) return null
        return Normalized(joinNals(listOf(input)))
    }

    fun nalRanges(annexB: ByteArray): List<NalRange> = annexBRanges(annexB) ?: emptyList()

    fun containsNalType(annexB: ByteArray, type: Int): Boolean =
        nalRanges(annexB).any { it.type == type }

    fun containsVcl(annexB: ByteArray): Boolean =
        nalRanges(annexB).any { it.type in 1..5 }

    fun joinNals(nals: List<ByteArray>): ByteArray {
        val size = nals.sumOf { startCode.size + it.size }
        val out = ByteArrayOutputStream(size)
        for (nal in nals) {
            out.write(startCode)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun joinRanges(input: ByteArray, ranges: List<NalRange>): ByteArray =
        joinNals(ranges.map { input.copyOfRange(it.start, it.end) })

    private fun annexBRanges(input: ByteArray): List<NalRange>? {
        val first = findStartCode(input, 0) ?: return null
        if (first.first > 3 || (0 until first.first).any { input[it].toInt() != 0 }) return null

        val ranges = mutableListOf<NalRange>()
        var marker: Pair<Int, Int>? = first
        while (marker != null) {
            val nalStart = marker.first + marker.second
            val next = findStartCode(input, nalStart)
            val nalEnd = next?.first ?: input.size
            if (nalEnd <= nalStart) return null
            if (!isValidNalHeader(input[nalStart])) return null
            ranges += NalRange(
                nalStart,
                nalEnd,
                input[nalStart].toInt() and 0x1f,
                marker.first,
                marker.second
            )
            marker = next
        }
        return ranges.takeIf { it.isNotEmpty() }
    }

    private fun findStartCode(input: ByteArray, from: Int): Pair<Int, Int>? {
        var i = from.coerceAtLeast(0)
        while (i + 2 < input.size) {
            if (input[i].toInt() == 0 && input[i + 1].toInt() == 0) {
                if (input[i + 2].toInt() == 1) return i to 3
                if (i + 3 < input.size && input[i + 2].toInt() == 0 && input[i + 3].toInt() == 1) {
                    return i to 4
                }
            }
            i++
        }
        return null
    }

    private fun parseLengthPrefixed(input: ByteArray, lengthSize: Int): List<ByteArray>? {
        if (lengthSize !in 1..4 || input.size <= lengthSize) return null
        val nals = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < input.size) {
            if (input.size - offset < lengthSize) return null
            var length = 0L
            repeat(lengthSize) {
                length = (length shl 8) or (input[offset++].toLong() and 0xff)
            }
            if (length <= 0 || length > Int.MAX_VALUE || length > input.size - offset) return null
            val end = offset + length.toInt()
            val nal = input.copyOfRange(offset, end)
            if (!isValidNal(nal)) return null
            nals += nal
            offset = end
        }
        return nals.takeIf { it.isNotEmpty() }
    }

    /** Parse the SPS/PPS part of ISO/IEC 14496-15 AVCDecoderConfigurationRecord. */
    private fun parseAvcConfiguration(input: ByteArray): Normalized? {
        if (input.size < 7 || input[0].toInt() != 1) return null
        val lengthSize = (input[4].toInt() and 0x03) + 1
        val nals = mutableListOf<ByteArray>()
        var offset = 6
        val spsCount = input[5].toInt() and 0x1f
        if (spsCount == 0) return null

        repeat(spsCount) {
            val parsed = readTwoByteLengthNal(input, offset) ?: return null
            if ((parsed.first[0].toInt() and 0x1f) != 7) return null
            nals += parsed.first
            offset = parsed.second
        }
        if (offset >= input.size) return null
        val ppsCount = input[offset++].toInt() and 0xff
        if (ppsCount == 0) return null
        repeat(ppsCount) {
            val parsed = readTwoByteLengthNal(input, offset) ?: return null
            if ((parsed.first[0].toInt() and 0x1f) != 8) return null
            nals += parsed.first
            offset = parsed.second
        }
        return Normalized(joinNals(nals), lengthSize)
    }

    private fun readTwoByteLengthNal(input: ByteArray, offset: Int): Pair<ByteArray, Int>? {
        if (offset + 2 > input.size) return null
        val length = ((input[offset].toInt() and 0xff) shl 8) or (input[offset + 1].toInt() and 0xff)
        val start = offset + 2
        val end = start + length
        if (length <= 0 || end > input.size) return null
        val nal = input.copyOfRange(start, end)
        if (!isValidNal(nal)) return null
        return nal to end
    }

    private fun isValidNal(nal: ByteArray): Boolean {
        return nal.isNotEmpty() && isValidNalHeader(nal[0])
    }

    private fun isValidNalHeader(header: Byte): Boolean =
        (header.toInt() and 0x80) == 0 && (header.toInt() and 0x1f) in 1..31
}

/** Caches codec parameter sets and makes each emitted IDR independently decodable. */
internal class H264ParameterSetCache {
    data class AccessUnit(val bytes: ByteArray, val isKeyframe: Boolean)

    private var sps: List<ByteArray> = emptyList()
    private var pps: List<ByteArray> = emptyList()
    private var nalLengthSize: Int? = null

    fun prepare(input: ByteArray, keyframeFlag: Boolean): AccessUnit? {
        val normalized = H264Bitstream.normalize(input, nalLengthSize) ?: return null
        if (normalized.nalLengthSize != null) nalLengthSize = normalized.nalLengthSize

        val ranges = H264Bitstream.nalRanges(normalized.bytes)
        val newSps = ranges.filter { it.type == 7 }
            .map { normalized.bytes.copyOfRange(it.start, it.end) }
        val newPps = ranges.filter { it.type == 8 }
            .map { normalized.bytes.copyOfRange(it.start, it.end) }
        if (newSps.isNotEmpty()) sps = newSps
        if (newPps.isNotEmpty()) pps = newPps

        // Codec-config buffers contain no picture. Cache them, but do not send them as a frame.
        if (ranges.none { it.type in 1..5 }) return null

        val isIdr = ranges.any { it.type == 5 }
        if (!keyframeFlag && !isIdr) return AccessUnit(normalized.bytes, false)

        if (!hasCompleteParameters()) {
            // Keep newly connected clients waiting rather than accepting an undecodable IDR.
            return AccessUnit(normalized.bytes, false)
        }

        val nals = ArrayList<ByteArray>(ranges.size + sps.size + pps.size)
        // Access-unit delimiter belongs first; parameter sets belong before SEI and slices.
        for (range in ranges.filter { it.type == 9 }) {
            nals += normalized.bytes.copyOfRange(range.start, range.end)
        }
        nals += sps
        nals += pps
        for (range in ranges) {
            if (range.type != 7 && range.type != 8 && range.type != 9) {
                nals += normalized.bytes.copyOfRange(range.start, range.end)
            }
        }
        return AccessUnit(H264Bitstream.joinNals(nals), true)
    }

    fun hasCompleteParameters(): Boolean = sps.isNotEmpty() && pps.isNotEmpty()
}

/** Accumulates MediaCodec buffers marked BUFFER_FLAG_PARTIAL_FRAME. */
internal class H264AccessUnitAssembler {
    private val pending = ByteArrayOutputStream()

    fun append(bytes: ByteArray, isPartial: Boolean): ByteArray? {
        if (pending.size() == 0 && !isPartial) return bytes
        pending.write(bytes)
        if (isPartial) return null
        return pending.toByteArray().also { pending.reset() }
    }

    fun reset() = pending.reset()
}

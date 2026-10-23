package com.github.digitallyrefined.androidipcamera.helpers

import java.nio.charset.StandardCharsets
import kotlin.math.abs

/** Largest RTP payload sent; with the IP/UDP/RTP headers it stays under a 1500-byte Ethernet MTU. */
internal const val RTP_MAX_PAYLOAD = 1400

/** RTP sender state for one track of one session (RFC 3550): SSRC, sequence number, counters. */
internal class RtpStream(
    val payloadType: Int,
    val clockRate: Int,
    val ssrc: Int,
    initialSequence: Int,
    private val timestampBase: Int
) {
    /** Sequence number the next packet will carry. */
    var sequence = initialSequence and 0xffff
        private set
    var packetCount = 0L
        private set
    var octetCount = 0L
        private set

    /** RTP timestamp for an instant on the shared media clock, [clockNs] measured from [epochNs]. */
    fun timestampAt(clockNs: Long, epochNs: Long): Int {
        val elapsedUs = (clockNs - epochNs) / 1000
        return (timestampBase + (elapsedUs * clockRate + 500_000) / 1_000_000).toInt()
    }

    fun packet(payload: ByteArray, marker: Boolean, timestamp: Int): ByteArray {
        val out = ByteArray(12 + payload.size)
        out[0] = 0x80.toByte() // version 2, no padding, no extension, no CSRCs
        out[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7f)).toByte()
        out[2] = (sequence shr 8).toByte()
        out[3] = sequence.toByte()
        putInt(out, 4, timestamp)
        putInt(out, 8, ssrc)
        System.arraycopy(payload, 0, out, 12, payload.size)
        sequence = (sequence + 1) and 0xffff
        packetCount++
        octetCount += payload.size
        return out
    }
}

/** H.264 over RTP (RFC 6184, packetization-mode=1): single NAL unit packets plus FU-A fragments. */
internal object H264RtpPacketizer {
    private const val NAL_ACCESS_UNIT_DELIMITER = 9
    private const val NAL_FU_A = 28

    /**
     * RTP payloads for one Annex-B access unit, in order; the caller sets the marker bit on the last.
     * Access unit delimiters are dropped: RTP marks access-unit boundaries itself.
     */
    fun payloads(accessUnit: ByteArray, maxPayload: Int = RTP_MAX_PAYLOAD): List<ByteArray> {
        val payloads = ArrayList<ByteArray>()
        for (range in H264Bitstream.nalRanges(accessUnit)) {
            if (range.type == NAL_ACCESS_UNIT_DELIMITER) continue
            val size = range.end - range.start
            if (size <= maxPayload) {
                payloads += accessUnit.copyOfRange(range.start, range.end)
                continue
            }
            val header = accessUnit[range.start].toInt() and 0xff
            val indicator = (header and 0xe0) or NAL_FU_A
            val chunk = maxPayload - 2
            var offset = range.start + 1 // the NAL header travels in the FU indicator/header
            while (offset < range.end) {
                val length = minOf(chunk, range.end - offset)
                val start = offset == range.start + 1
                val end = offset + length == range.end
                val fragment = ByteArray(2 + length)
                fragment[0] = indicator.toByte()
                fragment[1] = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or (header and 0x1f)).toByte()
                System.arraycopy(accessUnit, offset, fragment, 2, length)
                payloads += fragment
                offset += length
            }
        }
        return payloads
    }
}

/** AAC over RTP (RFC 3640, mode=AAC-hbr): one access unit per packet, fragmented if oversized. */
internal object AacRtpPacketizer {
    /** Largest access unit a 13-bit AU-size field can describe. */
    private const val MAX_ACCESS_UNIT = 0x1fff

    fun payloads(accessUnit: ByteArray, maxPayload: Int = RTP_MAX_PAYLOAD): List<ByteArray> {
        if (accessUnit.isEmpty() || accessUnit.size > MAX_ACCESS_UNIT) return emptyList()
        val payloads = ArrayList<ByteArray>()
        val chunk = maxPayload - 4
        var offset = 0
        while (offset < accessUnit.size) {
            val length = minOf(chunk, accessUnit.size - offset)
            val payload = ByteArray(4 + length)
            payload[0] = 0x00 // AU-headers-length: one 16-bit header
            payload[1] = 0x10
            // AU-size (13 bits, the whole AU even in fragments) + AU-index (3 bits, always 0).
            payload[2] = (accessUnit.size shr 5).toByte()
            payload[3] = ((accessUnit.size and 0x1f) shl 3).toByte()
            System.arraycopy(accessUnit, offset, payload, 4, length)
            payloads += payload
            offset += length
        }
        return payloads
    }
}

/** RTCP packets the server sends (RFC 3550). */
internal object RtcpPackets {
    private const val NTP_UNIX_OFFSET_S = 2_208_988_800L

    /**
     * Compound sender report + SDES CNAME. The SR ties [rtpTimestamp] to wall-clock time, which
     * is what lets players line audio and video up.
     */
    fun senderReport(
        ssrc: Int,
        wallClockMs: Long,
        rtpTimestamp: Int,
        packetCount: Long,
        octetCount: Long,
        cname: String
    ): ByteArray {
        val name = cname.toByteArray(StandardCharsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val itemsLength = 2 + name.size
        val padding = 4 - itemsLength % 4 // at least one terminating null octet
        val sdesLength = 8 + itemsLength + padding
        val out = ByteArray(28 + sdesLength)

        out[0] = 0x80.toByte() // version 2, no reception report blocks
        out[1] = 200.toByte()
        out[3] = 6 // length in 32-bit words minus one
        putInt(out, 4, ssrc)
        val seconds = wallClockMs / 1000 + NTP_UNIX_OFFSET_S
        val fraction = ((wallClockMs % 1000) shl 32) / 1000
        putInt(out, 8, seconds.toInt())
        putInt(out, 12, fraction.toInt())
        putInt(out, 16, rtpTimestamp)
        putInt(out, 20, packetCount.toInt())
        putInt(out, 24, octetCount.toInt())

        val sdes = 28
        out[sdes] = 0x81.toByte() // version 2, one chunk
        out[sdes + 1] = 202.toByte()
        val words = sdesLength / 4 - 1
        out[sdes + 2] = (words shr 8).toByte()
        out[sdes + 3] = words.toByte()
        putInt(out, sdes + 4, ssrc)
        out[sdes + 8] = 1 // CNAME
        out[sdes + 9] = name.size.toByte()
        System.arraycopy(name, 0, out, sdes + 10, name.size)
        return out
    }
}

/**
 * Maps encoder presentation timestamps onto one monotonic clock ([nanoTime]) shared by every
 * track, so the RTP timestamps of video and audio — and the RTCP sender reports that tie them to
 * wall-clock time — all use one timebase.
 *
 * Camera timestamps are either already on [nanoTime] (CLOCK_MONOTONIC) or on the boot clock
 * (elapsedRealtimeNanos, which also counts deep sleep). The mapping recognises both; anything else
 * is anchored to the arrival time of the frame. It re-detects when the source jumps, e.g. after
 * the encoder restarts on a different timebase.
 */
internal class RtpMediaClock(
    private val nanoTime: () -> Long = System::nanoTime,
    private val bootClockNanos: () -> Long? = { null }
) {
    private companion object {
        /** A frame is at most this old when it reaches the server. */
        const val PLAUSIBLE_LATENCY_NS = 1_000_000_000L
        /** Mapped time this far from "now" means the source timebase changed. */
        const val RESYNC_NS = 2_000_000_000L
    }

    private var offsetNs: Long? = null
    private var lastMappedNs: Long? = null

    @Synchronized
    fun toClockNs(ptsUs: Long): Long {
        val ptsNs = ptsUs * 1000
        val now = nanoTime()
        var offset = offsetNs
        if (offset == null || abs(now - (ptsNs + offset)) > RESYNC_NS) {
            offset = detectOffset(ptsNs, now)
            offsetNs = offset
        }
        var mapped = ptsNs + offset
        val last = lastMappedNs
        if (last != null && mapped <= last) mapped = last + 1
        lastMappedNs = mapped
        return mapped
    }

    private fun detectOffset(ptsNs: Long, now: Long): Long {
        fun plausible(offset: Long) = (now - (ptsNs + offset)) in 0..PLAUSIBLE_LATENCY_NS
        if (plausible(0)) return 0
        val boot = bootClockNanos()
        if (boot != null) {
            val offset = now - boot
            if (plausible(offset)) return offset
        }
        return now - ptsNs
    }
}

private fun putInt(out: ByteArray, offset: Int, value: Int) {
    out[offset] = (value shr 24).toByte()
    out[offset + 1] = (value shr 16).toByte()
    out[offset + 2] = (value shr 8).toByte()
    out[offset + 3] = value.toByte()
}

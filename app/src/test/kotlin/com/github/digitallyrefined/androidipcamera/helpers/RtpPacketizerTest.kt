package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RtpPacketizerTest {
    private val aud = bytes(0x09, 0xf0)
    private val sps = bytes(0x67, 0x42, 0xe0, 0x1f)
    private val pps = bytes(0x68, 0xce, 0x3c, 0x80)

    @Test
    fun `rtp header carries marker, sequence, timestamp and ssrc`() {
        val stream = RtpStream(96, 90_000, 0x11223344, 0xffff, 0)
        val first = stream.packet(bytes(1, 2, 3), marker = true, timestamp = 0x0a0b0c0d)
        val second = stream.packet(bytes(4), marker = false, timestamp = 7)

        assertEquals(0x80, u(first[0]))
        assertEquals(0x80 or 96, u(first[1]))
        assertEquals(0xffff, (u(first[2]) shl 8) or u(first[3]))
        assertEquals(0x0a0b0c0d, int(first, 4))
        assertEquals(0x11223344, int(first, 8))
        assertArrayEquals(bytes(1, 2, 3), first.copyOfRange(12, first.size))

        assertEquals(96, u(second[1]))
        assertEquals(0, (u(second[2]) shl 8) or u(second[3])) // wrapped
        assertEquals(2L, stream.packetCount)
        assertEquals(4L, stream.octetCount)
    }

    @Test
    fun `timestamps follow the clock rate from the epoch`() {
        val video = RtpStream(96, 90_000, 1, 0, 1000)
        assertEquals(1000, video.timestampAt(5_000_000_000L, 5_000_000_000L))
        assertEquals(1000 + 90_000, video.timestampAt(6_000_000_000L, 5_000_000_000L))
        val audio = RtpStream(97, 44_100, 1, 0, Int.MAX_VALUE)
        assertEquals(Int.MIN_VALUE + 44_099, audio.timestampAt(1_000_000_000L, 0)) // wraps modulo 2^32
    }

    @Test
    fun `small NAL units are sent whole and delimiters dropped`() {
        val idr = bytes(0x65, 1, 2, 3)
        val payloads = H264RtpPacketizer.payloads(annexB(aud, sps, pps, idr))
        assertEquals(3, payloads.size)
        assertArrayEquals(sps, payloads[0])
        assertArrayEquals(pps, payloads[1])
        assertArrayEquals(idr, payloads[2])
    }

    @Test
    fun `large NAL units are split into FU-A fragments`() {
        val nal = ByteArray(3500) { (it * 7).toByte() }.also { it[0] = 0x65 }
        val payloads = H264RtpPacketizer.payloads(annexB(nal), maxPayload = 1400)

        assertEquals(3, payloads.size)
        val rebuilt = ByteArrayOutputStream()
        payloads.forEachIndexed { i, payload ->
            assertTrue(payload.size <= 1400)
            assertEquals(0x60 or 28, u(payload[0])) // NRI kept, type FU-A
            val header = u(payload[1])
            assertEquals(i == 0, header and 0x80 != 0)
            assertEquals(i == payloads.lastIndex, header and 0x40 != 0)
            assertEquals(5, header and 0x1f)
            rebuilt.write(payload, 2, payload.size - 2)
        }
        assertArrayEquals(nal.copyOfRange(1, nal.size), rebuilt.toByteArray())
    }

    @Test
    fun `aac access units get an RFC 3640 AU header`() {
        val frame = ByteArray(300) { it.toByte() }
        val payloads = AacRtpPacketizer.payloads(frame)
        assertEquals(1, payloads.size)
        val payload = payloads[0]
        assertEquals(16, (u(payload[0]) shl 8) or u(payload[1]))
        assertEquals(300, ((u(payload[2]) shl 8) or u(payload[3])) shr 3)
        assertEquals(0, u(payload[3]) and 0x07)
        assertArrayEquals(frame, payload.copyOfRange(4, payload.size))
    }

    @Test
    fun `oversized aac access units are fragmented with the full size in every header`() {
        val frame = ByteArray(3000) { it.toByte() }
        val payloads = AacRtpPacketizer.payloads(frame, maxPayload = 1400)
        assertEquals(3, payloads.size)
        val rebuilt = ByteArrayOutputStream()
        for (payload in payloads) {
            assertEquals(3000, ((u(payload[2]) shl 8) or u(payload[3])) shr 3)
            rebuilt.write(payload, 4, payload.size - 4)
        }
        assertArrayEquals(frame, rebuilt.toByteArray())
        assertTrue(AacRtpPacketizer.payloads(ByteArray(0x2000)).isEmpty())
    }

    @Test
    fun `sender report maps rtp time to ntp time and carries a cname`() {
        val report = RtcpPackets.senderReport(
            ssrc = 0x01020304,
            wallClockMs = 1_000_500L,
            rtpTimestamp = 12345,
            packetCount = 10,
            octetCount = 2000,
            cname = "cam"
        )
        assertEquals(0, report.size % 4)
        assertEquals(0x80, u(report[0]))
        assertEquals(200, u(report[1]))
        assertEquals(6, (u(report[2]) shl 8) or u(report[3]))
        assertEquals(0x01020304, int(report, 4))
        assertEquals((1_000L + 2_208_988_800L).toInt(), int(report, 8))
        assertEquals(Int.MIN_VALUE, int(report, 12)) // 0.5 s as a 32-bit fraction
        assertEquals(12345, int(report, 16))
        assertEquals(10, int(report, 20))
        assertEquals(2000, int(report, 24))

        assertEquals(0x81, u(report[28]))
        assertEquals(202, u(report[29]))
        assertEquals(report.size - 28, (((u(report[30]) shl 8) or u(report[31])) + 1) * 4)
        assertEquals(0x01020304, int(report, 32))
        assertEquals(1, u(report[36]))
        assertEquals(3, u(report[37]))
        assertEquals("cam", String(report, 38, 3))
        assertEquals(0, u(report[41]))
    }

    @Test
    fun `media clock keeps monotonic timestamps`() {
        var now = 10_000_000_000L
        val clock = RtpMediaClock(nanoTime = { now })
        assertEquals(9_950_000_000L, clock.toClockNs(9_950_000L)) // already on nanoTime: no offset
        now += 33_000_000
        assertEquals(9_983_000_000L, clock.toClockNs(9_983_000L))
        assertEquals(9_983_000_001L, clock.toClockNs(9_983_000L)) // repeated pts stays increasing
    }

    @Test
    fun `media clock recognises boot clock timestamps`() {
        val now = 10_000_000_000L
        val boot = 50_000_000_000L // device slept 40 s since boot
        val clock = RtpMediaClock(nanoTime = { now }, bootClockNanos = { boot })
        assertEquals(now - 20_000_000, clock.toClockNs((boot - 20_000_000) / 1000))
    }

    @Test
    fun `media clock anchors unknown timebases and resyncs after jumps`() {
        var now = 10_000_000_000L
        val clock = RtpMediaClock(nanoTime = { now })
        assertEquals(now, clock.toClockNs(123_000_000L)) // unrelated timebase: anchored on arrival
        now += 40_000_000
        assertEquals(now, clock.toClockNs(123_040_000L))  // keeps the source spacing
        now += 40_000_000
        assertEquals(now, clock.toClockNs(999_000_000L))  // encoder restarted: re-anchored
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun u(b: Byte) = b.toInt() and 0xff

    private fun int(data: ByteArray, offset: Int) =
        (u(data[offset]) shl 24) or (u(data[offset + 1]) shl 16) or (u(data[offset + 2]) shl 8) or u(data[offset + 3])
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RtspProtocolTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0xD9.toByte(), 0x40, 0x78)
    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte(), 0x22, 0xC0.toByte())

    @Test fun parsesRequestHeadCaseInsensitively() {
        val r = RtspRequest.parseHead("DESCRIBE rtsp://1.2.3.4:8554/live RTSP/1.0\r\ncseq: 2\r\nAccept: application/sdp\r\n")!!
        assertEquals("DESCRIBE", r.method)
        assertEquals("rtsp://1.2.3.4:8554/live", r.uri)
        assertEquals("2", r.cseq)
        assertEquals("application/sdp", r.header("accept"))
        assertNull(RtspRequest.parseHead("GET / HTTP/1.1\r\n"))
    }

    @Test fun readerHandlesInterleavedFramesAndBodies() {
        val bytes = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray() +
            byteArrayOf('$'.code.toByte(), 1, 0, 3, 9, 9, 9) +
            "SET_PARAMETER rtsp://x/live RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 4\r\n\r\nab\r\n".toByteArray()
        val rd = RtspRequestReader(ByteArrayInputStream(bytes))
        val a = rd.next() as RtspRequestReader.Item.Request
        assertEquals("OPTIONS", a.request.method)
        val b = rd.next() as RtspRequestReader.Item.Interleaved
        assertEquals(1, b.channel); assertArrayEquals(byteArrayOf(9, 9, 9), b.data)
        val c = rd.next() as RtspRequestReader.Item.Request
        assertEquals("SET_PARAMETER", c.request.method)
        assertEquals("ab\r\n", String(c.request.body))
        assertNull(rd.next())
    }

    @Test fun parsesTransports() {
        val tcp = RtspTransport.parse("RTP/AVP/TCP;unicast;interleaved=2-3")!!
        assertTrue(tcp.tcp); assertEquals(2 to 3, tcp.interleaved)
        val udp = RtspTransport.parse("RTP/AVP;unicast;client_port=5000-5001")!!
        assertFalse(udp.tcp); assertEquals(5000 to 5001, udp.clientPorts)
        // First usable one wins; multicast skipped
        val multi = RtspTransport.parse("RTP/AVP;multicast, RTP/AVP/TCP;unicast;interleaved=0-1")!!
        assertTrue(multi.tcp)
        assertNull(RtspTransport.parse("RAW/RAW/UDP;unicast"))
        assertNull(RtspTransport.parse("RTP/AVP;unicast"))
    }

    @Test fun sdpContainsSpropAndProfileLevelId() {
        val sdp = RtspSdp.build(sps, pps, "10.0.0.2", 1234)
        assertTrue(sdp.contains("m=video 0 RTP/AVP 96\r\n"))
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000\r\n"))
        assertTrue(sdp.contains("profile-level-id=640028"))
        assertTrue(sdp.contains("sprop-parameter-sets=${Base64Lite.encode(sps)},${Base64Lite.encode(pps)}"))
        assertTrue(sdp.contains("a=control:trackID=0\r\n"))
        assertTrue(sdp.startsWith("v=0\r\no=- 1234 1 IN IP4 10.0.0.2\r\n"))
        assertEquals("42e01f", RtspSdp.profileLevelId(null))
        assertFalse(RtspSdp.build(null, null, "h", 1).contains("sprop"))
    }

    @Test fun base64RoundTripMatchesRfc4648() {
        assertEquals("", Base64Lite.encode(ByteArray(0)))
        assertEquals("Zg==", Base64Lite.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Lite.encode("fo".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Lite.encode("foobar".toByteArray()))
        assertEquals("foobar", String(Base64Lite.decode("Zm9vYmFy")!!))
        assertEquals("fo", String(Base64Lite.decode("Zm8=")!!))
        assertNull(Base64Lite.decode("@@"))
    }

    @Test fun digestAuthVerifiesRfc2617Example() {
        // RFC 2617 §3.5 example values (realm swapped in through a custom RtspAuth).
        val a = RtspAuth(realm = "testrealm@host.com")
        val header = """Digest username="Mufasa", realm="testrealm@host.com", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", uri="/dir/index.html", qop=auth, nc=00000001, cnonce="0a4f113b", response="6629fae49393a05397450978507c4ef1", opaque="5ccc069c403ebaf9f0171e9517f40e41""""
        assertTrue(a.verify(header, "GET", "Mufasa", "Circle Of Life", checkNonce = false))
        assertFalse(a.verify(header, "GET", "Mufasa", "wrong", checkNonce = false))
        assertFalse(a.verify(header, "GET", "Mufasa", "Circle Of Life", checkNonce = true)) // nonce not issued by us
    }

    @Test fun digestWithoutQopAndIssuedNonce() {
        val a = RtspAuth()
        val nonce = a.newNonce()
        val ha1 = RtspAuth.md5Hex("admin:${a.realm}:secret")
        val ha2 = RtspAuth.md5Hex("DESCRIBE:rtsp://h/live")
        val resp = RtspAuth.md5Hex("$ha1:$nonce:$ha2")
        val hdr = "Digest username=\"admin\", realm=\"${a.realm}\", nonce=\"$nonce\", uri=\"rtsp://h/live\", response=\"$resp\""
        assertTrue(a.verify(hdr, "DESCRIBE", "admin", "secret"))
        assertFalse(a.verify(hdr, "PLAY", "admin", "secret"))
        assertTrue(a.verify("Basic " + Base64Lite.encode("admin:secret".toByteArray()), "PLAY", "admin", "secret"))
        assertFalse(a.verify("Basic " + Base64Lite.encode("admin:nope".toByteArray()), "PLAY", "admin", "secret"))
        assertFalse(a.verify(null, "PLAY", "admin", "secret"))
    }

    @Test fun smallNalsAreSingleNalPacketsWithMarkerOnLast() {
        val au = H264Bitstream.joinNals(listOf(byteArrayOf(0x09, 0x10), sps, pps, byteArrayOf(0x65, 1, 2, 3)))
        val p = RtpH264Packetizer.packetize(au)
        assertEquals(3, p.size)   // AUD dropped
        assertArrayEquals(sps, p[0].bytes)
        assertArrayEquals(pps, p[1].bytes)
        assertEquals(listOf(false, false, true), p.map { it.marker })
    }

    @Test fun largeNalIsFragmentedAsFuA() {
        val nal = ByteArray(3000) { (it and 0x7f).toByte() }
        nal[0] = 0x65 // NRI=3, type=5
        val p = RtpH264Packetizer.packetize(H264Bitstream.joinNals(listOf(nal)), maxPayload = 1400)
        assertEquals(3, p.size)
        assertTrue(p.all { it.bytes.size <= 1400 })
        assertEquals(0x7c, p[0].bytes[0].toInt() and 0xff)          // F=0 NRI=3 type 28
        assertEquals(0x85, p[0].bytes[1].toInt() and 0xff)          // S bit + type 5
        assertEquals(0x05, p[1].bytes[1].toInt() and 0xff)          // middle
        assertEquals(0x45, p[2].bytes[1].toInt() and 0xff)          // E bit + type 5
        assertEquals(listOf(false, false, true), p.map { it.marker })
        // Reassembled payload equals NAL body without its header byte
        val body = p.flatMap { it.bytes.drop(2) }.toByteArray()
        assertArrayEquals(nal.copyOfRange(1, nal.size), body)
    }

    @Test fun rtpHeaderLayout() {
        val pkt = RtpH264Packetizer.rtpPacket(RtpH264Packetizer.Payload(byteArrayOf(0x41, 7), true), 0xABCD, 0x01020304L, 0x0A0B0C0D)
        assertEquals(0x80, pkt[0].toInt() and 0xff)
        assertEquals(0x80 or 96, pkt[1].toInt() and 0xff)
        assertEquals(0xAB, pkt[2].toInt() and 0xff); assertEquals(0xCD, pkt[3].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), pkt.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), pkt.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(0x41, 7), pkt.copyOfRange(12, 14))
    }

    @Test fun extractsParameterSets() {
        val (s, p) = RtpH264Packetizer.parameterSets(H264Bitstream.joinNals(listOf(sps, pps, byteArrayOf(0x65, 1))))
        assertNotNull(s); assertArrayEquals(sps, s); assertArrayEquals(pps, p)
    }
}

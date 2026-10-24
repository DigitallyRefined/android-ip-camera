package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.random.Random

class RtspProtocolTest {
    @Test
    fun `reads requests, skips interleaved frames and blank lines`() {
        val stream = (
            "OPTIONS rtsp://cam/live RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: test\r\n\r\n" +
                "\r\n" +
                "\$\u0001\u0000\u0003abc" +
                "SET_PARAMETER rtsp://cam/live RTSP/1.0\r\ncseq: 2\r\nContent-Length: 5\r\n\r\nhello"
            ).toByteArray(Charsets.ISO_8859_1)
        val reader = RtspRequestReader(ByteArrayInputStream(stream))

        val options = (reader.read() as RtspInbound.Request).request
        assertEquals("OPTIONS", options.method)
        assertEquals("rtsp://cam/live", options.uri)
        assertEquals("1", options.cseq)
        assertEquals("test", options.header("user-agent"))

        val interleaved = reader.read() as RtspInbound.Interleaved
        assertEquals(1, interleaved.channel)
        assertEquals(3, interleaved.length)

        val setParameter = (reader.read() as RtspInbound.Request).request
        assertEquals("2", setParameter.cseq)
        assertArrayEquals("hello".toByteArray(), setParameter.body)

        assertNull(reader.read())
    }

    @Test
    fun `cseq and session id are sanitized`() {
        val request = read("PLAY rtsp://cam/live RTSP/1.0\r\nCSeq: 7\rX-Injected: 1\r\nSession: ABCD;timeout=60\r\n\r\n")
        assertEquals("71", request.cseq)
        assertEquals("ABCD", request.sessionId)
    }

    @Test
    fun `rejects malformed and oversized requests`() {
        expectProtocolError("GARBAGE\r\n\r\n")
        expectProtocolError("DESCRIBE rtsp://cam/live HTTP/1.1\r\n\r\n")
        expectProtocolError("DESCRIBE rtsp://cam/live RTSP/1.0\r\nNoColon\r\n\r\n")
        expectProtocolError("DESCRIBE rtsp://cam/live RTSP/1.0\r\nContent-Length: 999999\r\n\r\n")
        expectProtocolError("DESCRIBE rtsp://cam/" + "a".repeat(9000) + " RTSP/1.0\r\n\r\n")
    }

    @Test
    fun `parses TCP and UDP transports`() {
        assertEquals(
            RtspTransport(true, 0 to 1, null),
            RtspTransport.parse("RTP/AVP/TCP;unicast;interleaved=0-1")
        )
        assertEquals(
            RtspTransport(true, null, null),
            RtspTransport.parse("RTP/AVP/TCP;unicast")
        )
        assertEquals(
            RtspTransport(false, null, 5000 to 5001),
            RtspTransport.parse("RTP/AVP;unicast;client_port=5000-5001;mode=\"PLAY\"")
        )
        assertEquals(
            RtspTransport(false, null, 6000 to 6001),
            RtspTransport.parse("RTP/AVP/UDP;unicast;client_port=6000")
        )
    }

    @Test
    fun `skips transports the server cannot serve`() {
        assertEquals(
            RtspTransport(true, 2 to 3, null),
            RtspTransport.parse("RTP/AVP;multicast, RTP/AVP;unicast;mode=record;client_port=1-2, RTP/AVP/TCP;interleaved=2-3")
        )
        assertNull(RtspTransport.parse("RTP/AVP;unicast"))
        assertNull(RtspTransport.parse("RAW/RAW/UDP;unicast;client_port=5000-5001"))
        assertNull(RtspTransport.parse("RTP/AVP;unicast;client_port=0-70000"))
    }

    @Test
    fun `digest responses match the RFC examples`() {
        // RFC 2069 (no qop) and RFC 2617 section 3.5 (qop=auth).
        assertEquals(
            "1949323746fe6a43ef61f9606e7febea",
            RtspDigestAuth.response("Mufasa", "CircleOfLife", "testrealm@host.com", "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093")
        )
        assertEquals(
            "6629fae49393a05397450978507c4ef1",
            RtspDigestAuth.response("Mufasa", "Circle Of Life", "testrealm@host.com", "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "00000001", "0a4f113b")
        )
    }

    @Test
    fun `parses digest parameters with quoted commas and escapes`() {
        val params = RtspDigestAuth.parseParams(
            "Digest username=\"admin\", realm=\"Android IP Camera\", nonce=\"abc\",uri=\"rtsp://cam/live?a=1,b=2\", " +
                "response=\"0123\", algorithm=MD5, opaque=\"say \\\"hi\\\"\""
        )
        assertEquals("admin", params["username"])
        assertEquals("Android IP Camera", params["realm"])
        assertEquals("abc", params["nonce"])
        assertEquals("rtsp://cam/live?a=1,b=2", params["uri"])
        assertEquals("0123", params["response"])
        assertEquals("MD5", params["algorithm"])
        assertEquals("say \"hi\"", params["opaque"])
    }

    @Test
    fun `nonces verify, age and reject tampering`() {
        var now = 1_700_000_000_000L
        val nonces = RtspNonces { now }
        val nonce = nonces.issue()
        assertEquals(0L, nonces.ageMs(nonce))
        now += 90_000
        assertEquals(90_000L, nonces.ageMs(nonce))

        val tampered = nonce.substring(0, 15) + (if (nonce[15] == '0') '1' else '0') + nonce.substring(16)
        assertNull(nonces.ageMs(tampered))
        assertNull(nonces.ageMs("short"))
        assertNull(RtspNonces { now }.ageMs(nonce)) // signed by a different server instance
    }

    @Test
    fun `aac audio specific config`() {
        assertEquals("1208", AacFormat(44100, 1).audioSpecificConfig())
        assertEquals("1190", AacFormat(48000, 2).audioSpecificConfig())
    }

    @Test
    fun `sdp describes h264 and aac tracks`() {
        val sps = byteArrayOf(0x67, 0x42, 0xe0.toByte(), 0x1f, 0x11)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
        val sdp = RtspSdp.build("192.168.1.5", 42, sps, pps, AacFormat(44100, 1))

        assertTrue(sdp.endsWith("\r\n"))
        val lines = sdp.split("\r\n")
        assertTrue(lines.contains("o=- 42 42 IN IP4 192.168.1.5"))
        assertTrue(lines.contains("m=video 0 RTP/AVP 96"))
        assertTrue(lines.contains("a=rtpmap:96 H264/90000"))
        assertTrue(lines.contains(
            "a=fmtp:96 packetization-mode=1;profile-level-id=42e01f;sprop-parameter-sets=" +
                Base64.getEncoder().encodeToString(sps) + "," + Base64.getEncoder().encodeToString(pps)
        ))
        assertTrue(lines.contains("a=control:trackID=0"))
        assertTrue(lines.contains("a=rtpmap:97 MPEG4-GENERIC/44100/1"))
        assertTrue(lines.any { it.startsWith("a=fmtp:97 ") && it.contains("mode=AAC-hbr") && it.contains("config=1208") })
        assertTrue(lines.contains("a=control:trackID=1"))
    }

    @Test
    fun `sdp without parameter sets or audio`() {
        val sdp = RtspSdp.build("10.0.0.1", 1, null, null, null)
        assertTrue(sdp.contains("a=fmtp:96 packetization-mode=1\r\n"))
        assertTrue(!sdp.contains("m=audio"))
    }

    @Test
    fun `base64 matches the JDK`() {
        val random = Random(1)
        for (size in 0..40) {
            val data = random.nextBytes(size)
            val encoded = RtspBase64.encode(data)
            assertEquals(Base64.getEncoder().encodeToString(data), encoded)
            assertArrayEquals(data, RtspBase64.decode(encoded))
        }
        assertNotNull(RtspBase64.decode("YWRtaW46U2VjcmV0MTIz"))
        assertNull(RtspBase64.decode("abc"))
        assertNull(RtspBase64.decode("ab=c"))
        assertNull(RtspBase64.decode("a\$bc"))
    }

    private fun read(text: String): RtspRequest =
        (RtspRequestReader(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))).read() as RtspInbound.Request).request

    private fun expectProtocolError(text: String) {
        try {
            read(text)
            fail("Expected RtspProtocolException for: ${text.take(40)}")
        } catch (_: RtspProtocolException) {
        }
    }
}

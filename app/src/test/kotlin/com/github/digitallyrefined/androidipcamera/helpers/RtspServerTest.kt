package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class RtspServerTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40, 0x16, 0xE8.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = H264Bitstream.joinNals(listOf(sps, pps, ByteArray(3000) { 0x22 }.also { it[0] = 0x65 }))
    private val pFrame = H264Bitstream.joinNals(listOf(byteArrayOf(0x41, 0x9A.toByte(), 1, 2, 3)))

    private lateinit var server: RtspServer
    private val counts = CopyOnWriteArrayList<Int>()
    private val feeding = AtomicBoolean(true)
    private var port = 0

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startServer(creds: Pair<String, String>?) {
        port = freePort()
        server = RtspServer(port, { creds }, { counts += it })
        server.start()
        // Simulated encoder: keyframe every 10 frames at ~30 fps
        Thread {
            var i = 0
            while (feeding.get()) {
                server.onAccessUnit(if (i % 10 == 0) idr else pFrame, i % 10 == 0)
                i++
                Thread.sleep(33)
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun tearDown() { feeding.set(false); server.stop() }

    private class Client(port: Int) {
        val sock = Socket("127.0.0.1", port).apply { soTimeout = 8000 }
        val input = DataInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        var cseq = 0

        fun request(method: String, uri: String, headers: List<String> = emptyList()): Pair<String, String> {
            val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\n")
            headers.forEach { sb.append(it).append("\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray()); out.flush()
            return readResponse()
        }

        fun readResponse(): Pair<String, String> {
            // Skip any interleaved RTP that may precede the response.
            while (true) {
                input.mark(1)
                val b = input.read()
                if (b == '$'.code) { skipInterleavedBody(); continue }
                val head = StringBuilder().append(b.toChar())
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(len).also { input.readFully(it) }
                return head.toString() to String(body)
            }
        }

        private fun skipInterleavedBody() { input.read(); val len = input.readUnsignedShort(); input.skipBytes(len) }

        fun readInterleaved(): Pair<Int, ByteArray> {
            val b = input.read()
            require(b == '$'.code) { "expected interleaved frame, got $b" }
            val ch = input.read(); val len = input.readUnsignedShort()
            return ch to ByteArray(len).also { input.readFully(it) }
        }
    }

    @Test fun tcpInterleavedSessionReceivesRtp() {
        startServer(null)
        Thread.sleep(150) // let a keyframe cache SPS/PPS
        val c = Client(port)
        val url = "rtsp://127.0.0.1:$port/live"
        val (opt, _) = c.request("OPTIONS", url)
        assertTrue(opt.startsWith("RTSP/1.0 200 OK")); assertTrue(opt.contains("CSeq: 1")); assertTrue(opt.contains("DESCRIBE"))

        val (desc, sdp) = c.request("DESCRIBE", url, listOf("Accept: application/sdp"))
        assertTrue(desc.startsWith("RTSP/1.0 200"))
        assertTrue(desc.contains("Content-Base: $url/"))
        assertTrue(desc.contains("Content-Type: application/sdp"))
        assertTrue(sdp.contains("sprop-parameter-sets=${Base64Lite.encode(sps)},${Base64Lite.encode(pps)}"))
        assertTrue(sdp.contains("profile-level-id=42c01f"))

        val (setup, _) = c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1"))
        assertTrue(setup, setup.startsWith("RTSP/1.0 200"))
        assertTrue(setup.contains("interleaved=0-1")); assertTrue(setup.contains(";ssrc="))
        val session = Regex("Session: ([0-9A-F]+);timeout=60").find(setup)!!.groupValues[1]
        assertEquals(0, server.let { if (it.hasClients()) 1 else 0 }) // SETUP alone is not playing

        val (play, _) = c.request("PLAY", url, listOf("Session: $session", "Range: npt=0.000-"))
        assertTrue(play.startsWith("RTSP/1.0 200")); assertTrue(play.contains("RTP-Info: url=$url/trackID=0;seq="))
        assertTrue(server.hasClients())

        // First packets must start with SPS (keyframe sync), share timestamp, have increasing seq.
        val all = (1..14).map { c.readInterleaved() }
        // RTCP sender reports arrive on the odd channel; RTP on channel 0.
        val rtcp = all.filter { it.first == 1 }
        assertTrue(rtcp.isNotEmpty())
        assertEquals(200, rtcp.first().second[1].toInt() and 0xff)   // SR
        val pkts = all.filter { it.first == 0 }.take(12)
        assertTrue(pkts.size >= 10)
        val first = pkts[0].second
        assertEquals(0x80, first[0].toInt() and 0xff)
        assertEquals(96, first[1].toInt() and 0x7f)
        assertEquals(7, first[12].toInt() and 0x1f)
        val seqs = pkts.map { ((it.second[2].toInt() and 0xff) shl 8) or (it.second[3].toInt() and 0xff) }
        for (i in 1 until seqs.size) assertEquals((seqs[i - 1] + 1) and 0xffff, seqs[i])
        assertTrue(pkts.any { it.second[1].toInt() and 0x80 != 0 })   // marker seen

        val (td, _) = c.request("TEARDOWN", url, listOf("Session: $session"))
        assertTrue(td.startsWith("RTSP/1.0 200"))
        Thread.sleep(100)
        assertTrue(!server.hasClients())
        assertTrue(counts.contains(1) && counts.last() == 0)
        c.sock.close()
    }

    @Test fun digestAuthIsRequired() {
        startServer("admin" to "pw")
        val c = Client(port)
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("OPTIONS", url).first.startsWith("RTSP/1.0 200"))
        val (r401, _) = c.request("DESCRIBE", url)
        assertTrue(r401.startsWith("RTSP/1.0 401"))
        val nonce = Regex("nonce=\"([0-9a-f]+)\"").find(r401)!!.groupValues[1]
        val ha1 = RtspAuth.md5Hex("admin:AndroidIPCamera:pw")
        val resp = RtspAuth.md5Hex("$ha1:$nonce:${RtspAuth.md5Hex("DESCRIBE:$url")}")
        val (ok, _) = c.request("DESCRIBE", url, listOf(
            "Authorization: Digest username=\"admin\", realm=\"AndroidIPCamera\", nonce=\"$nonce\", uri=\"$url\", response=\"$resp\""))
        assertTrue(ok, ok.startsWith("RTSP/1.0 200"))
        c.sock.close()
    }

    @Test fun describeWaitsForFirstKeyframeAndReportsDemand() {
        port = freePort()
        server = RtspServer(port, { null }, { counts += it })
        server.start()
        feeding.set(false)
        Thread {
            // Encoder only starts once a client signals demand.
            while (counts.isEmpty()) Thread.sleep(10)
            server.onAccessUnit(idr, true)
        }.apply { isDaemon = true; start() }
        val c = Client(port)
        val (desc, sdp) = c.request("DESCRIBE", "rtsp://127.0.0.1:$port/live")
        assertTrue(desc.startsWith("RTSP/1.0 200"))
        assertTrue(sdp.contains("sprop-parameter-sets="))
        assertEquals(1, counts.first())
        c.sock.close()
    }

    @Test fun unknownSessionAndBadTransport() {
        startServer(null)
        val c = Client(port)
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("PLAY", url, listOf("Session: DEADBEEF")).first.startsWith("RTSP/1.0 454"))
        assertTrue(c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP;multicast")).first.startsWith("RTSP/1.0 461"))
        assertTrue(c.request("RECORD", url).first.startsWith("RTSP/1.0 501"))
        c.sock.close()
    }

    @Suppress("unused") private fun InputStream.drain() { while (available() > 0) read() }
}

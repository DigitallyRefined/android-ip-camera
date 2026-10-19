package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Drives the real server over loopback sockets with a scripted client. */
class RtspServerTest {
    private val host = FakeHost()
    private lateinit var server: RtspServer
    private var feeder: Thread? = null
    @Volatile private var feeding = false

    private val sps = byteArrayOf(0x67, 0x42, 0xe0.toByte(), 0x1f, 0x0a)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    private val keyFrame = annexB(sps, pps, ByteArray(3000) { (it * 7 + 1).toByte() }.also { it[0] = 0x65 })
    private val deltaFrame = annexB(ByteArray(200) { (it * 7 + 1).toByte() }.also { it[0] = 0x41 })

    @Before
    fun setUp() {
        server = RtspServer(host)
        server.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull("server did not bind", server.boundPort())
    }

    @After
    fun tearDown() {
        feeding = false
        feeder?.join(1_000)
        server.stop()
    }

    @Test
    fun `options works without credentials, describe needs digest`() {
        client().use { c ->
            val options = c.request("OPTIONS", "*")
            assertEquals(200, options.status)
            assertTrue(options.headers["public"]!!.contains("DESCRIBE"))

            val challenge = c.request("DESCRIBE", c.url)
            assertEquals(401, challenge.status)
            assertTrue(challenge.headers["www-authenticate"]!!.startsWith("Digest realm=\"Android IP Camera\""))
        }
    }

    @Test
    fun `describe returns sdp with live parameter sets and audio`() {
        server.onVideoFrame(keyFrame, true, nowUs())
        client().use { c ->
            val describe = c.authenticated("DESCRIBE", c.url)
            assertEquals(200, describe.status)
            assertEquals("application/sdp", describe.headers["content-type"])
            assertEquals("${c.url}/", describe.headers["content-base"])
            val sdp = String(describe.body)
            assertTrue(sdp, sdp.contains("sprop-parameter-sets=" + RtspBase64.encode(sps) + "," + RtspBase64.encode(pps)))
            assertTrue(sdp, sdp.contains("a=rtpmap:97 MPEG4-GENERIC/44100/1"))
            assertTrue(server.hasVideoClients())
            assertEquals(1, host.connected.get())
        }
        waitFor { !server.hasVideoClients() && host.disconnected.get() == 1 }
    }

    @Test
    fun `plays video and audio interleaved over tcp starting at a keyframe`() {
        server.onVideoFrame(keyFrame, true, nowUs())
        client().use { c ->
            assertEquals(200, c.authenticated("DESCRIBE", c.url).status)
            val video = c.authenticated("SETUP", "${c.url}/trackID=0", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
            assertEquals(200, video.status)
            assertTrue(video.headers["transport"]!!.startsWith("RTP/AVP/TCP;unicast;interleaved=0-1;ssrc="))
            val session = video.headers["session"]!!.substringBefore(';')
            val audio = c.authenticated("SETUP", "${c.url}/trackID=1",
                "Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3", "Session" to session)
            assertEquals(200, audio.status)
            val play = c.authenticated("PLAY", "${c.url}/", "Session" to session)
            assertEquals(200, play.status)
            assertTrue(play.headers["rtp-info"]!!.contains("url=${c.url}/trackID=0;seq="))
            startFeeding()

            val packets = (1..60).map { c.readInterleaved() }
            val videoPackets = packets.filter { it.first == 0 }.map { it.second }
            val audioPackets = packets.filter { it.first == 2 }.map { it.second }
            assertTrue(videoPackets.isNotEmpty())
            assertTrue(audioPackets.isNotEmpty())
            assertTrue(packets.any { it.first == 1 && (it.second[1].toInt() and 0xff) == 200 }) // RTCP SR

            // Delta frames are held back until the first keyframe: the stream starts with its SPS.
            assertEquals(7, videoPackets.first()[12].toInt() and 0x1f)
            assertEquals(96, videoPackets.first()[1].toInt() and 0x7f)
            assertFalse(marker(videoPackets[0]))
            assertFalse(marker(videoPackets[1]))
            // SPS, PPS, then the IDR split into three FU-A fragments; the last one ends the frame.
            assertEquals(28, videoPackets[2][12].toInt() and 0x1f)
            assertTrue(marker(videoPackets[4]))
            assertEquals(seq(videoPackets[0]) + 4, seq(videoPackets[4]))

            assertEquals(97, audioPackets.first()[1].toInt() and 0x7f)
            assertTrue(marker(audioPackets.first()))
            assertEquals(16, audioPackets.first()[13].toInt())

            stopFeeding()
            assertTrue(host.audioStarted.get() == 1)
            val teardown = c.authenticated("TEARDOWN", "${c.url}/", "Session" to session)
            assertEquals(200, teardown.status)
            waitFor { !server.hasVideoClients() && host.audioStopped.get() == 1 }
        }
    }

    @Test
    fun `plays over udp`() {
        server.onVideoFrame(keyFrame, true, nowUs())
        DatagramSocket(0).use { rtp ->
            DatagramSocket(0).use { rtcp ->
                rtp.soTimeout = 5_000
                client().use { c ->
                    c.authenticated("DESCRIBE", c.url)
                    val setup = c.authenticated("SETUP", "${c.url}/trackID=0",
                        "Transport" to "RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}")
                    assertEquals(200, setup.status)
                    val transport = setup.headers["transport"]!!
                    assertTrue(transport, transport.contains("client_port=${rtp.localPort}-${rtcp.localPort};server_port="))
                    val serverRtp = transport.substringAfter("server_port=").substringBefore('-').toInt()
                    assertEquals(0, serverRtp % 2)
                    val session = setup.headers["session"]!!.substringBefore(';')
                    assertEquals(200, c.authenticated("PLAY", "${c.url}/", "Session" to session).status)
                    startFeeding()

                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    rtp.receive(packet)
                    assertEquals(96, buffer[1].toInt() and 0x7f)
                    assertEquals(7, buffer[12].toInt() and 0x1f)
                    assertEquals(serverRtp, packet.port)
                }
            }
        }
    }

    @Test
    fun `wrong passwords are counted and the address gets blocked`() {
        client().use { c ->
            c.password = "Wrong1234"
            repeat(4) { assertEquals(401, c.authenticated("DESCRIBE", c.url).status) }
            assertEquals(403, c.authenticated("DESCRIBE", c.url).status)
        }
        client().use { c -> assertEquals(403, c.request("OPTIONS", "*").status) }
    }

    @Test
    fun `basic credentials sent up front are accepted`() {
        server.onVideoFrame(keyFrame, true, nowUs())
        client().use { c ->
            val basic = RtspBase64.encode("admin:Secret123".toByteArray())
            assertEquals(200, c.request("DESCRIBE", c.url, "Authorization" to "Basic $basic").status)
        }
    }

    @Test
    fun `refuses media requests while streaming is disabled`() {
        host.streaming = false
        client().use { c -> assertEquals(503, c.authenticated("DESCRIBE", c.url).status) }
    }

    @Test
    fun `refuses everything when credentials are not configured`() {
        host.credentials = RtspCredentials.NotConfigured
        client().use { c -> assertEquals(403, c.request("DESCRIBE", c.url).status) }
    }

    private fun startFeeding() {
        feeding = true
        feeder = Thread {
            var i = 0
            while (feeding) {
                server.onVideoFrame(if (i % 5 == 2) keyFrame else deltaFrame, i % 5 == 2, nowUs())
                host.audioSink.get()?.invoke(ByteArray(180) { it.toByte() }, nowUs())
                i++
                Thread.sleep(20)
            }
        }.apply { start() }
    }

    private fun stopFeeding() {
        feeding = false
        feeder?.join(1_000)
    }

    private fun client() = TestClient(server.boundPort()!!)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }

    private fun nowUs() = System.nanoTime() / 1000

    private fun marker(packet: ByteArray) = packet[1].toInt() and 0x80 != 0

    private fun seq(packet: ByteArray) = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private class FakeHost : RtspServer.Host {
        @Volatile var streaming = true
        @Volatile var credentials: RtspCredentials = RtspCredentials.Required("admin", "Secret123")
        val connected = AtomicInteger()
        val disconnected = AtomicInteger()
        val audioStarted = AtomicInteger()
        val audioStopped = AtomicInteger()
        val audioSink = AtomicReference<((ByteArray, Long) -> Unit)?>()

        override fun port() = 0
        override fun credentials() = credentials
        override fun isStreamingEnabled() = streaming
        override fun audioFormat() = AacFormat(44100, 1)
        override fun createAudioSource(onAccessUnit: (ByteArray, Long) -> Unit) = object : RtspAudioSource {
            override fun start() {
                audioStarted.incrementAndGet()
                audioSink.set(onAccessUnit)
            }

            override fun stop() {
                audioStopped.incrementAndGet()
                audioSink.set(null)
            }
        }
        override fun onClientConnected() { connected.incrementAndGet() }
        override fun onClientDisconnected() { disconnected.incrementAndGet() }
        override fun requestKeyFrame() {}
        override fun log(message: String) {}
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private class TestClient(port: Int) : Closeable {
        val url = "rtsp://127.0.0.1:$port/live"
        var password = "Secret123"
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private var cseq = 0
        private var nonce: String? = null

        fun request(method: String, uri: String, vararg headers: Pair<String, String>): Response {
            val text = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\n")
            for ((name, value) in headers) text.append("$name: $value\r\n")
            text.append("\r\n")
            output.write(text.toString().toByteArray())
            output.flush()
            return readResponse()
        }

        /** Sends with a Digest Authorization header, fetching a fresh challenge when needed. */
        fun authenticated(method: String, uri: String, vararg headers: Pair<String, String>): Response {
            if (nonce == null) {
                val challenge = request(method, uri, *headers)
                if (challenge.status != 401) return challenge
                nonce = RtspDigestAuth.parseParams(challenge.headers["www-authenticate"]!!)["nonce"]
            }
            val response = RtspDigestAuth.response("admin", password, RtspDigestAuth.REALM, method, uri, nonce!!)
            val authorization = "Digest username=\"admin\", realm=\"${RtspDigestAuth.REALM}\", " +
                "nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
            return request(method, uri, *headers, "Authorization" to authorization)
        }

        fun readInterleaved(): Pair<Int, ByteArray> {
            while (true) {
                val first = input.read()
                if (first < 0) throw EOFException()
                if (first != '$'.code) throw AssertionError("Expected interleaved frame, got ${first.toChar()}")
                val channel = input.read()
                val length = (input.read() shl 8) or input.read()
                val data = ByteArray(length)
                var offset = 0
                while (offset < length) offset += input.read(data, offset, length - offset)
                return channel to data
            }
        }

        private fun readResponse(): Response {
            // RTP still in flight is interleaved ahead of the reply; skip it.
            var first = input.read()
            while (first == '$'.code) {
                input.read()
                var remaining = (input.read() shl 8) or input.read()
                while (remaining-- > 0) input.read()
                first = input.read()
            }
            if (first < 0) throw EOFException()
            val statusLine = first.toChar() + readLine()
            val status = statusLine.split(' ')[1].toInt()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var offset = 0
            while (offset < body.size) offset += input.read(body, offset, body.size - offset)
            return Response(status, headers, body)
        }

        private fun readLine(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toString(Charsets.ISO_8859_1.name())
        }

        override fun close() = socket.close()
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal RTSP 1.0 server (RFC 2326) publishing the live H.264 stream as RTP (RFC 6184), over
 * TCP-interleaved or UDP unicast. Feed it access units with [onAccessUnit]; it never blocks the
 * caller — each session has a bounded queue and its own writer thread.
 *
 * Any request path is accepted; `/live` is the advertised one.
 */
class RtspServer(
    private val port: Int = 8554,
    private val credentials: () -> Pair<String, String>?,
    private val onClientsChanged: (activeCount: Int) -> Unit,
    private val onLog: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val auth = RtspAuth()
    private val random = SecureRandom()
    private val clock0 = System.nanoTime()
    private val sessionVersion = System.currentTimeMillis() / 1000

    // Cached parameter sets from the latest keyframe.
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    private val paramLock = java.util.concurrent.locks.ReentrantLock()
    private val paramsReady = paramLock.newCondition()

    /** Connections currently blocked in DESCRIBE waiting for the first keyframe. */
    private val describeWaiters = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastReportedCount = -1

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        openUdpPair()
        thread("RtspAccept") { acceptLoop(ss) }
        rtcpSocket?.let { s -> thread("RtspRtcp") { rtcpLoop(s) } }
        thread("RtspReaper") { reaperLoop() }
        onLog("RTSP server listening on port $port")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        connections.toList().forEach { it.close() }
        sessions.values.toList().forEach { endSession(it) }
        try { rtpSocket?.close() } catch (_: Exception) {}
        try { rtcpSocket?.close() } catch (_: Exception) {}
        rtpSocket = null; rtcpSocket = null
        paramLock.lock(); try { paramsReady.signalAll() } finally { paramLock.unlock() }
        reportClients()
    }

    /** Port actually bound (useful when constructed with port 0 in tests). */
    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun hasClients(): Boolean = activeCount() > 0

    private fun activeCount(): Int = describeWaiters.get() + sessions.values.count { it.playing }

    /** Hand one Annex-B access unit to all playing sessions. Never blocks. */
    fun onAccessUnit(annexB: ByteArray, isKey: Boolean) {
        if (!running.get()) return
        val hasIdr = isKey || H264Bitstream.containsNalType(annexB, 5)
        if (hasIdr) {
            val (s, p) = RtpH264Packetizer.parameterSets(annexB)
            if (s != null || p != null) {
                paramLock.lock()
                try {
                    if (s != null) sps = s
                    if (p != null) pps = p
                    paramsReady.signalAll()
                } finally { paramLock.unlock() }
            }
        }
        val playing = sessions.values.filter { it.playing }
        if (playing.isEmpty()) return
        val ts = ((System.nanoTime() - clock0) / 1000L * 90L / 1000L) and 0xffffffffL
        val payloads = RtpH264Packetizer.packetize(annexB)
        if (payloads.isEmpty()) return
        val frame = Frame(payloads, ts, hasIdr)
        for (s in playing) s.enqueue(frame)
    }

    // ---------------------------------------------------------------- networking

    private fun openUdpPair() {
        // RTP on an even port, RTCP on the next one (RFC 3550 §11).
        for (attempt in 0 until 50) {
            val base = 50000 + 2 * random.nextInt(5000)
            try {
                val a = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(base)) }
                try {
                    val b = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(base + 1)) }
                    rtpSocket = a; rtcpSocket = b
                    return
                } catch (_: SocketException) { a.close() }
            } catch (_: SocketException) {}
        }
        onLog("RTSP: no UDP port pair available, TCP interleaved only")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try { ss.accept() } catch (_: Exception) { break }
            try {
                sock.tcpNoDelay = true
                sock.keepAlive = true
                val c = Connection(sock)
                connections += c
                thread("RtspConn-${sock.inetAddress.hostAddress}") { c.run() }
            } catch (_: Exception) { try { sock.close() } catch (_: Exception) {} }
        }
    }

    private fun rtcpLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try { s.receive(pkt) } catch (_: Exception) { break }
            val from = pkt.address
            sessions.values.filter { !it.tcp && it.udpAddr == from }.forEach { it.touch() }
        }
    }

    private fun reaperLoop() {
        while (running.get()) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            for (s in sessions.values) {
                // TCP sessions live as long as their connection; UDP ones need keepalives.
                if (!s.tcp && now - s.lastActivity > SESSION_TIMEOUT_S * 1000L) {
                    onLog("RTSP session ${s.id} timed out")
                    endSession(s)
                }
            }
        }
    }

    private fun reportClients() {
        val n = activeCount()
        if (n != lastReportedCount) {
            lastReportedCount = n
            try { onClientsChanged(n) } catch (e: Exception) { onLog("onClientsChanged: ${e.message}") }
        }
    }

    private fun endSession(s: Session) {
        if (sessions.remove(s.id) != null) {
            s.shutdown()
            reportClients()
        }
    }

    // ---------------------------------------------------------------- sessions

    private class Frame(val payloads: List<RtpH264Packetizer.Payload>, val timestamp: Long, val key: Boolean)

    private inner class Session(val id: String, val connection: Connection, val transport: RtspTransport) {
        val tcp = transport.tcp
        val ssrc = random.nextInt()
        @Volatile var seq = random.nextInt(0x10000)
        @Volatile var playing = false
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile private var waitingKey = true
        private val queue = ArrayBlockingQueue<Frame>(QUEUE_CAPACITY)
        private var writer: Thread? = null
        val udpAddr: InetAddress? = if (tcp) null else connection.socket.inetAddress
        val rtpChannel = transport.interleaved?.first ?: 0
        val rtcpChannel = transport.interleaved?.second ?: (rtpChannel + 1)
        private var packetCount = 0L
        private var octetCount = 0L
        private var lastSrMs = 0L

        fun touch() { lastActivity = System.currentTimeMillis() }

        /** Initial RTP timestamp advertised in RTP-Info; the next frame will be close to it. */
        fun currentTimestamp(): Long = ((System.nanoTime() - clock0) / 1000L * 90L / 1000L) and 0xffffffffL

        fun play() {
            waitingKey = true
            playing = true
            if (writer == null) writer = thread("RtspWriter-$id") { writeLoop() }
        }

        fun pause() { playing = false; queue.clear() }

        fun enqueue(f: Frame) {
            if (waitingKey && !f.key) return
            if (!queue.offer(f)) {
                // Client can't keep up: drop the backlog and resync on the next keyframe.
                queue.clear()
                waitingKey = true
                if (f.key) { queue.offer(f); waitingKey = false }
                return
            }
            if (f.key) waitingKey = false
        }

        private fun writeLoop() {
            while (running.get() && sessions.containsKey(id)) {
                val f = try { queue.poll(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { break } ?: continue
                try {
                    for (p in f.payloads) {
                        val pkt = RtpH264Packetizer.rtpPacket(p, seq, f.timestamp, ssrc)
                        seq = (seq + 1) and 0xffff
                        if (tcp) connection.writeInterleaved(rtpChannel, pkt) else sendUdp(pkt)
                        packetCount++; octetCount += pkt.size - 12
                    }
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastSrMs >= SR_INTERVAL_MS) {
                        lastSrMs = nowMs
                        val sr = RtpH264Packetizer.rtcpSenderReport(ssrc, nowMs, currentTimestamp(), packetCount, octetCount)
                        if (tcp) connection.writeInterleaved(rtcpChannel, sr) else sendUdpRtcp(sr)
                    }
                } catch (e: Exception) {
                    onLog("RTSP session $id write failed: ${e.message}")
                    endSession(this)
                    break
                }
            }
        }

        private fun sendUdp(pkt: ByteArray) {
            val s = rtpSocket ?: throw SocketException("no UDP socket")
            val port = transport.clientPorts?.first ?: throw SocketException("no client port")
            s.send(DatagramPacket(pkt, pkt.size, udpAddr, port))
        }

        private fun sendUdpRtcp(pkt: ByteArray) {
            val s = rtcpSocket ?: return
            val port = transport.clientPorts?.second ?: return
            s.send(DatagramPacket(pkt, pkt.size, udpAddr, port))
        }

        fun shutdown() {
            playing = false
            queue.clear()
            writer?.interrupt()
        }
    }

    // ---------------------------------------------------------------- connection

    private inner class Connection(val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        private val writeLock = Any()
        private val mySessions = mutableSetOf<String>()

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }

        fun writeInterleaved(channel: Int, data: ByteArray) {
            val hdr = byteArrayOf('$'.code.toByte(), channel.toByte(), (data.size shr 8).toByte(), data.size.toByte())
            synchronized(writeLock) {
                out.write(hdr); out.write(data); out.flush()
            }
        }

        private fun writeResponse(text: String, body: ByteArray? = null) {
            synchronized(writeLock) {
                out.write(text.toByteArray(Charsets.ISO_8859_1))
                if (body != null) out.write(body)
                out.flush()
            }
        }

        fun run() {
            val reader = RtspRequestReader(socket.getInputStream())
            try {
                while (running.get()) {
                    when (val item = reader.next() ?: break) {
                        is RtspRequestReader.Item.Interleaved -> touchAll()  // RTCP RR over TCP
                        is RtspRequestReader.Item.Malformed -> respond(null, 400, "Bad Request")
                        is RtspRequestReader.Item.Request -> handle(item.request)
                    }
                }
            } catch (_: Exception) {
            } finally {
                connections.remove(this)
                synchronized(mySessions) { mySessions.toList() }.forEach { id -> sessions[id]?.let { endSession(it) } }
                close()
            }
        }

        private fun touchAll() {
            synchronized(mySessions) { mySessions.toList() }.forEach { sessions[it]?.touch() }
        }

        private fun respond(req: RtspRequest?, code: Int, reason: String, headers: List<String> = emptyList(), body: String? = null, contentType: String? = null) {
            val sb = StringBuilder("RTSP/1.0 $code $reason\r\n")
            req?.cseq?.let { sb.append("CSeq: $it\r\n") }
            sb.append("Date: ${httpDate()}\r\n")
            sb.append("Server: AndroidIPCamera\r\n")
            headers.forEach { sb.append(it).append("\r\n") }
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                if (contentType != null) sb.append("Content-Type: $contentType\r\n")
                sb.append("Content-Length: ${bytes.size}\r\n")
            }
            sb.append("\r\n")
            writeResponse(sb.toString(), bytes)
        }

        private fun authorized(req: RtspRequest): Boolean {
            val cred = credentials() ?: return true
            if (cred.first.isBlank()) return true
            return auth.verify(req.header("Authorization"), req.method, cred.first, cred.second)
        }

        private fun baseUrl(req: RtspRequest): String {
            val uri = req.uri
            // Strip a trailing track control so Content-Base always points at the presentation.
            val trimmed = uri.substringBefore('?').trimEnd('/')
                .removeSuffix("/${RtspSdp.TRACK_CONTROL}")
            return if (trimmed.startsWith("rtsp://", true)) "$trimmed/" else
                "rtsp://${socket.localAddress.hostAddress}:${socket.localPort}${if (trimmed.startsWith('/')) trimmed else "/live"}/"
        }

        private fun sessionFor(req: RtspRequest): Session? {
            val id = req.header("Session")?.substringBefore(';')?.trim() ?: return null
            return sessions[id]?.takeIf { it.connection === this || !it.tcp }
        }

        private fun handle(req: RtspRequest) {
            if (req.version != "RTSP/1.0") return respond(req, 505, "RTSP Version Not Supported")
            sessionFor(req)?.touch()
            if (req.method != "OPTIONS" && !authorized(req)) {
                return respond(req, 401, "Unauthorized", auth.challengeHeaders())
            }
            when (req.method) {
                "OPTIONS" -> respond(req, 200, "OK", listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"))
                "DESCRIBE" -> describe(req)
                "SETUP" -> setup(req)
                "PLAY" -> play(req)
                "PAUSE" -> {
                    val s = sessionFor(req) ?: return respond(req, 454, "Session Not Found")
                    s.pause(); reportClients()
                    respond(req, 200, "OK", listOf(sessionHeader(s)))
                }
                "TEARDOWN" -> {
                    val s = sessionFor(req)
                    if (s != null) { synchronized(mySessions) { mySessions.remove(s.id) }; endSession(s) }
                    respond(req, 200, "OK", if (s != null) listOf("Session: ${s.id}") else emptyList())
                }
                "GET_PARAMETER", "SET_PARAMETER" -> {
                    val s = sessionFor(req)
                    respond(req, 200, "OK", if (s != null) listOf(sessionHeader(s)) else emptyList())
                }
                else -> respond(req, 501, "Not Implemented")
            }
        }

        private fun describe(req: RtspRequest) {
            val accept = req.header("Accept")
            if (accept != null && !accept.contains("application/sdp", true) && !accept.contains("*/*")) {
                return respond(req, 406, "Not Acceptable")
            }
            if (sps == null || pps == null) {
                // Ask the host to bring the camera/encoder up, then wait for the first keyframe.
                describeWaiters.incrementAndGet()
                reportClients()
                try {
                    val deadline = System.currentTimeMillis() + DESCRIBE_WAIT_MS
                    paramLock.lock()
                    try {
                        while ((sps == null || pps == null) && running.get()) {
                            val left = deadline - System.currentTimeMillis()
                            if (left <= 0) break
                            paramsReady.await(left, TimeUnit.MILLISECONDS)
                        }
                    } finally { paramLock.unlock() }
                } finally {
                    describeWaiters.decrementAndGet()
                    // Defer reporting until the session either SETUP/PLAYs or never comes back:
                    // reporting 0 now would make the host tear the camera down between DESCRIBE and PLAY.
                    thread("RtspDescribeGrace") {
                        try { Thread.sleep(DESCRIBE_GRACE_MS) } catch (_: InterruptedException) {}
                        reportClients()
                    }
                }
                if (sps == null || pps == null) onLog("RTSP DESCRIBE: no SPS/PPS yet, sending SDP without sprop-parameter-sets")
            }
            val host = socket.localAddress.hostAddress ?: "0.0.0.0"
            val sdp = RtspSdp.build(sps, pps, host, sessionVersion)
            respond(req, 200, "OK", listOf("Content-Base: ${baseUrl(req)}"), sdp, "application/sdp")
        }

        private fun setup(req: RtspRequest) {
            val t = RtspTransport.parse(req.header("Transport")) ?: return respond(req, 461, "Unsupported Transport")
            if (!t.tcp && (rtpSocket == null || t.clientPorts == null)) return respond(req, 461, "Unsupported Transport")
            val existing = sessionFor(req)
            if (existing != null) {
                // We only have one track; re-SETUP of the same session just echoes the transport.
                return respond(req, 200, "OK", listOf(transportHeader(existing), sessionHeader(existing)))
            }
            val id = newSessionId()
            val s = Session(id, this, t)
            sessions[id] = s
            synchronized(mySessions) { mySessions += id }
            respond(req, 200, "OK", listOf(transportHeader(s), sessionHeader(s)))
        }

        private fun play(req: RtspRequest) {
            val s = sessionFor(req) ?: return respond(req, 454, "Session Not Found")
            val ts = s.currentTimestamp()
            val url = baseUrl(req) + RtspSdp.TRACK_CONTROL
            respond(req, 200, "OK", listOf(
                sessionHeader(s),
                "Range: npt=0.000-",
                "RTP-Info: url=$url;seq=${s.seq};rtptime=$ts"
            ))
            s.play()
            reportClients()
            onLog("RTSP session ${s.id} playing (${if (s.tcp) "TCP" else "UDP"}) for ${socket.inetAddress.hostAddress}")
        }

        private fun transportHeader(s: Session): String {
            val ssrc = String.format(Locale.US, "%08X", s.ssrc)
            return if (s.tcp) {
                val (a, b) = s.transport.interleaved ?: (0 to 1)
                "Transport: RTP/AVP/TCP;unicast;interleaved=$a-$b;ssrc=$ssrc;mode=\"PLAY\""
            } else {
                val (a, b) = s.transport.clientPorts!!
                val sp = rtpSocket!!.localPort
                "Transport: RTP/AVP;unicast;client_port=$a-$b;server_port=$sp-${sp + 1};ssrc=$ssrc;mode=\"PLAY\""
            }
        }

        private fun sessionHeader(s: Session) = "Session: ${s.id};timeout=$SESSION_TIMEOUT_S"
    }

    private fun newSessionId(): String {
        while (true) {
            val id = String.format(Locale.US, "%08X", random.nextInt()).take(8)
            if (!sessions.containsKey(id)) return id
        }
    }

    private fun httpDate(): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date())
    }

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true; start() }

    companion object {
        const val SESSION_TIMEOUT_S = 60
        private const val QUEUE_CAPACITY = 90
        private const val SR_INTERVAL_MS = 2_000L
        private const val DESCRIBE_WAIT_MS = 6000L
        private const val DESCRIBE_GRACE_MS = 10_000L
    }
}

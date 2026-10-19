package com.github.digitallyrefined.androidipcamera.helpers

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Microphone capture feeding the RTSP audio track; started while any session plays audio. */
internal interface RtspAudioSource {
    fun start()
    fun stop()
}

/**
 * RTSP server (RFC 2326) publishing the camera at `rtsp://<ip>:<port>/live`: H.264 from the shared
 * hardware encoder and, optionally, AAC audio from the microphone.
 *
 * RTP goes over UDP or interleaved in the RTSP TCP connection, so VLC, ffmpeg, go2rtc/Frigate and
 * NVRs can all play it. Requests use HTTP Digest authentication (Basic is accepted when a client
 * sends it unprompted) against the same username/password as the HTTP server. A session belongs
 * to the TCP connection that created it and ends with it.
 *
 * Plain java.net, so it runs in JVM tests; everything Android-specific comes in through [Host].
 */
internal class RtspServer(
    private val host: Host,
    private val sendQueueCapacity: Int = 256
) {
    interface Host {
        fun port(): Int
        /** Read once per connection. */
        fun credentials(): RtspCredentials
        fun isStreamingEnabled(): Boolean
        /** Audio track to offer, or null when audio is off or the microphone permission is missing. */
        fun audioFormat(): AacFormat?
        fun createAudioSource(onAccessUnit: (ByteArray, Long) -> Unit): RtspAudioSource?
        /** A client needs video: the camera and H.264 encoder must run. */
        fun onClientConnected()
        fun onClientDisconnected()
        fun requestKeyFrame()
        fun log(message: String)
        /** Second clock camera timestamps may be on (elapsedRealtimeNanos on Android). */
        fun bootClockNanos(): Long? = null
    }

    companion object {
        const val DEFAULT_PORT = 8554
        const val PATH = "/live"
        const val PREF_ENABLED = "rtsp_enabled"
        const val PREF_PORT = "rtsp_port"
        const val PREF_AUDIO = "rtsp_audio"

        private const val SERVER_NAME = "Android IP Camera"
        private const val CNAME = "android-ip-camera"
        private const val PUBLIC_METHODS = "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
        private const val SESSION_TIMEOUT_S = 60
        private const val MAX_CONNECTIONS = 20
        // Same limits as the HTTP server's streaming clients.
        private const val MAX_SESSIONS = 3
        private const val MAX_AUTHENTICATED_SESSIONS = 10
        /** How long DESCRIBE waits for the encoder's SPS/PPS when the camera was idle. */
        private const val PARAMETER_SET_WAIT_MS = 8_000L
        private const val PARAMETER_SET_FRESH_NS = 3_000_000_000L
        private const val NONCE_TTL_MS = 5 * 60 * 1000L
        /** Disconnect a client whose socket write has not completed within this window. */
        private const val STALLED_WRITE_NS = 15_000_000_000L
        private const val SENDER_REPORT_INTERVAL_NS = 5_000_000_000L
        private const val OVERFLOW_LOG_INTERVAL_NS = 30_000_000_000L
        private const val NOT_WRITING = Long.MIN_VALUE
        // SECURITY: failed-login lockout, mirroring the HTTP server.
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val BLOCK_DURATION_MS = 15 * 60 * 1000L
        private const val RESET_WINDOW_MS = 10 * 60 * 1000L
        private const val BIND_MAX_RETRIES = 5
        private const val BIND_RETRY_DELAY_MS = 200L
        private val TRACK_ID = Regex("trackID=(\\d+)/?$")
    }

    private class FailedAttempt(var count: Int = 0, var lastAttempt: Long = 0L, var blockedUntil: Long = 0L)

    private class MediaFrame(val track: Int, val data: ByteArray, val isKey: Boolean, val clockNs: Long)

    private enum class Auth { GRANTED, CHALLENGED, REFUSED }

    private enum class Verdict { OK, STALE, WRONG }

    private val lock = Any()
    @Volatile private var running = false
    private var generation = 0
    private var serverSocket: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Connection>()
    private val failedAttempts = ConcurrentHashMap<String, FailedAttempt>()
    private val nonces = RtspNonces()
    private val random = SecureRandom()
    private val epochNs = System.nanoTime()
    private val videoClock = RtpMediaClock(bootClockNanos = host::bootClockNanos)
    private val audioClock = RtpMediaClock(bootClockNanos = host::bootClockNanos)
    @Volatile private var parameterSets: Pair<ByteArray, ByteArray>? = null
    @Volatile private var lastVideoFrameNs: Long? = null
    private val audioLock = Any()
    private var audioSource: RtspAudioSource? = null

    /** Starts listening (no-op if already running). Binding happens on the accept thread. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val gen = ++generation
            Thread({ acceptLoop(gen) }, "RtspAccept").apply { isDaemon = true; start() }
            Thread({ housekeepingLoop(gen) }, "RtspHousekeeping").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        val socket: ServerSocket?
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            socket = serverSocket
            serverSocket = null
        }
        try { socket?.close() } catch (_: IOException) {}
        disconnectAll()
    }

    /** Port actually bound, or null while not listening (tests bind port 0). */
    fun boundPort(): Int? = synchronized(lock) { serverSocket?.localPort }

    /** Drops every client, e.g. when streaming is switched off through /control/stop. */
    fun disconnectAll() {
        connections.toList().forEach { it.close() }
    }

    /** True while an RTSP client needs the H.264 encoder running. */
    fun hasVideoClients(): Boolean = connections.any { it.demandsVideo }

    /** Called from the encoder's output thread for every access unit (Annex-B, SPS/PPS on keyframes). */
    fun onVideoFrame(accessUnit: ByteArray, isKey: Boolean, ptsUs: Long) {
        if (!running) return
        lastVideoFrameNs = System.nanoTime()
        if (isKey) cacheParameterSets(accessUnit)
        val sessions = connections.mapNotNull { c -> c.session?.takeIf { it.playing && it.video != null } }
        if (sessions.isEmpty()) return
        val frame = MediaFrame(RtspSdp.VIDEO_TRACK, accessUnit, isKey, videoClock.toClockNs(ptsUs))
        sessions.forEach { it.offer(frame) }
    }

    private fun onAudioFrame(accessUnit: ByteArray, ptsUs: Long) {
        val sessions = connections.mapNotNull { c -> c.session?.takeIf { it.playing && it.audio != null } }
        if (sessions.isEmpty()) return
        val frame = MediaFrame(RtspSdp.AUDIO_TRACK, accessUnit, false, audioClock.toClockNs(ptsUs))
        sessions.forEach { it.offer(frame) }
    }

    private fun cacheParameterSets(accessUnit: ByteArray) {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (range in H264Bitstream.nalRanges(accessUnit)) {
            if (range.type == 7 && sps == null) sps = accessUnit.copyOfRange(range.start, range.end)
            if (range.type == 8 && pps == null) pps = accessUnit.copyOfRange(range.start, range.end)
        }
        if (sps != null && pps != null) parameterSets = sps to pps
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { running && gen == generation }

    private fun acceptLoop(gen: Int) {
        val port = host.port()
        val socket = bind(port, gen) ?: return
        synchronized(lock) {
            if (!running || gen != generation) {
                try { socket.close() } catch (_: IOException) {}
                return
            }
            serverSocket = socket
        }
        host.log("RTSP server started on port ${socket.localPort}")
        try {
            while (isCurrent(gen)) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed) break else continue
                }
                if (connections.size >= MAX_CONNECTIONS) {
                    try { client.close() } catch (_: IOException) {}
                    continue
                }
                val connection = try {
                    Connection(client)
                } catch (e: IOException) {
                    try { client.close() } catch (_: IOException) {}
                    continue
                }
                connections.add(connection)
                Thread({ connection.run() }, "RtspConnection").apply { isDaemon = true; start() }
            }
        } finally {
            try { socket.close() } catch (_: IOException) {}
            host.log("RTSP server stopped")
        }
    }

    private fun bind(port: Int, gen: Int): ServerSocket? {
        for (attempt in 0..BIND_MAX_RETRIES) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 50)
                return socket
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) {}
                if (attempt == BIND_MAX_RETRIES || !isCurrent(gen)) {
                    host.log("RTSP server could not listen on port $port: ${e.message}")
                    synchronized(lock) { if (gen == generation) running = false }
                    return null
                }
                try { Thread.sleep(BIND_RETRY_DELAY_MS shl attempt) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    private fun housekeepingLoop(gen: Int) {
        while (isCurrent(gen)) {
            try { Thread.sleep(2_000) } catch (_: InterruptedException) { return }
            val now = System.nanoTime()
            for (connection in connections) {
                val started = connection.writeStartedNs
                if (started != NOT_WRITING && now - started > STALLED_WRITE_NS) {
                    host.log("RTSP client ${connection.clientIp} disconnected because its connection stalled")
                    connection.close()
                }
            }
        }
    }

    /** Runs the shared microphone encoder exactly while some session is playing audio. */
    private fun updateAudioDemand() {
        synchronized(audioLock) {
            val wanted = running && connections.any { c -> c.session?.let { it.playing && it.audio != null } == true }
            val current = audioSource
            if (wanted && current == null) {
                audioSource = host.createAudioSource { data, ptsUs -> onAudioFrame(data, ptsUs) }?.also { it.start() }
            } else if (!wanted && current != null) {
                audioSource = null
                current.stop()
            }
        }
    }

    private fun isRateLimited(ip: String): Boolean {
        val attempt = failedAttempts[ip] ?: return false
        synchronized(attempt) {
            val now = System.currentTimeMillis()
            if (now < attempt.blockedUntil) return true
            if (now - attempt.lastAttempt > RESET_WINDOW_MS) attempt.count = 0
            return false
        }
    }

    private fun recordFailedAttempt(ip: String) {
        val attempt = failedAttempts.getOrPut(ip) { FailedAttempt() }
        synchronized(attempt) {
            val now = System.currentTimeMillis()
            if (now - attempt.lastAttempt > RESET_WINDOW_MS) attempt.count = 0
            if (attempt.count < Int.MAX_VALUE - 1) attempt.count++
            attempt.lastAttempt = now
            if (attempt.count >= MAX_FAILED_ATTEMPTS) {
                attempt.blockedUntil = now + BLOCK_DURATION_MS
                host.log("SECURITY: IP $ip blocked from RTSP for ${BLOCK_DURATION_MS / 60_000} minutes due to too many failed attempts")
            }
        }
    }

    private fun randomHex(bytes: Int): String = hex(ByteArray(bytes).also { random.nextBytes(it) })

    /** One RTSP control connection and the (at most one) session it owns. */
    private inner class Connection(private val socket: Socket) {
        val address: InetAddress = socket.inetAddress
        val clientIp: String = address.hostAddress ?: "unknown"
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val writeLock = Any()
        private val closed = AtomicBoolean(false)
        private var credentials: RtspCredentials? = null
        /** Nonce this connection authenticated with; stays valid for it beyond the nonce TTL. */
        private var authenticatedNonce: String? = null

        @Volatile var session: Session? = null
            private set
        /** Set from DESCRIBE/SETUP of video until the session ends: keeps the camera and encoder up. */
        @Volatile var demandsVideo = false
            private set
        @Volatile var writeStartedNs = NOT_WRITING
            private set

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val reader = RtspRequestReader(BufferedInputStream(socket.getInputStream()))
                while (!closed.get()) {
                    socket.soTimeout = readTimeoutMs()
                    val inbound = reader.read() ?: break
                    if (inbound is RtspInbound.Request && !handle(inbound.request)) break
                }
            } catch (e: RtspProtocolException) {
                try { respond(null, "400 Bad Request") } catch (_: IOException) {}
            } catch (e: SocketTimeoutException) {
                host.log("RTSP client $clientIp timed out")
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        /** RTP keeps a TCP-interleaved connection busy; idle UDP clients must send keepalives. */
        private fun readTimeoutMs(): Int {
            val current = session
            return when {
                current == null || !current.playing -> SESSION_TIMEOUT_S * 1000
                current.usesUdp -> SESSION_TIMEOUT_S * 2 * 1000
                else -> 0
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            connections.remove(this)
            session?.stop()
            session = null
            try { socket.close() } catch (_: IOException) {}
            releaseVideo()
            updateAudioDemand()
        }

        @Synchronized
        private fun claimVideo() {
            if (closed.get() || demandsVideo) return
            demandsVideo = true
            host.onClientConnected()
        }

        @Synchronized
        private fun releaseVideo() {
            if (!demandsVideo) return
            demandsVideo = false
            host.onClientDisconnected()
        }

        /** Handles one request; false closes the connection. */
        private fun handle(request: RtspRequest): Boolean {
            val cseq = request.cseq
            if (isRateLimited(clientIp)) {
                respond(cseq, "403 Forbidden")
                host.log("SECURITY: Refused RTSP request from blocked IP $clientIp")
                return false
            }
            if (request.method == "OPTIONS") {
                respond(cseq, "200 OK", listOf("Public" to PUBLIC_METHODS))
                return true
            }
            when (authorize(request)) {
                Auth.GRANTED -> {}
                Auth.CHALLENGED -> return true
                Auth.REFUSED -> return false
            }
            request.header("require")?.let {
                respond(cseq, "551 Option not supported", listOf("Unsupported" to it))
                return true
            }
            return when (request.method) {
                "DESCRIBE" -> describe(request)
                "SETUP" -> setup(request)
                "PLAY" -> play(request)
                "PAUSE" -> pause(request)
                "TEARDOWN" -> teardown(request)
                "GET_PARAMETER", "SET_PARAMETER" -> keepAlive(request)
                else -> {
                    respond(cseq, "405 Method Not Allowed", listOf("Allow" to PUBLIC_METHODS))
                    true
                }
            }
        }

        private fun authorize(request: RtspRequest): Auth {
            val cseq = request.cseq
            val creds = credentials ?: host.credentials().also { credentials = it }
            val required = when (creds) {
                RtspCredentials.Disabled -> return Auth.GRANTED
                RtspCredentials.NotConfigured -> {
                    recordFailedAttempt(clientIp)
                    respond(cseq, "403 Forbidden")
                    host.log("SECURITY: RTSP connection rejected - authentication credentials not configured")
                    return Auth.REFUSED
                }
                is RtspCredentials.Required -> creds
            }
            var stale = false
            val header = request.header("authorization")
            if (header != null) {
                when (verify(header, request.method, required)) {
                    Verdict.OK -> return Auth.GRANTED
                    Verdict.STALE -> stale = true
                    Verdict.WRONG -> {
                        recordFailedAttempt(clientIp)
                        host.log("SECURITY: Failed RTSP authentication attempt from $clientIp")
                        if (isRateLimited(clientIp)) {
                            respond(cseq, "403 Forbidden")
                            return Auth.REFUSED
                        }
                    }
                }
            }
            // Only Digest is offered: RTSP has no TLS, and Basic would send the password in clear.
            val challenge = "Digest realm=\"${RtspDigestAuth.REALM}\", nonce=\"${nonces.issue()}\"" +
                if (stale) ", stale=TRUE" else ""
            respond(cseq, "401 Unauthorized", listOf("WWW-Authenticate" to challenge))
            return Auth.CHALLENGED
        }

        private fun verify(header: String, method: String, creds: RtspCredentials.Required): Verdict {
            val scheme = header.trim().substringBefore(' ')
            if (scheme.equals("Basic", ignoreCase = true)) {
                val decoded = RtspBase64.decode(header.trim().substringAfter(' ').trim()) ?: return Verdict.WRONG
                val expected = "${creds.username}:${creds.password}".toByteArray(StandardCharsets.UTF_8)
                return if (MessageDigest.isEqual(decoded, expected)) Verdict.OK else Verdict.WRONG
            }
            if (!scheme.equals("Digest", ignoreCase = true)) return Verdict.WRONG
            val params = RtspDigestAuth.parseParams(header)
            val nonce = params["nonce"] ?: return Verdict.WRONG
            val uri = params["uri"] ?: return Verdict.WRONG
            val response = params["response"] ?: return Verdict.WRONG
            if (params["algorithm"]?.equals("MD5", ignoreCase = true) == false) return Verdict.WRONG
            if (params["username"] != creds.username || params["realm"] != RtspDigestAuth.REALM) return Verdict.WRONG
            val age = nonces.ageMs(nonce) ?: return Verdict.WRONG
            val expected = RtspDigestAuth.response(
                creds.username, creds.password, RtspDigestAuth.REALM, method, uri, nonce,
                params["qop"], params["nc"], params["cnonce"]
            )
            if (!MessageDigest.isEqual(expected.toByteArray(), response.lowercase(Locale.US).toByteArray())) {
                return Verdict.WRONG
            }
            if (age > NONCE_TTL_MS && nonce != authenticatedNonce) return Verdict.STALE
            authenticatedNonce = nonce
            return Verdict.OK
        }

        private fun describe(request: RtspRequest): Boolean {
            val cseq = request.cseq
            if (!host.isStreamingEnabled()) return respondStreamingDisabled(cseq)
            claimVideo()
            val params = awaitParameterSets()
            val origin = socket.localAddress.hostAddress ?: "0.0.0.0"
            val sdp = RtspSdp.build(origin, random.nextLong() and Long.MAX_VALUE, params?.first, params?.second, host.audioFormat())
            val base = if (request.uri.endsWith("/")) request.uri else "${request.uri}/"
            respond(
                cseq, "200 OK",
                listOf("Content-Base" to base, "Content-Type" to "application/sdp"),
                sdp.toByteArray(StandardCharsets.UTF_8)
            )
            return true
        }

        /** SPS/PPS of the running encoder, waiting for the camera to start if it was idle. */
        private fun awaitParameterSets(): Pair<ByteArray, ByteArray>? {
            val deadline = System.nanoTime() + PARAMETER_SET_WAIT_MS * 1_000_000
            while (!closed.get()) {
                val current = parameterSets
                val last = lastVideoFrameNs
                if (current != null && last != null && System.nanoTime() - last < PARAMETER_SET_FRESH_NS) return current
                if (System.nanoTime() >= deadline) {
                    // Players fall back to the SPS/PPS repeated in front of every keyframe.
                    host.log("RTSP: no live H.264 parameter sets within ${PARAMETER_SET_WAIT_MS}ms")
                    return current
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return current }
            }
            return null
        }

        private fun setup(request: RtspRequest): Boolean {
            val cseq = request.cseq
            if (!host.isStreamingEnabled()) return respondStreamingDisabled(cseq)
            val trackIndex = TRACK_ID.find(request.uri)?.groupValues?.get(1)?.toIntOrNull() ?: RtspSdp.VIDEO_TRACK
            val audioFormat = if (trackIndex == RtspSdp.AUDIO_TRACK) host.audioFormat() else null
            if ((trackIndex != RtspSdp.VIDEO_TRACK && trackIndex != RtspSdp.AUDIO_TRACK) ||
                (trackIndex == RtspSdp.AUDIO_TRACK && audioFormat == null)
            ) {
                respond(cseq, "404 Not Found")
                return true
            }
            val transport = request.header("transport")?.let { RtspTransport.parse(it) }
            if (transport == null) {
                respond(cseq, "461 Unsupported Transport")
                return true
            }
            val requestedId = request.sessionId
            var current = session
            if (requestedId != null && requestedId != current?.id) {
                respond(cseq, "454 Session Not Found")
                return true
            }
            if (current == null) {
                val limit = if (credentials == RtspCredentials.Disabled) MAX_SESSIONS else MAX_AUTHENTICATED_SESSIONS
                if (connections.count { it.session != null } >= limit) {
                    respond(cseq, "503 Service Unavailable")
                    host.log("RTSP session limit reached, refusing $clientIp")
                    return true
                }
                current = Session(randomHex(8).uppercase(Locale.US), this)
                session = current
            }
            if (current.playing) {
                respond(cseq, "455 Method Not Valid in This State")
                return true
            }
            val track = if (trackIndex == RtspSdp.VIDEO_TRACK) {
                current.setupTrack(trackIndex, transport, request.uri, RtspSdp.VIDEO_PAYLOAD_TYPE, RtspSdp.VIDEO_CLOCK_RATE)
            } else {
                current.setupTrack(trackIndex, transport, request.uri, RtspSdp.AUDIO_PAYLOAD_TYPE, audioFormat!!.sampleRate)
            }
            if (track == null) {
                // No UDP port pair could be bound; clients fall back to TCP on 461.
                respond(cseq, "461 Unsupported Transport")
                return true
            }
            if (trackIndex == RtspSdp.VIDEO_TRACK) claimVideo()
            respond(cseq, "200 OK", listOf("Transport" to track.transportReply(), "Session" to "${current.id};timeout=$SESSION_TIMEOUT_S"))
            return true
        }

        private fun play(request: RtspRequest): Boolean {
            val cseq = request.cseq
            val current = sessionFor(request) ?: return true
            if (!host.isStreamingEnabled()) return respondStreamingDisabled(cseq)
            if (!current.hasTracks) {
                respond(cseq, "455 Method Not Valid in This State")
                return true
            }
            respond(cseq, "200 OK", listOf("Session" to current.id, "Range" to "npt=0.000-", "RTP-Info" to current.rtpInfo()))
            // Only after the response: RTP must not reach a TCP client ahead of the PLAY reply.
            current.startPlaying()
            if (current.video != null) host.requestKeyFrame() else releaseVideo()
            updateAudioDemand()
            host.log("RTSP client $clientIp playing (${current.summary()})")
            return true
        }

        private fun pause(request: RtspRequest): Boolean {
            val current = sessionFor(request) ?: return true
            current.pause()
            respond(request.cseq, "200 OK", listOf("Session" to current.id))
            updateAudioDemand()
            return true
        }

        private fun teardown(request: RtspRequest): Boolean {
            val current = sessionFor(request) ?: return true
            respond(request.cseq, "200 OK", listOf("Session" to current.id))
            current.stop()
            session = null
            releaseVideo()
            updateAudioDemand()
            return true
        }

        private fun keepAlive(request: RtspRequest): Boolean {
            val requestedId = request.sessionId
            if (requestedId != null) {
                val current = sessionFor(request) ?: return true
                respond(request.cseq, "200 OK", listOf("Session" to current.id))
            } else {
                respond(request.cseq, "200 OK")
            }
            return true
        }

        /** This connection's session if the request names it; otherwise answers 454 and returns null. */
        private fun sessionFor(request: RtspRequest): Session? {
            val current = session
            if (current == null || request.sessionId != current.id) {
                respond(request.cseq, "454 Session Not Found")
                return null
            }
            return current
        }

        private fun respondStreamingDisabled(cseq: String?): Boolean {
            respond(cseq, "503 Service Unavailable")
            return true
        }

        private fun respond(
            cseq: String?,
            status: String,
            headers: List<Pair<String, String>> = emptyList(),
            body: ByteArray? = null
        ) {
            val text = StringBuilder("RTSP/1.0 ").append(status).append("\r\n")
            if (cseq != null) text.append("CSeq: ").append(cseq).append("\r\n")
            text.append("Server: ").append(SERVER_NAME).append("\r\n")
            for ((name, value) in headers) text.append(name).append(": ").append(value).append("\r\n")
            if (body != null) text.append("Content-Length: ").append(body.size).append("\r\n")
            text.append("\r\n")
            val head = text.toString().toByteArray(StandardCharsets.ISO_8859_1)
            write {
                output.write(head)
                if (body != null) output.write(body)
            }
        }

        /** RTP/RTCP packets framed for the TCP connection (RFC 2326 §10.12). */
        fun writeInterleaved(channel: Int, packets: List<ByteArray>) {
            write {
                for (packet in packets) {
                    output.write('$'.code)
                    output.write(channel)
                    output.write(packet.size shr 8)
                    output.write(packet.size and 0xff)
                    output.write(packet)
                }
            }
        }

        private inline fun write(block: () -> Unit) {
            synchronized(writeLock) {
                writeStartedNs = System.nanoTime()
                try {
                    block()
                    output.flush()
                } finally {
                    writeStartedNs = NOT_WRITING
                }
            }
        }
    }

    private class UdpTarget(
        val rtpSocket: DatagramSocket,
        val rtcpSocket: DatagramSocket,
        val address: InetAddress,
        val clientRtpPort: Int,
        val clientRtcpPort: Int
    ) {
        fun close() {
            rtpSocket.close()
            rtcpSocket.close()
        }
    }

    private class Track(
        val controlUrl: String,
        val rtp: RtpStream,
        /** TCP: interleaved RTP/RTCP channels. */
        val channels: Pair<Int, Int>?,
        /** UDP: sockets and client ports. */
        val udp: UdpTarget?
    ) {
        var lastReportNs: Long? = null

        fun transportReply(): String {
            val ssrc = String.format(Locale.US, "%08X", rtp.ssrc)
            return if (udp != null) {
                "RTP/AVP;unicast;client_port=${udp.clientRtpPort}-${udp.clientRtcpPort};" +
                    "server_port=${udp.rtpSocket.localPort}-${udp.rtcpSocket.localPort};ssrc=$ssrc;mode=play"
            } else {
                "RTP/AVP/TCP;unicast;interleaved=${channels!!.first}-${channels.second};ssrc=$ssrc;mode=play"
            }
        }

        fun close() {
            udp?.close()
        }
    }

    /** One client session: its tracks, a bounded send queue and the thread draining it. */
    private inner class Session(val id: String, private val connection: Connection) {
        @Volatile var video: Track? = null
            private set
        @Volatile var audio: Track? = null
            private set
        @Volatile var playing = false
            private set
        @Volatile private var stopped = false
        @Volatile private var videoWaitingKey = true
        private val queue = ArrayBlockingQueue<MediaFrame>(sendQueueCapacity)
        private var sender: Thread? = null
        @Volatile private var droppedBacklogs = 0
        @Volatile private var lastOverflowLogNs: Long? = null

        val hasTracks: Boolean get() = video != null || audio != null
        val usesUdp: Boolean get() = video?.udp != null || audio?.udp != null

        fun setupTrack(index: Int, transport: RtspTransport, controlUrl: String, payloadType: Int, clockRate: Int): Track? {
            val rtp = RtpStream(payloadType, clockRate, random.nextInt(), random.nextInt(0x10000), random.nextInt())
            val track = if (transport.tcp) {
                Track(controlUrl, rtp, transport.interleaved ?: (index * 2 to index * 2 + 1), null)
            } else {
                val ports = transport.clientPorts ?: return null
                val sockets = bindUdpPair() ?: return null
                Track(controlUrl, rtp, null, UdpTarget(sockets.first, sockets.second, connection.address, ports.first, ports.second))
            }
            val previous = if (index == RtspSdp.VIDEO_TRACK) video else audio
            if (index == RtspSdp.VIDEO_TRACK) video = track else audio = track
            previous?.close()
            return track
        }

        /** RTP and RTCP sockets on consecutive ports, RTP even (RFC 3550 §11). */
        private fun bindUdpPair(): Pair<DatagramSocket, DatagramSocket>? {
            repeat(20) {
                val rtpSocket = try { DatagramSocket(0) } catch (_: SocketException) { return null }
                val port = rtpSocket.localPort
                if (port % 2 != 0 || port >= 65535) {
                    rtpSocket.close()
                    return@repeat
                }
                try {
                    return rtpSocket to DatagramSocket(port + 1)
                } catch (_: SocketException) {
                    rtpSocket.close()
                }
            }
            host.log("RTSP: could not bind a UDP port pair")
            return null
        }

        fun rtpInfo(): String = listOfNotNull(video, audio).joinToString(",") { "url=${it.controlUrl};seq=${it.rtp.sequence}" }

        fun summary(): String = listOfNotNull(
            video?.let { "video over ${if (it.udp != null) "UDP" else "TCP"}" },
            audio?.let { "audio over ${if (it.udp != null) "UDP" else "TCP"}" }
        ).joinToString(", ")

        fun startPlaying() {
            queue.clear()
            videoWaitingKey = true
            playing = true
            if (sender == null) {
                sender = Thread({ sendLoop() }, "RtspSender").apply { isDaemon = true; start() }
            }
        }

        fun pause() {
            playing = false
            queue.clear()
        }

        fun stop() {
            stopped = true
            playing = false
            sender?.interrupt()
            queue.clear()
            video?.close()
            audio?.close()
        }

        fun offer(frame: MediaFrame) {
            if (!playing || queue.offer(frame)) return
            // The client is not keeping up. Drop the backlog and resume at the next keyframe rather
            // than send it a stream it can no longer decode.
            queue.clear()
            videoWaitingKey = true
            if (frame.isKey) queue.offer(frame)
            droppedBacklogs++
            val now = System.nanoTime()
            val lastLog = lastOverflowLogNs
            if (lastLog == null || now - lastLog > OVERFLOW_LOG_INTERVAL_NS) {
                lastOverflowLogNs = now
                host.log("RTSP client ${connection.clientIp} is too slow, dropped frames ($droppedBacklogs time(s))")
            }
        }

        private fun sendLoop() {
            try {
                while (!stopped) {
                    val frame = queue.poll(500, TimeUnit.MILLISECONDS)
                    if (stopped) break
                    if (frame != null && playing) send(frame)
                    if (playing) sendReports()
                }
            } catch (_: InterruptedException) {
            } catch (e: IOException) {
                if (!stopped) {
                    host.log("RTSP client ${connection.clientIp} disconnected: ${e.message}")
                    connection.close()
                }
            }
        }

        private fun send(frame: MediaFrame) {
            val track = (if (frame.track == RtspSdp.VIDEO_TRACK) video else audio) ?: return
            val payloads = if (frame.track == RtspSdp.VIDEO_TRACK) {
                if (videoWaitingKey) {
                    if (!frame.isKey) return
                    videoWaitingKey = false
                }
                H264RtpPacketizer.payloads(frame.data)
            } else {
                AacRtpPacketizer.payloads(frame.data)
            }
            if (payloads.isEmpty()) return
            val timestamp = track.rtp.timestampAt(frame.clockNs, epochNs)
            val packets = payloads.mapIndexed { i, payload -> track.rtp.packet(payload, i == payloads.lastIndex, timestamp) }
            transmit(track, packets, rtcp = false)
        }

        /** RTCP sender reports every few seconds per track, so players can sync audio and video. */
        private fun sendReports() {
            for (track in listOfNotNull(video, audio)) {
                if (track.rtp.packetCount == 0L) continue
                val now = System.nanoTime()
                val last = track.lastReportNs
                if (last != null && now - last < SENDER_REPORT_INTERVAL_NS) continue
                track.lastReportNs = now
                val report = RtcpPackets.senderReport(
                    track.rtp.ssrc,
                    System.currentTimeMillis(),
                    track.rtp.timestampAt(now, epochNs),
                    track.rtp.packetCount,
                    track.rtp.octetCount,
                    CNAME
                )
                transmit(track, listOf(report), rtcp = true)
            }
        }

        private fun transmit(track: Track, packets: List<ByteArray>, rtcp: Boolean) {
            val udp = track.udp
            if (udp != null) {
                val socket = if (rtcp) udp.rtcpSocket else udp.rtpSocket
                val port = if (rtcp) udp.clientRtcpPort else udp.clientRtpPort
                for (packet in packets) socket.send(DatagramPacket(packet, packet.size, udp.address, port))
            } else {
                val channels = track.channels ?: return
                connection.writeInterleaved(if (rtcp) channels.second else channels.first, packets)
            }
        }
    }
}

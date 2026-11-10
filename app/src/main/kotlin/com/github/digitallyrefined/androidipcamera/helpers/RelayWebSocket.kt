package com.github.digitallyrefined.androidipcamera.helpers

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The relay no longer recognises this camera's token (for example after removal). */
class RelayUnauthorizedException(message: String) : IOException(message)

/**
 * Minimal RFC 6455 client used for the relay connection. Keeping this small
 * avoids adding OkHttp just for one long-lived outbound socket. It supports
 * TLS, masking, fragmentation, ping/pong and text/binary messages.
 */
class RelayWebSocket(
    private val url: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(data: ByteArray)
        fun onPong()
        fun onClosed(error: Throwable?)
    }

    companion object {
        private const val TAG = "RelayWebSocket"
        private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var output: OutputStream? = null
    @Volatile
    private var closed = false
    private val closeNotified = AtomicBoolean(false)
    private var input: BufferedInputStream? = null

    fun connect() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: throw IOException("Relay URL has no host")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = 0

        val activeSocket = if (secure) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            if (!isIpLiteral(host)) {
                runCatching { parameters.serverNames = listOf(SNIHostName(host)) }
            }
            ssl.sslParameters = parameters
            ssl.startHandshake()
            ssl
        } else {
            raw
        }
        socket = activeSocket

        val key = Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val buffered = BufferedInputStream(activeSocket.getInputStream(), 32 * 1024)
        input = buffered
        val out = activeSocket.getOutputStream()
        output = out

        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host)
            if ((secure && port != 443) || (!secure && port != 80)) append(':').append(port)
            append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: Android-IP-Camera\r\n")
            for ((name, value) in extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        out.write(request.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val statusLine = readHttpLine(buffered)
        if (!statusLine.contains(" 101 ")) {
            if (statusLine.contains(" 401 ")) {
                throw RelayUnauthorizedException("Relay rejected this camera's token")
            }
            throw IOException("Relay rejected WebSocket upgrade: $statusLine")
        }
        var accept: String? = null
        while (true) {
            val line = readHttpLine(buffered)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.equals("Sec-WebSocket-Accept", ignoreCase = true)) accept = value
        }
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
        if (accept != expected) {
            throw IOException("Relay returned an invalid WebSocket accept header")
        }

        listener.onOpen()
    }

    fun readLoop() {
        var error: Throwable? = null
        try {
            while (!closed) {
                readFrame()
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            closed = true
            runCatching { socket?.close() }
            socket = null
            notifyClosed(error)
        }
    }

    fun sendText(text: String) {
        sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    fun sendBinary(data: ByteArray) {
        sendFrame(0x2, data)
    }

    fun ping() {
        sendFrame(0x9, ByteArray(0))
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { sendFrame(0x8, byteArrayOf(0x03, 0xE8.toByte())) }
        runCatching { socket?.close() }
        socket = null
    }

    private fun notifyClosed(error: Throwable?) {
        if (closeNotified.compareAndSet(false, true)) {
            listener.onClosed(error)
        }
    }

    private fun readFrame() {
        val input = input ?: throw IOException("WebSocket is not connected")
        val b0 = readByte(input)
        val b1 = readByte(input)
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7f).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IOException("WebSocket frame is too large: $length")
        }
        val mask = if (masked) readFully(input, 4) else null
        val payload = readFully(input, length.toInt())
        if (mask != null) {
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }

        when (opcode) {
            0x0 -> {
                // Continuation frame. Fragment state is handled below.
            }
            0x1, 0x2 -> {
                if (!fin) {
                    fragmentOpcode = opcode
                    fragmentBuffer = payload
                    return
                }
                dispatch(opcode, payload)
                return
            }
            0x8 -> {
                closed = true
                runCatching { sendFrame(0x8, byteArrayOf(0x03, 0xE8.toByte())) }
                return
            }
            0x9 -> {
                sendFrame(0xA, payload)
                return
            }
            0xA -> {
                listener.onPong()
                return
            }
            else -> return
        }

        if (fragmentOpcode == 0) {
            // Continuation without a start frame: ignore it.
            return
        }
        val combined = ByteArray(fragmentBuffer.size + payload.size)
        fragmentBuffer.copyInto(combined)
        payload.copyInto(combined, fragmentBuffer.size)
        fragmentBuffer = combined
        if (fin) {
            val completedOpcode = fragmentOpcode
            val completed = fragmentBuffer
            fragmentOpcode = 0
            fragmentBuffer = ByteArray(0)
            dispatch(completedOpcode, completed)
        }
    }

    private var fragmentOpcode = 0
    private var fragmentBuffer = ByteArray(0)

    private fun dispatch(opcode: Int, payload: ByteArray) {
        when (opcode) {
            0x1 -> listener.onText(payload.toString(Charsets.UTF_8))
            0x2 -> listener.onBinary(payload)
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: throw IOException("WebSocket is not connected")
        val header = ByteArray(14)
        var size = 2
        header[0] = (0x80 or opcode).toByte()
        when {
            payload.size < 126 -> header[1] = (0x80 or payload.size).toByte()
            payload.size <= 0xffff -> {
                header[1] = (0x80 or 126).toByte()
                header[2] = ((payload.size shr 8) and 0xff).toByte()
                header[3] = (payload.size and 0xff).toByte()
                size = 4
            }
            else -> {
                header[1] = (0x80 or 127).toByte()
                var length = payload.size.toLong()
                for (index in 9 downTo 2) {
                    header[index] = (length and 0xff).toByte()
                    length = length shr 8
                }
                size = 10
            }
        }
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        mask.copyInto(header, size)
        size += 4
        val maskedPayload = ByteArray(payload.size)
        for (index in payload.indices) {
            maskedPayload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        synchronized(this) {
            out.write(header, 0, size)
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun readHttpLine(input: InputStream): String {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) throw EOFException("Relay closed during WebSocket handshake")
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                builder.append(next.toChar())
                if (builder.length > 8192) throw IOException("WebSocket handshake line is too long")
            }
        }
        return builder.toString()
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value == -1) throw EOFException("Relay closed the WebSocket")
        return value
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) throw EOFException("Relay closed the WebSocket")
            offset += read
        }
        return data
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}

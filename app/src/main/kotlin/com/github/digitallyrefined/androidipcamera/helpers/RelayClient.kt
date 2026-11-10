package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.min

/**
 * Maintains the camera's outbound relay connection. The relay can only ask
 * this device for work over that connection, so no inbound port forwarding,
 * SSH tunnel, or third-party tunnel provider is needed.
 *
 * Browser HTTP requests are replayed against the camera's own loopback server
 * with the viewer's Basic credentials forwarded, so the camera enforces the
 * same authentication as a direct connection. H.264 access units are sent as
 * binary WebSocket messages and packetized on the relay for WebRTC. The relay
 * must also prove the viewer's credentials before the camera starts media.
 */
class RelayClient(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
    private val onStatusChanged: (connected: Boolean, status: String) -> Unit = { _, _ -> },
    private val onMediaActiveChanged: (Boolean) -> Unit = {},
    private val onKeyFrameRequest: () -> Unit = {},
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val VIDEO_QUEUE_CAPACITY = 12
        private const val PING_INTERVAL_MS = 30_000L
        private const val PONG_TIMEOUT_MS = 95_000L
        private const val PREF_CONNECTED = "relay_connected"
        private const val PREF_STATUS = "relay_status"
        private const val PREF_LAST_ERROR = "relay_last_error"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoQueue = ArrayBlockingQueue<ByteArray>(VIDEO_QUEUE_CAPACITY)
    private val pendingSockets = ConcurrentHashMap<String, Socket>()
    private val lastPong = AtomicLong(0)

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    @Volatile
    private var activeMedia = false

    @Volatile
    private var relaySocket: RelayWebSocket? = null

    private var reconnectJob: Job? = null
    private var videoWriter: Thread? = null

    val isConnected: Boolean get() = connected
    val isMediaActive: Boolean get() = activeMedia

    @Synchronized
    fun start() {
        if (running) return
        if (RelayAssociation.load(context) == null) {
            publishStatus(false, "Not associated")
            return
        }
        running = true
        startVideoWriter()
        reconnectJob = scope.launch { reconnectLoop() }
    }

    @Synchronized
    fun stop() {
        if (!running) {
            publishStatus(false, "Disconnected")
            return
        }
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        relaySocket?.close()
        relaySocket = null
        videoWriter?.interrupt()
        videoWriter = null
        videoQueue.clear()
        closePendingSockets()
        setMediaActive(false)
        connected = false
        publishStatus(false, "Disconnected")
    }

    /** Forget the association and stop reconnecting. */
    @Synchronized
    fun disconnect() {
        RelayAssociation.clear(context)
        stop()
        publishStatus(false, "Not associated")
    }

    /** Called after the app has stored fresh association credentials. */
    @Synchronized
    fun restartAfterAssociation() {
        // Full stop closes the old socket and stops the video writer, so the
        // new association cannot end up with two writer threads.
        stop()
        start()
    }

    fun sendVideo(data: ByteArray, isKey: Boolean, ptsUs: Long) {
        if (!running || !connected || !activeMedia || data.isEmpty()) return
        val frame = ByteArray(10 + data.size)
        frame[0] = 1
        frame[1] = if (isKey) 1 else 0
        java.nio.ByteBuffer.wrap(frame).putLong(2, ptsUs)
        data.copyInto(frame, 10)
        if (!videoQueue.offer(frame)) {
            // Drop rather than stall MediaCodec. A later keyframe resynchronizes viewers.
            Log.w(TAG, "Relay video queue full; dropping access unit")
        }
    }

    fun requestKeyFrame() {
        onKeyFrameRequest()
    }

    private suspend fun reconnectLoop() {
        var attempt = 0
        while (running) {
            val credentials = RelayAssociation.load(context)
            if (credentials == null) {
                publishStatus(false, "Not associated")
                break
            }

            val socket = RelayWebSocket(
                url = websocketUrl(credentials),
                extraHeaders = mapOf("Authorization" to "Bearer ${credentials.token}"),
                listener = object : RelayWebSocket.Listener {
                    override fun onOpen() {
                        lastPong.set(System.currentTimeMillis())
                        publishStatus(true, "Connected")
                    }

                    override fun onText(text: String) {
                        handleRelayMessage(text)
                    }

                    override fun onBinary(data: ByteArray) {
                        // The camera only receives JSON signalling from the relay.
                    }

                    override fun onPong() {
                        lastPong.set(System.currentTimeMillis())
                    }

                    override fun onClosed(error: Throwable?) {
                        if (running && error != null) {
                            Log.w(TAG, "Relay WebSocket closed: ${error.message}")
                        }
                    }
                },
            )
            relaySocket = socket

            try {
                socket.connect()
                connected = true
                attempt = 0
                publishStatus(true, "Connected")
                val pingJob = scope.launch { keepAlive(socket) }
                socket.readLoop()
                pingJob.cancel()
            } catch (error: RelayUnauthorizedException) {
                // The relay no longer recognises this camera (removed, not
                // registered, or the token was rejected). Retrying would never
                // succeed, so forget the association and let the user pair again.
                Log.w(TAG, "Relay rejected this camera: ${error.message}")
                RelayAssociation.clear(context)
                val detail = error.message?.takeIf { it.isNotBlank() }
                    ?: "Relay rejected this camera"
                publishStatus(false, "$detail. Pair it again from the relay page.")
                running = false
                videoWriter?.interrupt()
                videoWriter = null
                videoQueue.clear()
            } catch (error: Throwable) {
                Log.w(TAG, "Relay connection failed: ${error.message}")
                publishStatus(false, error.message ?: "Connection failed")
            } finally {
                connected = false
                relaySocket = null
                setMediaActive(false)
                closePendingSockets()
            }

            if (!running) break
            val backoffMs = min(60_000L, 1_000L shl min(attempt, 6)) + SecureRandom().nextInt(500)
            attempt++
            publishStatus(false, "Reconnecting in ${backoffMs / 1000}s")
            delay(backoffMs)
        }
    }

    private suspend fun keepAlive(socket: RelayWebSocket) {
        while (scope.isActive && running && connected && relaySocket === socket) {
            delay(PING_INTERVAL_MS)
            if (!running || relaySocket !== socket) break
            if (System.currentTimeMillis() - lastPong.get() > PONG_TIMEOUT_MS) {
                onLog("Relay keepalive timed out; reconnecting")
                socket.close()
                break
            }
            try {
                socket.ping()
            } catch (error: Exception) {
                socket.close()
                break
            }
        }
    }

    private fun handleRelayMessage(text: String) {
        val message = try {
            JSONObject(text)
        } catch (error: Exception) {
            return
        }
        when (message.optString("type")) {
            "http-request" -> {
                val id = message.optString("id")
                if (id.isBlank()) return
                val method = message.optString("method", "GET").uppercase()
                val path = message.optString("path", "/")
                val headers = message.optJSONObject("headers")
                val body = message.optString("body").takeIf { it.isNotBlank() }
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
                scope.launch { proxyHttpRequest(id, method, path, headers, body) }
            }
            "http-cancel" -> {
                val id = message.optString("id")
                if (id.isBlank()) return
                pendingSockets.remove(id)?.let { runCatching { it.close() } }
            }
            "auth-check" -> {
                val id = message.optString("id")
                if (id.isBlank()) return
                val result = evaluateRelayAuth(
                    message.optString("username", ""),
                    message.optString("password", ""),
                )
                sendJson(
                    JSONObject()
                        .put("type", "auth-result")
                        .put("id", id)
                        .put("ok", result.ok)
                        .put("status", result.status)
                        .put("reason", result.reason)
                        .put("error", result.error),
                )
            }
            "media-start" -> {
                // The relay must prove the viewer's credentials before it can start
                // media. This is enforced here, not only on the relay, so a relay
                // that skips its own check still cannot obtain a stream.
                val result = evaluateRelayAuth(
                    message.optString("username", ""),
                    message.optString("password", ""),
                )
                if (result.ok) {
                    videoQueue.clear()
                    setMediaActive(true)
                } else {
                    // A rejected start must also stop any stream that was
                    // already running, so a failed re-authorization cannot
                    // leave the camera sending video.
                    setMediaActive(false)
                    sendJson(
                        JSONObject()
                            .put("type", "media-denied")
                            .put("status", result.status)
                            .put("reason", result.reason)
                            .put("error", result.error),
                    )
                }
            }
            "media-stop" -> setMediaActive(false)
            "keyframe" -> onKeyFrameRequest()
        }
    }

    private fun evaluateRelayAuth(username: String, password: String): CameraAuth.Result =
        try {
            CameraAuth.verify(context, username, password)
        } catch (error: Exception) {
            Log.e(TAG, "Could not evaluate relay authentication", error)
            CameraAuth.Result(
                ok = false,
                status = 403,
                reason = "auth-error",
                error = "The camera could not read its authentication settings.",
            )
        }

    private fun setMediaActive(active: Boolean) {
        if (activeMedia == active) return
        activeMedia = active
        if (active) {
            videoQueue.clear()
            onKeyFrameRequest()
        }
        onMediaActiveChanged(active)
    }

    private fun websocketUrl(credentials: RelayAssociation.Credentials): String {
        val base = when {
            credentials.serverUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + credentials.serverUrl.substring(8)
            credentials.serverUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + credentials.serverUrl.substring(7)
            else -> "wss://${credentials.serverUrl}"
        }.trimEnd('/')
        val id = URLEncoder.encode(credentials.deviceId, Charsets.UTF_8.name())
        return "$base/ws/device?deviceId=$id"
    }

    private fun proxyHttpRequest(
        id: String,
        method: String,
        path: String,
        forwardedHeaders: JSONObject?,
        body: ByteArray?,
    ) {
        var socket: Socket? = null
        try {
            if (path.any { it == '\r' || it == '\n' }) throw IOException("Invalid request path")
            if (!path.startsWith("/")) throw IOException("Invalid request path")
            if (method !in setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")) {
                throw IOException("Unsupported method")
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val port = prefs.getString("server_port", "4444")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4444
            val tls = prefs.getBoolean("tls_enabled", true)
            socket = openLoopbackSocket(port, tls)
            pendingSockets[id] = socket

            val request = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(port).append("\r\n")
                append("Connection: close\r\n")
                append("Accept-Encoding: identity\r\n")
                if (forwardedHeaders != null) {
                    val names = forwardedHeaders.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        val lower = name.lowercase()
                        if (lower in setOf("accept", "range", "content-type", "if-none-match", "if-modified-since", "authorization")) {
                            append(name).append(": ").append(forwardedHeaders.optString(name)).append("\r\n")
                        }
                    }
                }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                if (body != null) write(body)
                flush()
            }

            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val statusLine = readHttpLine(input) ?: throw IOException("Camera closed the request")
            val statusParts = statusLine.split(' ', limit = 3)
            val status = statusParts.getOrNull(1)?.toIntOrNull() ?: 502
            val responseHeaders = linkedMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                responseHeaders[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }

            sendJson(
                JSONObject()
                    .put("type", "http-response")
                    .put("id", id)
                    .put("status", status)
                    .put("headers", JSONObject(responseHeaders as Map<*, *>)),
            )

            if (method != "HEAD") {
                val transferEncoding = responseHeaders["transfer-encoding"].orEmpty()
                val contentLength = responseHeaders["content-length"]?.toLongOrNull()
                when {
                    transferEncoding.contains("chunked", ignoreCase = true) -> streamChunked(id, input, socket)
                    contentLength != null -> streamFixed(id, input, contentLength)
                    else -> streamUntilEof(id, input)
                }
            }

            sendJson(JSONObject().put("type", "http-end").put("id", id))
        } catch (error: Throwable) {
            sendJson(
                JSONObject()
                    .put("type", "http-error")
                    .put("id", id)
                    .put("message", error.message ?: "Camera request failed"),
            )
        } finally {
            socket?.let {
                pendingSockets.remove(id, it)
                runCatching { it.close() }
            }
        }
    }

    private fun streamChunked(id: String, input: InputStream, socket: Socket) {
        val buffer = ByteArray(16 * 1024)
        while (running && !socket.isClosed) {
            val sizeLine = readHttpLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Consume optional trailers.
                while (true) {
                    val trailer = readHttpLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = size
            while (remaining > 0 && running) {
                val read = input.read(buffer, 0, min(buffer.size, remaining))
                if (read == -1) return
                if (read > 0) {
                    remaining -= read
                    sendChunk(id, buffer, read)
                }
            }
            readHttpLine(input) // trailing CRLF
        }
    }

    private fun streamFixed(id: String, input: InputStream, length: Long) {
        val buffer = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0 && running) {
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read == -1) return
            if (read > 0) {
                remaining -= read
                sendChunk(id, buffer, read)
            }
        }
    }

    private fun streamUntilEof(id: String, input: InputStream) {
        val buffer = ByteArray(16 * 1024)
        while (running) {
            val read = input.read(buffer)
            if (read == -1) return
            if (read > 0) sendChunk(id, buffer, read)
        }
    }

    private fun sendChunk(id: String, buffer: ByteArray, length: Int) {
        val encoded = Base64.encodeToString(buffer, 0, length, Base64.NO_WRAP)
        sendJson(JSONObject().put("type", "http-chunk").put("id", id).put("data", encoded))
    }

    private fun sendJson(message: JSONObject) {
        try {
            relaySocket?.sendText(message.toString())
        } catch (error: Exception) {
            Log.w(TAG, "Could not send relay message: ${error.message}")
        }
    }

    private fun openLoopbackSocket(port: Int, tls: Boolean): Socket {
        val factory: SocketFactory = if (tls) trustAllLoopbackContext.socketFactory else SocketFactory.getDefault()
        val socket = factory.createSocket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
        socket.soTimeout = 60_000
        if (socket is SSLSocket) socket.startHandshake()
        return socket
    }

    private fun closePendingSockets() {
        val sockets = pendingSockets.values.toList()
        pendingSockets.clear()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun readHttpLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return builder.takeIf { it.isNotEmpty() }?.toString()
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                builder.append(next.toChar())
                if (builder.length > 16 * 1024) throw IOException("Camera response line is too long")
            }
        }
        return builder.toString()
    }

    private fun publishStatus(connected: Boolean, status: String) {
        this.connected = connected
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_CONNECTED, connected)
            .putString(PREF_STATUS, status)
            .putLong("relay_status_updated_at", System.currentTimeMillis())
            .apply()
        onStatusChanged(connected, status)
    }

    private fun startVideoWriter() {
        videoWriter = Thread({
            while (running) {
                val frame = try {
                    videoQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                try {
                    relaySocket?.sendBinary(frame)
                } catch (_: Exception) {
                    relaySocket?.close()
                }
            }
        }, "RelayVideoWriter").apply {
            isDaemon = true
            start()
        }
    }

    private val trustAllLoopbackContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }
}

package com.github.digitallyrefined.androidipcamera.opentunnel

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * OpenTunnel terminates the public TLS connection and forwards plain HTTP to
 * the local target. The camera's own server normally speaks HTTPS, so this
 * loopback-only bridge accepts plain TCP from OpenTunnel, opens a local TLS
 * client connection to the camera server and copies bytes both ways.
 *
 * It never leaves the device and only trusts the local server, so the
 * self-signed camera certificate is accepted.
 */
class LocalTlsBridge(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val tlsVersion: String,
    private val onLog: (String) -> Unit = {}
) {
    private val tag = "LocalTlsBridge"
    private var serverSocket: ServerSocket? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var acceptThread: Thread? = null
    @Volatile private var stopped = false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    private val protocol: String = if (tlsVersion == "1.2") "TLSv1.2" else "TLSv1.3"

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
    }

    /** Binds an ephemeral loopback port and starts accepting. Returns that port. */
    fun start(): Int {
        if (serverSocket != null) return port
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
        serverSocket = socket
        stopped = false
        acceptThread = Thread({
            while (!stopped) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (!stopped) Log.w(tag, "accept failed: ${e.message}")
                    break
                }
                Thread({ handle(client) }, "opentunnel-bridge-${client.port}").start()
            }
        }, "opentunnel-bridge-accept").also { it.start() }
        onLog("Local TLS bridge listening on 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sockets.forEach { closeQuietly(it) }
        sockets.clear()
        acceptThread?.interrupt()
        acceptThread = null
        onLog("Local TLS bridge stopped")
    }

    private fun handle(client: Socket) {
        sockets.add(client)
        var upstream: SSLSocket? = null
        try {
            val ssl = sslContext.socketFactory.createSocket(upstreamHost, upstreamPort) as SSLSocket
            upstream = ssl
            sockets.add(ssl)
            ssl.enabledProtocols = arrayOf(protocol)
            ssl.soTimeout = 15_000
            ssl.startHandshake()
            ssl.soTimeout = 0

            val closeBoth: () -> Unit = {
                closeQuietly(client)
                closeQuietly(ssl)
                sockets.remove(client)
                sockets.remove(ssl)
            }
            Thread({
                pump(client.getInputStream(), ssl.getOutputStream(), closeBoth)
            }, "opentunnel-bridge-up").start()
            Thread({
                pump(ssl.getInputStream(), client.getOutputStream(), closeBoth)
            }, "opentunnel-bridge-down").start()
        } catch (e: Exception) {
            Log.w(tag, "local TLS connection failed: ${e.message}")
            closeQuietly(upstream)
            closeQuietly(client)
            sockets.remove(client)
            upstream?.let { sockets.remove(it) }
        }
    }

    private fun pump(input: InputStream, output: OutputStream, closeBoth: () -> Unit) {
        try {
            input.copyTo(output, DEFAULT_BUFFER_SIZE)
            output.flush()
        } catch (_: Exception) {
            // A viewer closing the stream or a server restart is normal.
        } finally {
            closeBoth()
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 16 * 1024
    }
}

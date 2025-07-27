package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class StreamingServerHelper(
    private val context: Context,
    private val streamPort: Int = 4444,
    private val maxClients: Int = 3,
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {}
) {
    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter
    )

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    fun getClients(): List<Client> = clients.toList()

    fun startStreamingServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val useCertificate = prefs.getBoolean("use_certificate", false)
                val certificatePath = if (useCertificate) prefs.getString("certificate_path", null) else null
                val certificatePassword = if (useCertificate) {
                    prefs.getString("certificate_password", "")?.let {
                        if (it.isEmpty()) null else it.toCharArray()
                    }
                } else null

                serverSocket = if (certificatePath != null) {
                    try {
                        val uri = certificatePath.toUri()
                        val privateFile = File(context.filesDir, "certificate.p12")
                        if (privateFile.exists()) privateFile.delete()
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            privateFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Failed to open certificate file")
                        privateFile.inputStream().use { inputStream ->
                            val keyStore = KeyStore.getInstance("PKCS12")
                            keyStore.load(inputStream, certificatePassword)
                            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                            keyManagerFactory.init(keyStore, certificatePassword)
                            val sslContext = SSLContext.getInstance("TLSv1.2")
                            sslContext.init(keyManagerFactory.keyManagers, null, null)
                            val sslServerSocketFactory = sslContext.serverSocketFactory
                            (sslServerSocketFactory.createServerSocket(streamPort, 50, null) as SSLServerSocket).apply {
                                enabledProtocols = arrayOf("TLSv1.2")
                                enabledCipherSuites = supportedCipherSuites
                                reuseAddress = true
                                soTimeout = 30000
                            }
                        } ?: ServerSocket(streamPort)
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            onLog("Failed to create SSL server socket: ${e.message}")
                            Toast.makeText(context, "Failed to create SSL server socket: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        ServerSocket(streamPort)
                    }
                } else {
                    ServerSocket(streamPort, 50, null).apply {
                        reuseAddress = true
                        soTimeout = 30000
                    }
                }
                onLog("Server started on port $streamPort (${if (certificatePath != null) "HTTPS" else "HTTP"})")
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        if (handleMaxClients(socket)) continue
                        val outputStream = socket.getOutputStream()
                        val writer = PrintWriter(outputStream, true)
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val pref = PreferenceManager.getDefaultSharedPreferences(context)
                        val username = pref.getString("username", "") ?: ""
                        val password = pref.getString("password", "") ?: ""
                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            val headers = mutableListOf<String>()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line.isNullOrEmpty()) break
                                headers.add(line!!)
                            }
                            val authHeader = headers.find { it.startsWith("Authorization: Basic ") }
                            if (authHeader == null) {
                                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                                writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                                writer.print("Connection: close\r\n\r\n")
                                writer.flush()
                                socket.close()
                                continue
                            }
                            val providedAuth = String(Base64.decode(authHeader.substring(21), Base64.DEFAULT))
                            if (providedAuth != "$username:$password") {
                                writer.print("HTTP/1.1 401 Unauthorized\r\n\r\n")
                                writer.flush()
                                socket.close()
                                continue
                            }
                        }
                        writer.print("HTTP/1.0 200 OK\r\n")
                        writer.print("Connection: close\r\n")
                        writer.print("Cache-Control: no-cache\r\n")
                        writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                        writer.flush()
                        clients.add(Client(socket, outputStream, writer))
                        onLog("Client connected")
                        onClientConnected()
                        val delay = pref.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
                        Thread.sleep(delay)
                    } catch (e: IOException) {
                        // Ignore
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } catch (e: IOException) {
                onLog("Could not start server: ${e.message}")
            }
        }
    }

    fun handleMaxClients(socket: Socket): Boolean {
        if (clients.size >= maxClients) {
            socket.getOutputStream().writer().use { writer ->
                writer.write("HTTP/1.1 503 Service Unavailable\r\n\r\n")
                writer.flush()
            }
            socket.close()
            return true
        }
        return false
    }

    fun closeClientConnection() {
        clients.forEach { client ->
            try {
                client.socket.close()
            } catch (e: IOException) {
                onLog("Error closing client connection: ${e.message}")
            }
        }
        clients.clear()
        onClientDisconnected()
    }

    fun removeClient(client: Client) {
        clients.remove(client)
        onClientDisconnected()
    }
}

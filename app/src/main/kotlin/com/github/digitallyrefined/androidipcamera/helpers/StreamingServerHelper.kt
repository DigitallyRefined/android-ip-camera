package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class StreamingServerHelper(
    private val context: Context,
    private val streamPort: Int = 4444,
    private val maxClients: Int = 3,
    private val maxAuthenticatedClients: Int = 10, // Higher limit for authenticated users
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {}
) {
    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter,
        val connectedAt: Long = System.currentTimeMillis(),
        val isAuthenticated: Boolean = false
    )

    private data class FailedAttempt(
        var count: Int = 0,
        var lastAttempt: Long = 0L,
        var blockedUntil: Long = 0L
    )

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val failedAttempts = ConcurrentHashMap<String, FailedAttempt>()

    // SECURITY: Rate limiting constants (only for unauthenticated connections)
    private val MAX_FAILED_ATTEMPTS = 5  // 5 failed attempts allowed
    private val BLOCK_DURATION_MS = 15 * 60 * 1000L // 15 minutes block for unauthenticated
    private val RESET_WINDOW_MS = 10 * 60 * 1000L // 10 minutes reset window

    // Connection limits
    private val MAX_CONNECTION_DURATION_MS = 30 * 60 * 1000L // 30 minutes max per connection (unauthenticated)
    private val MAX_AUTHENTICATED_CONNECTION_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours for authenticated users
    private val CONNECTION_READ_TIMEOUT_MS = 30 * 1000 // 30 seconds read timeout
    private val SOCKET_TIMEOUT_MS = 60 * 1000 // 60 seconds socket timeout

    fun getClients(): List<Client> = clients.toList()

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Check if currently blocked
        if (now < attempt.blockedUntil) {
            return true
        }

        // Reset counter if outside window
        if (now - attempt.lastAttempt > RESET_WINDOW_MS) {
            attempt.count = 0
        }

        return false
    }

    private fun recordFailedAttempt(clientIp: String) {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Prevent integer overflow
        if (attempt.count < Int.MAX_VALUE - 1) {
            attempt.count++
        }
        attempt.lastAttempt = now

        if (attempt.count >= MAX_FAILED_ATTEMPTS) {
            attempt.blockedUntil = now + BLOCK_DURATION_MS
            onLog("SECURITY: IP $clientIp blocked for ${BLOCK_DURATION_MS / (60 * 1000)} minutes due to too many unauthenticated attempts")
        }
    }

    fun startStreamingServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val secureStorage = SecureStorage(context)
                val certificatePath = prefs.getString("certificate_path", null)
                val certificatePassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")?.let {
                    if (it.isEmpty()) null else it.toCharArray()
                }

                // Certificate setup required - no defaults
                var finalCertificatePath = certificatePath
                var finalCertificatePassword = certificatePassword

                if (certificatePath == null) {
                    // Use personal certificate from assets - requires password configuration
                    try {
                        val personalCertFile = File(context.filesDir, "personal_certificate.p12")
                        if (!personalCertFile.exists()) {
                            // Try to copy from assets first
                            try {
                                context.assets.open("personal_certificate.p12").use { input ->
                                    personalCertFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            } catch (assetException: Exception) {
                                // Certificate not in assets - provide helpful error
                                Handler(Looper.getMainLooper()).post {
                                    onLog("Certificate not found. Run setup.bat to generate it, then configure password in settings.")
                                    Toast.makeText(context,
                                        "Certificate missing. Run 'setup.bat' to generate certificate, then set password in Settings.",
                                        Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }
                        }
                        finalCertificatePath = personalCertFile.absolutePath

                        // Require certificate password to be configured
                        if (finalCertificatePassword == null) {
                            Handler(Looper.getMainLooper()).post {
                                onLog("Certificate password not configured. Set it in app Settings.")
                                Toast.makeText(context,
                                    "Certificate password required. Configure it in Settings > Advanced Security.",
                                    Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }

                        Handler(Looper.getMainLooper()).post {
                            onLog("Using configured certificate for secure setup")
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            onLog("ERROR: Could not load certificate: ${e.message}")
                            Toast.makeText(context, "Certificate error. Check password in Settings.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                val bindAddress = InetAddress.getByName("0.0.0.0")

                serverSocket = try {
                    // Determine which certificate file to use
                    val certFile = if (certificatePath != null) {
                        // Custom certificate - copy from URI to local file
                        val uri = certificatePath.toUri()
                        val privateFile = File(context.filesDir, "certificate.p12")
                        if (privateFile.exists()) privateFile.delete()
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            privateFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Failed to open certificate file")
                        privateFile
                    } else {
                        // Personal certificate
                        File(finalCertificatePath!!)
                    }

                    certFile.inputStream().use { inputStream ->
                        try {
                            val keyStore = KeyStore.getInstance("PKCS12")
                            keyStore.load(inputStream, finalCertificatePassword)
                            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                            keyManagerFactory.init(keyStore, finalCertificatePassword)
                            val sslContext = SSLContext.getInstance("TLSv1.3")
                            sslContext.init(keyManagerFactory.keyManagers, null, null)
                            val sslServerSocketFactory = sslContext.serverSocketFactory
                            (sslServerSocketFactory.createServerSocket(streamPort, 50, bindAddress) as SSLServerSocket).apply {
                                enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                                enabledCipherSuites = arrayOf(
                                    "TLS_AES_256_GCM_SHA384",
                                    "TLS_AES_128_GCM_SHA256",
                                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
                                )
                                reuseAddress = true
                                soTimeout = 30000
                            }
                        } catch (keystoreException: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                onLog("Certificate loading failed: ${keystoreException.message}")
                                val errorMsg = when {
                                    keystoreException.message?.contains("password") == true ->
                                        "Certificate password is incorrect. Check Settings > Advanced Security."
                                    keystoreException.message?.contains("keystore") == true ->
                                        "Certificate file is corrupted or invalid. Regenerate with setup.bat."
                                    else ->
                                        "Certificate error: ${keystoreException.message}"
                                }
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        onLog("CRITICAL: Failed to create HTTPS server: ${e.message}")
                        Toast.makeText(context, "Failed to start secure HTTPS server: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                onLog("Server started on port $streamPort (${if (certificatePath != null) "HTTPS" else "HTTP"})")
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        val outputStream = socket.getOutputStream()
                        val writer = PrintWriter(outputStream, true)
                        val clientIp = socket.inetAddress.hostAddress

                        // Configure socket timeouts for security
                        socket.soTimeout = SOCKET_TIMEOUT_MS

                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val secureStorage = SecureStorage(context)
                        val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
                        val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""

                        // SECURITY: Authentication is now MANDATORY for all connections
                        // Validate stored credentials - require both username and password
                        val username = InputValidator.validateAndSanitizeUsername(rawUsername)
                        val password = InputValidator.validateAndSanitizePassword(rawPassword)

                        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                            // CRITICAL: No valid credentials configured - reject all connections
                            recordFailedAttempt(clientIp)
                            writer.print("HTTP/1.1 403 Forbidden\r\n")
                            writer.print("Content-Type: text/plain\r\n")
                            writer.print("Connection: close\r\n\r\n")
                            writer.print("SECURITY ERROR: Authentication credentials not properly configured.\r\n")
                            writer.print("Please configure username and password in app settings.\r\n")
                            writer.flush()
                            socket.close()
                            onLog("SECURITY: Connection rejected - authentication credentials not configured")
                            continue
                        }

                        // Read HTTP headers
                        val headers = mutableListOf<String>()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line.isNullOrEmpty()) break
                            headers.add(line!!)
                        }

                        // SECURITY: Require Basic Authentication header for all requests
                        val authHeader = headers.find { it.startsWith("Authorization: Basic ") }
                        if (authHeader == null) {
                            // Rate limiting ONLY applies to unauthenticated requests
                            if (isRateLimited(clientIp)) {
                                writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                                writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for unauthenticated
                                writer.print("Connection: close\r\n\r\n")
                                writer.flush()
                                socket.close()
                                onLog("SECURITY: Rate limited unauthenticated request from $clientIp")
                                Thread.sleep(100)
                                continue
                            }
                            recordFailedAttempt(clientIp)
                            writer.print("HTTP/1.1 401 Unauthorized\r\n")
                            writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                            writer.print("Connection: close\r\n\r\n")
                            writer.flush()
                            socket.close()
                            continue
                        }

                        val providedAuth = String(Base64.decode(authHeader.substring(21), Base64.DEFAULT))
                        if (providedAuth != "$username:$password") {
                            // Rate limiting ONLY applies to failed authentication attempts
                            if (isRateLimited(clientIp)) {
                                writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                                writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for failed auth
                                writer.print("Connection: close\r\n\r\n")
                                writer.flush()
                                socket.close()
                                onLog("SECURITY: Rate limited failed auth attempt from $clientIp")
                                Thread.sleep(100)
                                continue
                            }
                            recordFailedAttempt(clientIp)
                            writer.print("HTTP/1.1 401 Unauthorized\r\n\r\n")
                            writer.flush()
                            socket.close()
                            onLog("SECURITY: Failed authentication attempt from $clientIp")
                            continue
                        }

                        // AUTHENTICATED CONNECTION - No rate limiting, higher connection limits
                        if (handleMaxClients(socket, isAuthenticated = true)) continue

                        writer.print("HTTP/1.0 200 OK\r\n")
                        writer.print("Connection: close\r\n")
                        writer.print("Cache-Control: no-cache\r\n")
                        writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                        writer.flush()
                        clients.add(Client(socket, outputStream, writer, System.currentTimeMillis(), isAuthenticated = true))
                        onLog("Authenticated client connected from $clientIp")
                        onClientConnected()
                        val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
                        Thread.sleep(delay)

                        // Periodic cleanup of expired connections (every 10 iterations)
                        if (clients.size > 0 && System.currentTimeMillis() % 10 == 0L) {
                            cleanupExpiredConnections()
                        }
                    } catch (e: IOException) {
                        // Ignore connection errors
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

    fun handleMaxClients(socket: Socket, isAuthenticated: Boolean = false): Boolean {
        val maxAllowed = if (isAuthenticated) maxAuthenticatedClients else maxClients
        if (clients.size >= maxAllowed) {
            socket.getOutputStream().writer().use { writer ->
                writer.write("HTTP/1.1 503 Service Unavailable\r\n")
                writer.write("Retry-After: 30\r\n") // 30 seconds
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
            }
            socket.close()
            // Add small delay to prevent rapid reconnection loops
            Thread.sleep(100)
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
        try {
            client.socket.close()
        } catch (e: IOException) {
            onLog("Error closing client socket: ${e.message}")
        }
        onClientDisconnected()
    }

    private fun cleanupExpiredConnections() {
        val now = System.currentTimeMillis()
        val toRemove = clients.filter { client ->
            val maxDuration = if (client.isAuthenticated) MAX_AUTHENTICATED_CONNECTION_DURATION_MS else MAX_CONNECTION_DURATION_MS
            now - client.connectedAt > maxDuration
        }

        toRemove.forEach { client ->
            val authStatus = if (client.isAuthenticated) "authenticated" else "unauthenticated"
            onLog("Removing expired $authStatus connection from ${client.socket.inetAddress.hostAddress}")
            removeClient(client)
        }
    }
}

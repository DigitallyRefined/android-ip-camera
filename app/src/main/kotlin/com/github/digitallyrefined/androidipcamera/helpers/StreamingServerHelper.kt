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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private var serverJob: Job? = null
    @Volatile
    private var isStarting = false
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
        // Show toast when starting server
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Server starting...", Toast.LENGTH_SHORT).show()
        }

        // Prevent concurrent starts
        synchronized(this) {
            if (isStarting) {
                return
            }

            // Check if server is already running
            if (serverJob != null && serverSocket != null && !serverSocket!!.isClosed) {
                return
            }

            isStarting = true
        }

        // Stop existing server BEFORE creating new one (outside the coroutine)
        // This must be done to avoid cancelling the new job
        val oldJob: Job?
        val oldSocket: ServerSocket?
        synchronized(this) {
            oldJob = serverJob
            oldSocket = serverSocket
            serverJob = null
            serverSocket = null
        }

        // Stop old server and wait for it to fully stop (if it exists)
        if (oldJob != null || oldSocket != null) {
            runBlocking(Dispatchers.IO) {
                try {
                    oldSocket?.close()
                } catch (e: IOException) {
                    onLog("Error closing old server socket: ${e.message}")
                }
                oldJob?.cancel()
                try {
                    oldJob?.join()
                } catch (e: Exception) {
                    // Ignore cancellation exceptions
                }
                // Small delay to ensure port is released
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        synchronized(this) {
              serverJob = CoroutineScope(Dispatchers.IO).launch {
              try {
                  val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                  val secureStorage = SecureStorage(context)
                  val certificatePath = prefs.getString("certificate_path", null)

                  val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
                  val certificatePassword = rawPassword?.let {
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
                                      onLog("Certificate not found.")
                                      Toast.makeText(context,
                                          "Certificate missing. Reset app to generate a new certificate",
                                          Toast.LENGTH_LONG).show()
                                  }
                                  return@launch
                              }
                          }
                          finalCertificatePath = personalCertFile.absolutePath

                          // Require certificate password to be configured
                          if (finalCertificatePassword == null) {
                              return@launch
                          }

                      } catch (e: Exception) {
                          Handler(Looper.getMainLooper()).post {
                              onLog("ERROR: Could not load certificate: ${e.message}")
                              Toast.makeText(context, "Certificate error. Check certificate file and password in Settings.", Toast.LENGTH_LONG).show()
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
                                  reuseAddress = true
                                  enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                                  enabledCipherSuites = arrayOf(
                                      "TLS_AES_256_GCM_SHA384",
                                      "TLS_AES_128_GCM_SHA256",
                                      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                      "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
                                  )
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
                  // Clear the starting flag now that server is running
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
                  Handler(Looper.getMainLooper()).post {
                      Toast.makeText(context, "Server started", Toast.LENGTH_SHORT).show()
                  }
                  while (isActive && !Thread.currentThread().isInterrupted) {
                      try {
                          val socket = serverSocket?.accept() ?: continue
                          val clientIp = socket.inetAddress.hostAddress

                          // Handle each connection in a separate coroutine to avoid blocking the accept loop
                          CoroutineScope(Dispatchers.IO).launch {
                              handleClientConnection(socket, clientIp)
                          }
                      } catch (e: IOException) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          // Ignore other connection errors
                      } catch (e: InterruptedException) {
                          Thread.currentThread().interrupt()
                          break
                      } catch (e: Exception) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          onLog("Unexpected error in server loop: ${e.message}")
                      }
                  }
              } catch (e: IOException) {
                  onLog("Could not start server: ${e.message}")
              } finally {
                  // Clear the starting flag if server failed to start
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
              }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket, clientIp: String) {
        try {
            val outputStream = socket.getOutputStream()
            val writer = PrintWriter(outputStream, true)

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
                              writer.print("Configure username and password in app settings.\r\n")
                              writer.flush()
                              socket.close()
                              onLog("SECURITY: Connection rejected - authentication credentials not configured")
                              return
                          }

                          // Read HTTP headers
                          val headers = mutableListOf<String>()
                          var line: String?
                          while (reader.readLine().also { line = it } != null) {
                              if (line.isNullOrEmpty()) break
                              headers.add(line!!)
                          }

                          // SECURITY: Require Basic Authentication header for all requests
                          // Parse headers in a robust, case-insensitive way (RFC 7230: header field names are case-insensitive)
                          val authHeaderPair = headers.mapNotNull { hdr ->
                              val idx = hdr.indexOf(":")
                              if (idx == -1) return@mapNotNull null
                              val name = hdr.substring(0, idx).trim()
                              val value = hdr.substring(idx + 1).trim()
                              name to value
                          }.find { (name, value) ->
                              name.equals("Authorization", ignoreCase = true) && value.startsWith("Basic ", ignoreCase = true)
                          }

                          if (authHeaderPair == null) {
                              // Rate limiting ONLY applies to unauthenticated requests
                              if (isRateLimited(clientIp)) {
                                  writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                                  writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for unauthenticated
                                  writer.print("Connection: close\r\n\r\n")
                                  writer.flush()
                                  socket.close()
                                  onLog("SECURITY: Rate limited unauthenticated request from $clientIp")
                                  Thread.sleep(100)
                                  return
                              }
                              recordFailedAttempt(clientIp)
                              writer.print("HTTP/1.1 401 Unauthorized\r\n")
                              writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                              writer.print("Connection: close\r\n\r\n")
                              writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                              writer.flush()
                              socket.close()
                              return
                          }

                          val authValue = authHeaderPair.second
                          val providedAuthEncoded = authValue.substringAfter("Basic ", "")
                          val providedAuth = try {
                              val decoded = Base64.decode(providedAuthEncoded, Base64.DEFAULT)
                              String(decoded)
                          } catch (e: IllegalArgumentException) {
                              // Malformed base64
                              if (isRateLimited(clientIp)) {
                                  writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                                  writer.print("Retry-After: 30\r\n")
                                  writer.print("Connection: close\r\n\r\n")
                                  writer.flush()
                                  socket.close()
                                  onLog("SECURITY: Rate limited malformed auth attempt from $clientIp")
                                  Thread.sleep(100)
                                  return
                              }
                              recordFailedAttempt(clientIp)
                              writer.print("HTTP/1.1 401 Unauthorized\r\n")
                              writer.print("Connection: close\r\n\r\n")
                              writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                              writer.flush()
                              socket.close()
                              onLog("SECURITY: Failed authentication attempt from $clientIp (malformed base64)")
                              return
                          }

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
                                  return
                              }
                              recordFailedAttempt(clientIp)
                              writer.print("HTTP/1.1 401 Unauthorized\r\n")
                              writer.print("Connection: close\r\n\r\n")
                              writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                              writer.flush()
                              socket.close()
                              onLog("SECURITY: Failed authentication attempt from $clientIp")
                              return
                          }

                          // AUTHENTICATED CONNECTION - No rate limiting, higher connection limits
                          if (handleMaxClients(socket, isAuthenticated = true)) return

                          // Send HTTP response headers for MJPEG stream
                          // Use HTTP/1.1 with keep-alive for better streaming performance
                          writer.print("HTTP/1.1 200 OK\r\n")
                          writer.print("Connection: keep-alive\r\n")
                          writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                          writer.print("Pragma: no-cache\r\n")
                          writer.print("Expires: 0\r\n")
                          writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                          writer.flush()

                          // Add client to list - frames will be sent from MainActivity.processImage()
                          clients.add(Client(socket, outputStream, writer, System.currentTimeMillis(), isAuthenticated = true))
                          onClientConnected()

                          // Keep connection alive - frames will be sent from MainActivity.processImage()
                          // Wait for connection to close or be removed
                          try {
                              // Read from socket to detect when client disconnects
                              while (socket.isConnected && !socket.isClosed) {
                                  // Check if socket has data (client disconnect will cause exception)
                                  if (reader.ready()) {
                                      val line = reader.readLine()
                                      if (line == null) break // Client disconnected
                                  }
                                  Thread.sleep(1000) // Check every second
                              }
                          } catch (e: IOException) {
                              // Client disconnected
                          } finally {
                              // Remove client when connection closes
                              clients.removeIf { it.socket == socket }
                              try {
                                  socket.close()
                              } catch (e: Exception) {
                                  // Ignore
                              }
                              onClientDisconnected()
                          }
        } catch (e: Exception) {
            onLog("Error handling client connection from $clientIp: ${e.message}")
            try {
                socket.close()
            } catch (closeException: Exception) {
                // Ignore
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

    suspend fun stopStreamingServer() {
        val jobToCancel: Job?
        val socketToClose: ServerSocket?

        synchronized(this) {
            // Get references to close outside synchronized block
            jobToCancel = serverJob
            socketToClose = serverSocket
            serverSocket = null
            serverJob = null
        }

        // If there's nothing to stop, return immediately
        if (jobToCancel == null && socketToClose == null) {
            return
        }

        // Run socket closing operations on background thread to avoid NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
            // Close the server socket first to interrupt any blocking accept() calls
            try {
                socketToClose?.close()
            } catch (e: IOException) {
                onLog("Error closing server socket: ${e.message}")
            }

            // Cancel the server coroutine and wait for it to finish
            jobToCancel?.cancel()
            try {
                // Wait for the coroutine to finish
                jobToCancel?.join()
            } catch (e: Exception) {
                onLog("Error waiting for server job: ${e.message}")
            }

            // Close all client connections (this involves network operations)
            closeClientConnection()

            // Wait a bit longer to ensure port is fully released
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

        }
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

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val onClientDisconnected: () -> Unit = {},
    private val onControlCommand: (String, String) -> Unit = { _, _ -> },
    private val onSnapshot: (String) -> ByteArray? = { null }
) {
    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter,
        val connectedAt: Long = System.currentTimeMillis(),
        val isAuthenticated: Boolean = false,
        @Volatile var waitingKey: Boolean = true // H.264: skip frames until first keyframe
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
    private val h264Clients = CopyOnWriteArrayList<Client>()  // raw H.264 (/h264) viewers
    private val failedAttempts = ConcurrentHashMap<String, FailedAttempt>()
    @Volatile
    private var appInForeground: Boolean = true

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
    fun getH264Clients(): List<Client> = h264Clients.toList()
    fun resetH264Wait() { h264Clients.forEach { it.waitingKey = true } }  // resync viewers at next keyframe
    fun removeH264Client(client: Client) {
        h264Clients.remove(client)
        try { client.socket.close() } catch (_: Exception) {}
        onClientDisconnected()
    }

    fun stopServer() {
        serverJob?.cancel()
        serverSocket?.close()
        clients.forEach { client ->
            try {
                client.socket.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
        clients.clear()
    }

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

        // Show toast when starting server
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Server starting...", Toast.LENGTH_SHORT).show()
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
                                          "Certificate missing, reset app to generate a new certificate",
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
                              Toast.makeText(context, "Certificate error, check certificate file and password in Settings", Toast.LENGTH_LONG).show()
                          }
                          return@launch
                      }
                  }

                  val bindAddress = InetAddress.getByName("0.0.0.0")

                  // Get TLS version preference
                  val tlsVersionPref = prefs.getString("tls_version", "1.3") ?: "1.3"
                  val useTLS = tlsVersionPref != "disabled"

                  serverSocket = if (useTLS) {
                      try {
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

                          // Try TLS versions with fallback
                          val tlsVersionsToTry = when (tlsVersionPref) {
                              "1.3" -> listOf("TLSv1.3", "TLSv1.2")
                              "1.2" -> listOf("TLSv1.2")
                              else -> listOf("TLSv1.3", "TLSv1.2")
                          }

                          var sslServerSocket: SSLServerSocket? = null
                          var lastError: Exception? = null
                          var actualTlsVersionUsed: String? = null

                          for (tlsVersion in tlsVersionsToTry) {
                              try {
                                  certFile.inputStream().use { inputStream ->
                                      val keyStore = KeyStore.getInstance("PKCS12")
                                      keyStore.load(inputStream, finalCertificatePassword)
                                      val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                                      keyManagerFactory.init(keyStore, finalCertificatePassword)

                                      val sslContext = try {
                                          SSLContext.getInstance(tlsVersion)
                                      } catch (e: Exception) {
                                          // TLS version not supported, try next
                                          lastError = e
                                          continue
                                      }

                                      sslContext.init(keyManagerFactory.keyManagers, null, null)
                                      val sslServerSocketFactory = sslContext.serverSocketFactory
                                      sslServerSocket = (sslServerSocketFactory.createServerSocket(streamPort, 50, bindAddress) as SSLServerSocket).apply {
                                          reuseAddress = true
                                          enabledProtocols = arrayOf(tlsVersion)
                                          // Don't restrict cipher suites - let the system negotiate
                                          soTimeout = 30000
                                      }
                                      actualTlsVersionUsed = tlsVersion
                                      onLog("Server started with TLS $tlsVersion")
                                      break
                                  }
                              } catch (keystoreException: Exception) {
                                  lastError = keystoreException
                                  continue
                              }
                          }

                          if (sslServerSocket == null) {
                              // Both TLS versions failed, disable TLS
                              prefs.edit().putString("tls_version", "disabled").apply()
                              throw lastError ?: IOException("Failed to create SSL server socket with any TLS version")
                          }

                          // Update preference if we fell back to a different version
                          if (actualTlsVersionUsed != null && tlsVersionPref != actualTlsVersionUsed) {
                              val newPrefValue = when (actualTlsVersionUsed) {
                                  "TLSv1.3" -> "1.3"
                                  "TLSv1.2" -> "1.2"
                                  else -> "disabled"
                              }
                              prefs.edit().putString("tls_version", newPrefValue).apply()
                              onLog("Updated TLS version preference to $actualTlsVersionUsed (fallback)")
                          }

                          sslServerSocket
                      } catch (keystoreException: Exception) {
                          Handler(Looper.getMainLooper()).post {
                              onLog("Certificate loading failed: ${keystoreException.message}")
                              val errorMsg = when {
                                  keystoreException.message?.contains("password") == true ->
                                      "Certificate password is incorrect, check Settings > Advanced Security"
                                  keystoreException.message?.contains("keystore") == true ->
                                      "Certificate file is corrupted or invalid, regenerate with setup.bat"
                                  else ->
                                      "Certificate error: ${keystoreException.message}"
                              }
                              Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                          }
                          return@launch
                      }
                  } else {
                      // Use HTTP (no TLS)
                      try {
                          ServerSocket(streamPort, 50, bindAddress).apply {
                                  reuseAddress = true
                                  soTimeout = 30000
                              }
                      } catch (e: Exception) {
                          Handler(Looper.getMainLooper()).post {
                              onLog("CRITICAL: Failed to create HTTP server: ${e.message}")
                              Toast.makeText(context, "Failed to start HTTP server: ${e.message}", Toast.LENGTH_LONG).show()
                          }
                          return@launch
                      }
                  }
                  onLog("Server started on port $streamPort (${if (useTLS) "HTTPS" else "HTTP"})")
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

            // Read the request line (e.g., GET /stream HTTP/1.1)
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return
            val uri = requestParts[1]

            val secureStorage = SecureStorage(context)
            val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
            val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""

            // Check if authentication is enabled
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val enableAuth = prefs.getBoolean("enable_auth", true)

            // Validate stored credentials if auth is enabled
            val username = if (enableAuth) {
                InputValidator.validateAndSanitizeUsername(rawUsername)
            } else {
                null
            }
            val password = if (enableAuth) {
                InputValidator.validateAndSanitizePassword(rawPassword)
            } else {
                null
            }

            if (enableAuth) {
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
            }

            // Read HTTP headers
            val headers = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                headers.add(line!!)
            }

            // SECURITY: Require Basic Authentication header for all requests (when auth is enabled)
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

            if (enableAuth) {
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
            }

            // Handle Control UI and Commands
            if (uri == "/" || uri == "") {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val curCamera = prefs.getString("last_camera_facing", "back") ?: "back"
                val curResolution = prefs.getString("camera_resolution", "low") ?: "low"
                val curZoom = prefs.getString("camera_zoom", "1.0") ?: "1.0"
                val curScale = prefs.getString("stream_scale", "1.0") ?: "1.0"
                val curExposure = prefs.getString("camera_exposure", "0") ?: "0"
                val curContrast = prefs.getString("camera_contrast", "0") ?: "0"
                val curDelay = prefs.getString("stream_delay", "33") ?: "33"
                val curTorch = prefs.getString("camera_torch", "off") ?: "off"
                val curAudioGain = prefs.getString("audio_gain", "1.0") ?: "1.0"

                val htmlTemplate = try {
                    context.assets.open("index.html").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "<html><body>Error loading interface.</body></html>"
                }

                val htmlResponse = htmlTemplate
                    .replace("{{CUR_CAMERA}}", curCamera)
                    .replace("{{RES_LOW_SELECTED}}", if (curResolution == "low") "selected" else "")
                    .replace("{{RES_MEDIUM_SELECTED}}", if (curResolution == "medium") "selected" else "")
                    .replace("{{RES_HIGH_SELECTED}}", if (curResolution == "high") "selected" else "")
                    .replace("{{CUR_ZOOM}}", curZoom)
                    .replace("{{CUR_SCALE}}", curScale)
                    .replace("{{CUR_EXPOSURE}}", curExposure)
                    .replace("{{CUR_CONTRAST}}", curContrast)
                    .replace("{{CUR_AUDIO_GAIN}}", curAudioGain)
                    .replace("{{CUR_DELAY}}", curDelay)
                    .replace("{{CUR_TORCH}}", curTorch)

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(htmlResponse)
                writer.flush()
                socket.close()
                return
            }

            if (uri.startsWith("/snapshot")) {
                // One JPEG captured into RAM (no disk). ?camera=<id>. For polling / dual-camera views.
                val id = if (uri.contains("?")) uri.substringAfter("camera=", "").substringBefore("&") else ""
                val jpeg = onSnapshot(id)
                if (jpeg != null) {
                    writer.print("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n")
                    writer.print("Content-Length: ${jpeg.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    writer.flush(); outputStream.write(jpeg); outputStream.flush()
                } else {
                    writer.print("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\nno frame"); writer.flush()
                }
                try { socket.close() } catch (_: Exception) {}
                return
            }

            if (uri.contains("?")) {
                val query = uri.substringAfter("?")
                query.split("&").forEach { param ->
                    val keyValue = param.split("=")
                    if (keyValue.size == 2) {
                        onControlCommand(keyValue[0], keyValue[1])
                    }
                }
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nOK")
                writer.flush()
                socket.close()
                return
            }

            if (uri.startsWith("/audio")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                    return
                }
                try {
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Connection: keep-alive\r\n")
                    writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    writer.print("Pragma: no-cache\r\n")
                    writer.print("Expires: 0\r\n")
                    writer.print("Content-Type: audio/wav\r\n")
                    writer.print("Transfer-Encoding: chunked\r\n\r\n")
                    writer.flush()

                    val sampleRate = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_MONO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    if (minBuffer <= 0) {
                        throw IOException("Invalid AudioRecord buffer size: $minBuffer")
                    }
                    val bufferSize = minBuffer * 2
                    val audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    val wavHeader = createWavHeader(
                        sampleRate = sampleRate,
                        bitsPerSample = 16,
                        channels = 1
                    )
                    writeChunk(outputStream, wavHeader, wavHeader.size)

                    audioRecord.startRecording()
                    val pcmBuffer = ByteArray(bufferSize)
                    val audioPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                    try {
                        while (socket.isConnected && !socket.isClosed && appInForeground) {
                            val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                            if (read <= 0) continue

                            // Read audio gain dynamically to allow real-time changes
                            val audioGain = audioPrefs.getString("audio_gain", "1.0")?.toFloatOrNull() ?: 1.0f

                            // Apply audio gain if not 1.0
                            val processedBuffer = if (audioGain != 1.0f) {
                                applyAudioGain(pcmBuffer, read, audioGain)
                            } else {
                                pcmBuffer
                            }

                            writeChunk(outputStream, processedBuffer, read)
                        }
                    } finally {
                        try {
                            audioRecord.stop()
                        } catch (_: Exception) {
                        }
                        audioRecord.release()
                        try {
                            outputStream.write("0\r\n\r\n".toByteArray())
                            outputStream.flush()
                        } catch (_: Exception) {
                        }
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (sec: SecurityException) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    onLog("Audio stream error: ${e.message}")
                    try {
                        writer.print("HTTP/1.1 500 Internal Server Error\r\n")
                        writer.print("Content-Type: text/plain\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.print("Audio streaming failed.\r\n")
                        writer.flush()
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                return
            }

            if (uri.startsWith("/cameras")) {
                // One list, all properties: each camera with its OWN supported live sizes
                // (sizes/formats differ per camera). Capped to the device's queried HW encoder ceiling.
                val encCaps = H264HardwareEncoder.caps()
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val idle = clients.isEmpty() && h264Clients.isEmpty()
                val sb = StringBuilder("[")
                try {
                    cm.cameraIdList.forEachIndexed { i, id ->
                        val ch = cm.getCameraCharacteristics(id)
                        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "front"
                            CameraCharacteristics.LENS_FACING_BACK -> "back"
                            else -> "external"
                        }
                        val set = LinkedHashSet<Pair<Int, Int>>()
                        try {
                            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            map?.getOutputSizes(SurfaceTexture::class.java)?.forEach { set.add(it.width to it.height) }
                            map?.getOutputSizes(android.media.MediaCodec::class.java)?.forEach { set.add(it.width to it.height) }
                        } catch (_: Exception) {}
                        if (idle) {  // Camera1 preview sizes expose 16:9 that LEGACY Camera2 omits
                            try {
                                @Suppress("DEPRECATION") val c1 = android.hardware.Camera.open(id.toIntOrNull() ?: i)
                                @Suppress("DEPRECATION") c1.parameters.supportedPreviewSizes?.forEach { set.add(it.width to it.height) }
                                @Suppress("DEPRECATION") c1.release()
                            } catch (_: Exception) {}
                        }
                        val sizes = set.filter { it.first <= encCaps.maxW && it.second <= encCaps.maxH }.sortedByDescending { it.first * it.second }
                        if (i > 0) sb.append(",")
                        sb.append("{\"id\":\"$id\",\"facing\":\"$facing\",\"sizes\":[")
                        sizes.forEachIndexed { j, s -> if (j > 0) sb.append(","); sb.append("{\"w\":${s.first},\"h\":${s.second}}") }
                        sb.append("]}")
                    }
                } catch (_: Exception) {}
                sb.append("]")
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
                writer.print(sb.toString()); writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return
            }

            if (uri.startsWith("/h264")) {
                // Raw Annex-B H.264 elementary stream (hardware-encoded). Browser plays via jMuxer.
                if (handleMaxClients(socket, isAuthenticated = enableAuth)) return
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Connection: keep-alive\r\n")
                writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                writer.print("Content-Type: video/h264\r\n\r\n")
                writer.flush()
                val client = Client(socket, outputStream, writer, System.currentTimeMillis(), isAuthenticated = enableAuth)
                h264Clients.add(client)
                onClientConnected()  // starts camera + encoder
                try {
                    while (socket.isConnected && !socket.isClosed) {
                        if (reader.ready()) { if (reader.readLine() == null) break }
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {
                } finally {
                    h264Clients.remove(client)
                    try { socket.close() } catch (_: Exception) {}
                    onClientDisconnected()
                }
                return
            }

            if (uri.startsWith("/stream")) {
                // AUTHENTICATED CONNECTION if auth is enabled - No rate limiting, higher connection limits
                if (handleMaxClients(socket, isAuthenticated = enableAuth)) return

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
                clients.add(Client(socket, outputStream, writer, System.currentTimeMillis(), isAuthenticated = enableAuth))
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
            } else {
                writer.print("HTTP/1.1 404 Not Found\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Not Found\r\n")
                writer.flush()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
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

    private fun createWavHeader(sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataChunkSize = 0x7FFFFFFF // Placeholder large size for live stream
        val riffChunkSize = 36 + dataChunkSize

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(riffChunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataChunkSize)
        return buffer.array()
    }

    private fun writeChunk(outputStream: OutputStream, data: ByteArray, length: Int) {
        val header = length.toString(16) + "\r\n"
        outputStream.write(header.toByteArray())
        outputStream.write(data, 0, length)
        outputStream.write("\r\n".toByteArray())
        outputStream.flush()
    }

    private fun applyAudioGain(buffer: ByteArray, length: Int, gain: Float): ByteArray {
        // Process 16-bit PCM samples (2 bytes per sample, little-endian)
        val result = ByteArray(length)
        for (i in 0 until length step 2) {
            if (i + 1 >= length) break
            // Convert little-endian bytes to short
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            // Apply gain and clamp to prevent overflow
            val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            // Convert back to little-endian bytes
            result[i] = (amplified and 0xFF).toByte()
            result[i + 1] = ((amplified shr 8) and 0xFF).toByte()
        }
        return result
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

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
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

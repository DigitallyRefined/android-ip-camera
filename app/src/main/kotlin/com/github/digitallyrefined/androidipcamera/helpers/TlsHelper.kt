package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory

/**
 * TLS material shared by every server endpoint. The web server, RTSP and ONVIF all listen through
 * the same [serverSocketFactory], so they present the same certificate.
 */
class TlsServerConfig(
    val protocol: String,
    private val serverSocketFactory: SSLServerSocketFactory
) {
    /** Binds a TLS listening socket, matching the plain-socket setup used when TLS is disabled. */
    fun createServerSocket(
        port: Int,
        backlog: Int = 50,
        address: InetAddress = InetAddress.getByName("0.0.0.0"),
        soTimeout: Int = 0
    ): ServerSocket {
        // The three-argument factory method is implemented by every platform (including Android);
        // the unbound no-argument variant is not.
        val socket = serverSocketFactory.createServerSocket(port, backlog, address) as SSLServerSocket
        socket.reuseAddress = true
        // Negotiate only the configured version, like the web server does.
        socket.enabledProtocols = arrayOf(protocol)
        // Don't restrict cipher suites - let the system negotiate.
        socket.soTimeout = soTimeout
        return socket
    }
}

/**
 * Loads the certificate the web server already uses so RTSP and ONVIF can serve TLS with it:
 * the custom `certificate_path` when configured, otherwise the built-in `personal_certificate.p12`
 * (packaged with the app or generated on first use).
 */
object TlsHelper {
    private const val TAG = "TlsHelper"
    const val PREF_TLS_ENABLED = "tls_enabled"
    const val PREF_TLS_VERSION = "tls_version"
    const val PREF_CERTIFICATE_PATH = "certificate_path"
    const val DEFAULT_TLS_VERSION = "1.3"
    private const val BUILT_IN_CERTIFICATE = "personal_certificate.p12"

    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Default to enabled for backward compatibility with existing installs that never set it.
        return prefs.getBoolean(PREF_TLS_ENABLED, true)
    }

    fun tlsVersion(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_TLS_VERSION, DEFAULT_TLS_VERSION)
            ?.takeIf { it == "1.2" } ?: DEFAULT_TLS_VERSION

    /**
     * Builds the TLS configuration shared by all listeners, or null when TLS is disabled.
     * Throws when the certificate cannot be prepared, so callers can surface the error instead of
     * silently serving plaintext.
     */
    @Synchronized
    fun serverConfig(context: Context, onLog: (String) -> Unit = {}): TlsServerConfig? {
        if (!isEnabled(context)) return null
        val tlsVersion = tlsVersion(context)

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val secureStorage = SecureStorage(context)
        val certificatePath = prefs.getString(PREF_CERTIFICATE_PATH, null)

        var rawPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
        if (certificatePath == null && rawPassword.isNullOrEmpty()) {
            rawPassword = generateCertificatePassword()
            if (!secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, rawPassword)) {
                throw IOException("Unable to store the generated certificate password")
            }
        }
        val certificatePassword = rawPassword?.takeIf { it.isNotEmpty() }?.toCharArray()

        val certFile = if (certificatePath != null) {
            // The settings screen keeps a private copy of the selected certificate, which is what
            // the servers load. Only fall back to the source URI for installs configured before
            // that copy existed (or if the private copy was cleared).
            val privateFile = File(context.filesDir, "certificate.p12")
            if (!privateFile.exists()) {
                val uri = certificatePath.toUri()
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        privateFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Failed to open certificate file")
                } catch (e: SecurityException) {
                    throw IOException(
                        "Permission denied opening certificate file. Re-select the certificate in Settings using the file picker.",
                        e
                    )
                } catch (e: Exception) {
                    throw IOException("Failed to open certificate file: ${e.message}", e)
                }
            }
            privateFile
        } else {
            val personalCertFile = File(context.filesDir, BUILT_IN_CERTIFICATE)
            if (!personalCertFile.exists()) {
                // Preserve compatibility with packaged certificates, then generate a device-local
                // one when the upstream source tree has no certificate asset.
                try {
                    context.assets.open(BUILT_IN_CERTIFICATE).use { input ->
                        personalCertFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                    if (rawPassword.isNullOrEmpty() ||
                        CertificateHelper.generateCertificate(context, rawPassword) == null) {
                        throw IOException("Certificate generation failed")
                    }
                }
            }
            if (certificatePassword == null) throw IOException("Certificate password is not configured")
            personalCertFile
        }

        // Android 10 introduced the platform TLS 1.3 implementation. Older Android versions use
        // their platform TLS 1.2 provider instead.
        val tlsVersionsToTry = when {
            tlsVersion == "1.2" -> listOf("TLSv1.2")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf("TLSv1.3")
            else -> listOf("TLSv1.2")
        }

        var lastError: Exception? = null
        for (protocol in tlsVersionsToTry) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                certFile.inputStream().use { keyStore.load(it, certificatePassword) }
                val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, certificatePassword)
                }.keyManagers
                val sslContext = SSLContext.getInstance(protocol)
                sslContext.init(keyManagers, null, null)
                onLog("Shared TLS certificate loaded (${certFile.name}, $protocol)")
                return TlsServerConfig(protocol, sslContext.serverSocketFactory)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "TLS $protocol setup failed", e)
            }
        }
        throw lastError ?: IOException("Failed to create an SSL context")
    }

    private fun generateCertificatePassword(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return buildString(32) {
            repeat(32) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom-scheme association:
 *
 *   androidipcamera://associate?server=https%3A%2F%2Frelay.example&code=ABCD-EFGH
 *
 * The code is single-use and short-lived. The relay returns a device token once;
 * only its SHA-256 hash stays on the server, and the token itself is kept in
 * [SecureStorage] on the phone.
 */
object RelayAssociation {
    private const val TAG = "RelayAssociation"

    data class PairingIntent(val serverUrl: String, val code: String)

    data class Credentials(
        val serverUrl: String,
        val deviceId: String,
        val deviceName: String,
        val token: String,
    )

    fun parseIntent(uri: Uri?): PairingIntent? {
        if (uri == null) return null
        if (!uri.scheme.equals("androidipcamera", ignoreCase = true)) return null
        if (!uri.host.equals("associate", ignoreCase = true)) return null
        val server = uri.getQueryParameter("server")?.trim().orEmpty()
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (server.isEmpty()) return null
        return PairingIntent(normalizeServer(server), code)
    }

    fun normalizeServer(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "https://$value"
        }
        return value
    }

    fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Android IP Camera" }
    }

    suspend fun associate(context: Context, serverUrl: String, code: String): Credentials =
        withContext(Dispatchers.IO) {
            val normalized = normalizeServer(serverUrl)
            val endpoint = URL("$normalized/api/associate")
            val payload = JSONObject()
                .put("code", code.trim())
                .put("name", defaultDeviceName())
                .toString()

            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Android-IP-Camera")
            }

            try {
                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw IOException(message?.takeIf { it.isNotBlank() } ?: "Association failed (HTTP $status)")
                }

                val json = JSONObject(text)
                val deviceId = json.optString("deviceId")
                val token = json.optString("token")
                if (deviceId.isBlank() || token.isBlank()) {
                    throw IOException("The relay returned an incomplete association response")
                }
                val credentials = Credentials(
                    serverUrl = normalizeServer(json.optString("serverUrl", normalized)),
                    deviceId = deviceId,
                    deviceName = json.optString("name", defaultDeviceName()).ifBlank { defaultDeviceName() },
                    token = token,
                )
                save(context, credentials)
                Log.i(TAG, "Associated with $normalized as ${credentials.deviceId}")
                credentials
            } finally {
                connection.disconnect()
            }
        }

    fun save(context: Context, credentials: Credentials) {
        val storage = SecureStorage(context)
        storage.putSecureString(SecureStorage.KEY_RELAY_SERVER, credentials.serverUrl)
        storage.putSecureString(SecureStorage.KEY_RELAY_DEVICE_ID, credentials.deviceId)
        storage.putSecureString(SecureStorage.KEY_RELAY_DEVICE_NAME, credentials.deviceName)
        storage.putSecureString(SecureStorage.KEY_RELAY_TOKEN, credentials.token)
    }

    fun load(context: Context): Credentials? {
        val storage = SecureStorage(context)
        val serverUrl = storage.getSecureString(SecureStorage.KEY_RELAY_SERVER)
        val deviceId = storage.getSecureString(SecureStorage.KEY_RELAY_DEVICE_ID)
        val token = storage.getSecureString(SecureStorage.KEY_RELAY_TOKEN)
        if (serverUrl.isNullOrBlank() || deviceId.isNullOrBlank() || token.isNullOrBlank()) return null
        return Credentials(
            serverUrl = normalizeServer(serverUrl),
            deviceId = deviceId,
            deviceName = storage.getSecureString(SecureStorage.KEY_RELAY_DEVICE_NAME) ?: defaultDeviceName(),
            token = token,
        )
    }

    fun clear(context: Context) {
        val storage = SecureStorage(context)
        storage.removeSecureString(SecureStorage.KEY_RELAY_SERVER)
        storage.removeSecureString(SecureStorage.KEY_RELAY_DEVICE_ID)
        storage.removeSecureString(SecureStorage.KEY_RELAY_DEVICE_NAME)
        storage.removeSecureString(SecureStorage.KEY_RELAY_TOKEN)
    }
}

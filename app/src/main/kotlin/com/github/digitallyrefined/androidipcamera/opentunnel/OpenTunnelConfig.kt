package com.github.digitallyrefined.androidipcamera.opentunnel

import android.content.Context
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import java.net.URI

/** Preferences and security rules shared by the service, main screen, and Settings. */
object OpenTunnelConfig {
    const val PREF_ENABLED = "opentunnel_enabled"
    const val PREF_API_URL = "opentunnel_api_url"
    const val DEFAULT_API_URL = "https://opentunnel.xyz"
    const val PROFILE = "default"

    /** Set the first time the user taps the OpenTunnel hint on the main screen. */
    const val PREF_TAPPED = "opentunnel_setup_tapped"

    /**
     * Whether OpenTunnel may connect at all. Disabling it disconnects the tunnel but keeps the
     * registered identity and all other settings, so enabling it again needs no new registration.
     */
    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_ENABLED, true)

    fun apiUrl(context: Context): String {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_API_URL, DEFAULT_API_URL)
            ?.trim()
            .orEmpty()
        val candidate = raw.ifEmpty { DEFAULT_API_URL }
        return normalizeApiUrl(candidate) ?: DEFAULT_API_URL
    }

    /** Returns a normalized URL, or null when it is not a usable http(s) base URL. */
    fun normalizeApiUrl(raw: String): String? {
        return try {
            val uri = URI(raw.trim())
            if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
            if (uri.host.isNullOrBlank()) return null
            if (!uri.path.isNullOrBlank() && uri.path != "/") return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A tunnel is public, so it may only run while the local server is
     * protected by authentication with both credentials configured.
     */
    fun hasRequiredCredentials(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("enable_auth", true)) return false
        val secure = SecureStorage(context)
        val username = secure.getSecureString(SecureStorage.KEY_USERNAME, "")?.trim().orEmpty()
        val password = secure.getSecureString(SecureStorage.KEY_PASSWORD, "")?.trim().orEmpty()
        return username.isNotEmpty() && password.isNotEmpty()
    }

    fun credentialsProblem(context: Context): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("enable_auth", true)) {
            return "Enable Authentication before using OpenTunnel: its URL is public"
        }
        val secure = SecureStorage(context)
        val username = secure.getSecureString(SecureStorage.KEY_USERNAME, "")?.trim().orEmpty()
        val password = secure.getSecureString(SecureStorage.KEY_PASSWORD, "")?.trim().orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            return "Set a username and password in Settings before using OpenTunnel"
        }
        return null
    }
}

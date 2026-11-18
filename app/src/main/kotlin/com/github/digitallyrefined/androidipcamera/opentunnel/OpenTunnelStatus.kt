package com.github.digitallyrefined.androidipcamera.opentunnel

import org.json.JSONObject

/** Poll-friendly snapshot of the native OpenTunnel client. */
data class OpenTunnelStatus(
    val registered: Boolean = false,
    val hasFiles: Boolean = false,
    val state: String = STATE_IDLE,
    val stage: String? = null,
    val url: String? = null,
    val hostname: String? = null,
    val profile: String = OpenTunnelConfig.PROFILE,
    val error: String? = null,
    val session: String? = null,
    val connections: Int = 0
) {
    val connected: Boolean get() = state == STATE_CONNECTED
    val busy: Boolean
        get() = state == STATE_PROVISIONING ||
            state == STATE_CONNECTING ||
            state == STATE_RECONNECTING ||
            state == STATE_WAITING_ROUTES ||
            state == STATE_FORGETTING

    /**
     * One line suitable for the main screen and the Settings summary. Deliberately
     * short: the caller only has a single line to work with.
     */
    fun progressText(): String {
        if (!registered && state == STATE_IDLE) {
            return "Tap to setup optional remote access"
        }
        return when (state) {
            STATE_PROVISIONING -> when (stage) {
                "starting", "creating_tunnel" -> "OpenTunnel: creating tunnel…"
                "generating_certificate" -> "OpenTunnel: generating certificate key…"
                "resuming_certificate" -> "OpenTunnel: resuming certificate…"
                "requesting_certificate" -> "OpenTunnel: requesting certificate…"
                "waiting_certificate" -> "OpenTunnel: waiting for certificate…"
                "saving_identity" -> "OpenTunnel: saving identity…"
                "checking" -> "OpenTunnel: checking registration…"
                "connecting" -> "OpenTunnel: connecting…"
                else -> "OpenTunnel: setting up…"
            }
            STATE_CONNECTING -> "OpenTunnel: connecting…"
            STATE_RECONNECTING -> "OpenTunnel: reconnecting…"
            STATE_CONNECTED -> "OpenTunnel: connected"
            STATE_WAITING_ROUTES -> "OpenTunnel: waiting for a route…"
            STATE_FORGETTING -> "OpenTunnel: disconnecting…"
            STATE_STOPPED -> error?.let { "OpenTunnel: $it" } ?: "OpenTunnel: stopped"
            else -> if (registered) "OpenTunnel: not connected" else "Tap to setup optional remote access"
        }
    }

    companion object {
        const val STATE_IDLE = "idle"
        const val STATE_PROVISIONING = "provisioning"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_RECONNECTING = "reconnecting"
        const val STATE_WAITING_ROUTES = "waiting_routes"
        const val STATE_STOPPED = "stopped"
        const val STATE_FORGETTING = "forgetting"

        fun parse(json: String?): OpenTunnelStatus {
            if (json.isNullOrBlank()) return OpenTunnelStatus()
            return try {
                val objectValue = JSONObject(json)
                OpenTunnelStatus(
                    registered = objectValue.optBoolean("registered", false),
                    hasFiles = objectValue.optBoolean("hasFiles", false),
                    state = objectValue.optString("state", STATE_IDLE),
                    stage = objectValue.optString("stage").takeIf { it.isNotBlank() && it != "null" },
                    url = objectValue.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    hostname = objectValue.optString("hostname").takeIf { it.isNotBlank() && it != "null" },
                    profile = objectValue.optString("profile", OpenTunnelConfig.PROFILE),
                    error = objectValue.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    session = objectValue.optString("session").takeIf { it.isNotBlank() && it != "null" },
                    connections = objectValue.optInt("connections", 0)
                )
            } catch (_: Exception) {
                OpenTunnelStatus()
            }
        }
    }
}

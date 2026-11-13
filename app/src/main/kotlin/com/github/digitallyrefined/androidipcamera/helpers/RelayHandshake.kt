package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Small, Android-independent helpers for the relay's HTTP upgrade response.
 * Keeping this separate makes the terminal-vs-retryable decision testable on
 * the JVM without opening a socket.
 */
internal object RelayHandshake {
    private const val NOT_REGISTERED = "device not registered"
    private const val UNKNOWN_DEVICE = "unknown device"
    private const val MAX_DETAIL_LENGTH = 200
    private val nonAlphanumeric = Regex("[^a-z0-9]+")
    private val whitespace = Regex("\\s+")

    private fun normalize(body: String?): String =
        body
            ?.lowercase()
            ?.replace(nonAlphanumeric, " ")
            ?.trim()
            .orEmpty()

    /**
     * A camera that is no longer registered with the relay is rejected with
     * HTTP 401 or 403. Status codes alone are not enough: a WAF such as
     * Cloudflare or CrowdSec can also return 401/403. Only treat the response
     * as terminal when its body explicitly says the device is not registered;
     * everything else stays retryable.
     */
    fun isTerminalRejection(statusCode: Int, body: String?): Boolean {
        if (statusCode != 401 && statusCode != 403) return false
        return normalize(body).contains(NOT_REGISTERED)
    }

    /**
     * True when a delete request failed because the relay already forgot this
     * camera. That is a successful outcome for the app: there is nothing left
     * to delete remotely. A generic 404 (for example a missing relay URL) is
     * deliberately not treated as success, so the caller can retry it.
     */
    fun isAlreadyUnregistered(statusCode: Int, body: String?): Boolean {
        if (statusCode != 401 && statusCode != 403 && statusCode != 404) return false
        val normalized = normalize(body)
        return normalized.contains(NOT_REGISTERED) || normalized.contains(UNKNOWN_DEVICE)
    }

    /** Extract the numeric status code from an HTTP response line. */
    fun parseStatusCode(statusLine: String): Int =
        statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0

    /** Build a user-facing message, including the relay's error body if present. */
    fun rejectionMessage(statusCode: Int, body: String?): String {
        val detail = summarize(body)
        return if (detail != null) {
            "Relay rejected this camera (HTTP $statusCode: $detail)"
        } else {
            "Relay rejected this camera (HTTP $statusCode)"
        }
    }

    /** Collapse an error body to a short single-line detail for logs/status. */
    fun summarize(body: String?): String? {
        val text = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val singleLine = text.replace(whitespace, " ")
        return if (singleLine.length <= MAX_DETAIL_LENGTH) {
            singleLine
        } else {
            singleLine.take(MAX_DETAIL_LENGTH) + "…"
        }
    }
}

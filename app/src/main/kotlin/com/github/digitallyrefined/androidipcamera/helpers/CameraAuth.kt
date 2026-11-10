package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import androidx.preference.PreferenceManager
import java.security.MessageDigest

/**
 * Username/password policy shared by the camera's HTTP, RTSP, and relay
 * transports. Authentication is enabled by default. When it is disabled the
 * camera accepts connections without credentials.
 */
object CameraAuth {
    enum class Mode { DISABLED, NOT_CONFIGURED, REQUIRED }

    data class Credentials(val username: String, val password: String)

    data class Result(
        val ok: Boolean,
        val status: Int,
        val reason: String,
        val error: String,
    )

    fun mode(context: Context): Pair<Mode, Credentials?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("enable_auth", true)) return Mode.DISABLED to null

        val storage = SecureStorage(context)
        val username = InputValidator.validateAndSanitizeUsername(
            storage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: "",
        )
        val password = InputValidator.validateAndSanitizePassword(
            storage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: "",
        )
        return if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            Mode.NOT_CONFIGURED to null
        } else {
            Mode.REQUIRED to Credentials(username, password)
        }
    }

    fun verify(
        context: Context,
        providedUsername: String,
        providedPassword: String,
    ): Result {
        val (mode, credentials) = mode(context)
        return verify(mode, credentials, providedUsername, providedPassword)
    }

    internal fun verify(
        mode: Mode,
        credentials: Credentials?,
        providedUsername: String,
        providedPassword: String,
    ): Result {
        return when (mode) {
            Mode.DISABLED -> Result(true, 200, "auth-disabled", "")
            Mode.NOT_CONFIGURED -> Result(
                false,
                403,
                "not-configured",
                "Authentication is enabled in the app, but no valid username and password are configured. Set them in the app settings.",
            )
            Mode.REQUIRED -> {
                val expected = credentials
                if (expected == null) {
                    Result(
                        false,
                        403,
                        "not-configured",
                        "Authentication is enabled in the app, but no valid username and password are configured. Set them in the app settings.",
                    )
                } else if (providedUsername.isEmpty() || providedPassword.isEmpty()) {
                    Result(
                        false,
                        401,
                        "missing-credentials",
                        "Enter the camera username and password. Authentication is enabled in the app.",
                    )
                } else if (
                    constantTimeEquals(providedUsername, expected.username) &&
                    constantTimeEquals(providedPassword, expected.password)
                ) {
                    Result(true, 200, "ok", "")
                } else {
                    Result(
                        false,
                        401,
                        "invalid-credentials",
                        "The username or password is incorrect. Check the credentials in the app settings.",
                    )
                }
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
}

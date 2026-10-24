package com.github.digitallyrefined.androidipcamera.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.net.InetAddress

/** Connects [RtspServer] to the app: settings, stored credentials, microphone and camera lifecycle. */
internal class AndroidRtspHost(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val clientConnected: () -> Unit,
    private val clientDisconnected: () -> Unit,
    private val keyFrameRequest: (RtspStream) -> Unit,
    private val streamingEnabled: () -> Boolean
) : RtspServer.Host {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    override fun port(): Int =
        prefs.getString(RtspServer.PREF_PORT, null)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: RtspServer.DEFAULT_PORT

    override fun credentials(): RtspCredentials {
        if (!prefs.getBoolean("enable_auth", true)) return RtspCredentials.Disabled
        val storage = SecureStorage(context)
        val username = InputValidator.validateAndSanitizeUsername(storage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: "")
        val password = InputValidator.validateAndSanitizePassword(storage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: "")
        return if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            RtspCredentials.NotConfigured
        } else {
            RtspCredentials.Required(username, password)
        }
    }

    override fun isStreamingEnabled(): Boolean = streamingEnabled()

    override fun audioFormat(): AacFormat? =
        if (prefs.getBoolean(RtspServer.PREF_AUDIO, true) && hasMicrophonePermission()) AacAudioSource.FORMAT else null

    override fun createAudioSource(onAccessUnit: (ByteArray, Long) -> Unit): RtspAudioSource? =
        if (hasMicrophonePermission()) AacAudioSource(context, onLog, onAccessUnit) else null

    override fun onClientConnected() = clientConnected()

    override fun onClientDisconnected() = clientDisconnected()

    override fun requestKeyFrame(stream: RtspStream) = keyFrameRequest(stream)

    override fun log(message: String) = onLog(message)

    override fun bootClockNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun bindAddress(): InetAddress = ListenInterface.resolve(context)

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

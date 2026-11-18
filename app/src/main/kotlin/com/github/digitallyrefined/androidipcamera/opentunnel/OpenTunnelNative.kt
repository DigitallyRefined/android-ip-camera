package com.github.digitallyrefined.androidipcamera.opentunnel

import android.util.Log

/**
 * Thin JNI surface for the Rust OpenTunnel client.
 *
 * The library is built for arm64-v8a and armeabi-v7a. On other ABIs [isLoaded]
 * is false and callers must treat OpenTunnel as unavailable instead of crashing.
 */
object OpenTunnelNative {
    private const val TAG = "OpenTunnelNative"
    private const val LIBRARY = "opentunnel_native"

    val isLoaded: Boolean
    val loadError: String?

    init {
        var error: String? = null
        val loaded = try {
            System.loadLibrary(LIBRARY)
            true
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "OpenTunnel native library is unavailable: $error")
            false
        }
        isLoaded = loaded
        loadError = error
    }

    /**
     * Creates or resumes the profile's tunnel identity and connects it.
     * Returns false when the arguments are invalid; asynchronous failures are
     * reported through [nativeStatus].
     */
    external fun nativeStart(
        filesDir: String,
        apiUrl: String,
        target: String,
        profile: String
    ): Boolean

    /** Current status as a JSON object; safe to poll. */
    external fun nativeStatus(filesDir: String, profile: String): String

    /** Stops the bridge but keeps the identity and certificates on disk. */
    external fun nativeDisconnect()

    /** Stops the bridge, deletes the tunnel on the server, then removes all local files. */
    external fun nativeForget(filesDir: String, apiUrl: String, profile: String)
}

package com.github.digitallyrefined.androidipcamera.opentunnel

import android.content.Context
import android.util.Log

/** Safe, non-throwing facade over [OpenTunnelNative] for UI and service code. */
object OpenTunnelController {
    private const val TAG = "OpenTunnel"

    val isAvailable: Boolean get() = OpenTunnelNative.isLoaded

    val unavailableReason: String?
        get() = OpenTunnelNative.loadError
            ?: if (isAvailable) null else "OpenTunnel is unavailable for this device's CPU architecture"

    fun status(context: Context): OpenTunnelStatus {
        if (!isAvailable) {
            return OpenTunnelStatus(error = unavailableReason)
        }
        return try {
            OpenTunnelStatus.parse(
                OpenTunnelNative.nativeStatus(context.filesDir.absolutePath, OpenTunnelConfig.PROFILE)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "status failed", t)
            OpenTunnelStatus(error = t.message ?: "OpenTunnel status unavailable")
        }
    }

    /**
     * Creates the identity if needed, then connects. The bridge keeps running in
     * the app process until [disconnect] or [forget].
     */
    fun start(context: Context, target: String): Boolean {
        if (!isAvailable) {
            Log.w(TAG, unavailableReason ?: "native library unavailable")
            return false
        }
        return try {
            OpenTunnelNative.nativeStart(
                context.filesDir.absolutePath,
                OpenTunnelConfig.apiUrl(context),
                target,
                OpenTunnelConfig.PROFILE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            false
        }
    }

    fun disconnect() {
        if (!isAvailable) return
        try {
            OpenTunnelNative.nativeDisconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect failed", t)
        }
    }

    fun forget(context: Context) {
        if (!isAvailable) return
        try {
            OpenTunnelNative.nativeForget(
                context.filesDir.absolutePath,
                OpenTunnelConfig.apiUrl(context),
                OpenTunnelConfig.PROFILE
            )
        } catch (t: Throwable) {
            Log.w(TAG, "forget failed", t)
        }
    }
}

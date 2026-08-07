package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Starts the streaming service after the device boots, when the "start_on_boot" preference is
 * enabled. Useful for unattended installations where the device is expected to come back online by
 * itself after a power cut or a reboot.
 *
 * StreamingService owns the camera and the HTTP server, so no activity needs to be shown.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("start_on_boot", false)) return

        // The service is a camera foreground service; without the permission it cannot start.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start_on_boot is enabled but the camera permission is not granted")
            return
        }

        // No activity is started on boot, and the activity is what normally calls
        // startStreamingServer() once it binds — so ask the service to start the server itself.
        val serviceIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_SERVER
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming service on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

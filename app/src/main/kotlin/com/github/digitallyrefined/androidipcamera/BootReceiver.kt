package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Offers to start the streaming service after the device boots, when the "start_on_boot"
 * preference is enabled. Useful for unattended installations where the device is expected to
 * come back online by itself after a power cut or a reboot.
 *
 * Android 14+ does not allow a camera/microphone foreground service to be started from the
 * background (BOOT_COMPLETED is not an eligible state for those while-in-use permissions), so on
 * those versions this receiver posts a notification that opens the app instead. Opening the app
 * brings it to the foreground, where the service is allowed to start the camera and microphone.
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

        // Android 14 (API 34) rejects startForeground() with the camera/microphone types when the
        // app is in the background. Starting the service here would crash the process in a loop,
        // so ask the user to open the app instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postStartNotification(context)
            return
        }

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
            // Some OEM builds reject a background foreground-service start even on older
            // versions. Fall back to the notification so the app never ends up in a crash loop.
            Log.e(TAG, "Failed to start streaming service on boot", e)
            postStartNotification(context)
        }
    }

    /** Notification shown after boot on Android 14+, where streaming must be started in the app. */
    private fun postStartNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "start_on_boot is enabled but notifications are not granted")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Start on boot",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shown after a restart so streaming can be started"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Android IP Camera")
            .setContentText("Tap to start streaming")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android requires the app to be opened before the camera and microphone can start. Tap to start streaming."
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Could not post the start-on-boot notification", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val CHANNEL_ID = "start_on_boot_channel"
        private const val NOTIFICATION_ID = 2

        /** Removes the "tap to start" prompt once the app is running. */
        fun cancelStartNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}

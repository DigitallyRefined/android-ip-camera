package com.github.digitallyrefined.androidipcamera.helpers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Device health values shown in `/info.json`, the web status badge and the burned-in video OSD.
 * Kept in one place so every consumer reads exactly the same battery, Wi-Fi and temperature data.
 */
object DeviceStatus {

    /** Battery percentage, or -1 when the platform does not report it. */
    fun batteryPercent(context: Context): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (_: Exception) {
        -1
    }

    /**
     * Battery temperature in degrees Celsius. Android exposes this in every sticky
     * ACTION_BATTERY_CHANGED broadcast as tenths of a degree and it needs no permission, so it is
     * the most reliable "phone temperature" available to a background streaming service.
     */
    fun temperatureC(context: Context): Double? = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    } catch (_: Exception) {
        null
    }

    /** Wi-Fi signal strength as 0–100, or -1 when unavailable or the location permission is missing. */
    fun wifiStrength(context: Context): Int = try {
        val hasWifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasWifiPermission) {
            -1
        } else {
            val rssi = currentWifiInfo(context)?.rssi ?: -127
            when {
                rssi == -127 -> -1
                rssi <= -100 -> 0
                rssi >= -50 -> 100
                else -> (rssi + 100) * 2
            }
        }
    } catch (_: Exception) {
        -1
    }

    /** Current Wi-Fi connection info; Android 10+ exposes it through the active network's capabilities. */
    private fun currentWifiInfo(context: Context): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            return cm?.getNetworkCapabilities(cm.activeNetwork)?.transportInfo as? WifiInfo
        }
        return legacyWifiInfo(context)
    }

    @Suppress("DEPRECATION") // WifiManager.connectionInfo is the only option below API 29.
    private fun legacyWifiInfo(context: Context): WifiInfo? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager)?.connectionInfo
}

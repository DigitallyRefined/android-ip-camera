package com.github.digitallyrefined.androidipcamera.helpers

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context

/**
 * Tracks device RAM class and system memory-pressure callbacks so encoders can
 * shrink buffers, drop frames, and avoid Bitmap work before the process is killed.
 */
object DeviceMemoryHelper {
    @Volatile
    var memoryPressureLevel: Int = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    fun updateMemoryPressure(level: Int) {
        memoryPressureLevel = maxOf(memoryPressureLevel, level)
    }

    fun resetMemoryPressure(level: Int) {
        memoryPressureLevel = level
    }

    fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isLowRamDevice || am.memoryClass <= 128
    }

    fun isUnderMemoryPressure(): Boolean =
        memoryPressureLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE

    fun isSevereMemoryPressure(): Boolean =
        memoryPressureLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    fun h264WriterQueueCapacity(context: Context): Int =
        when {
            isSevereMemoryPressure() -> 4
            isLowRamDevice(context) || isUnderMemoryPressure() -> 8
            else -> 30
        }

    fun mjpegWriterQueueCapacity(context: Context): Int =
        if (isLowRamDevice(context) || isUnderMemoryPressure()) 2 else 3

    /** Full-res snapshot JPEGs above this size are not cached in RAM. */
    fun maxSnapshotBytes(context: Context): Int =
        if (isLowRamDevice(context) || isUnderMemoryPressure()) 512 * 1024 else 2 * 1024 * 1024

    fun mjpegJpegQuality(context: Context): Int =
        when {
            isSevereMemoryPressure() -> 60
            isLowRamDevice(context) || isUnderMemoryPressure() -> 70
            else -> 80
        }
}

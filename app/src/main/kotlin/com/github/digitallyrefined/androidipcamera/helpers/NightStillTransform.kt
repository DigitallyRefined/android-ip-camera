package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Normalizes CameraX night-still JPEGs back to the sensor-space orientation used by the normal
 * video stream. CameraX can return a still whose pixels are already rotated for the requested
 * target rotation while the rest of this app intentionally ignores sensor orientation and applies
 * only the user's rotate= control. Applying the inverse of [ImageInfo.rotationDegrees] before the
 * usual rotate/mirror/crop transforms keeps night stills landscape on sensors mounted at 90°.
 */
object NightStillTransform {
    fun correctionDegrees(rotationDegrees: Int): Int =
        ((360 - (rotationDegrees % 360)) % 360 + 360) % 360

    /** Rotate [bitmap] back from CameraX target orientation to sensor space, recycling it if changed. */
    fun toSensorSpace(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val correction = correctionDegrees(rotationDegrees)
        if (correction == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(correction.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }
}

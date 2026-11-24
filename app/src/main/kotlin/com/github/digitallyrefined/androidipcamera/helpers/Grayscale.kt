package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ColorMatrix

/**
 * Luminance-only post-processing shared by the GL shader and the night still-image path.
 *
 * Camera frames and the app's NV21 conversion use Rec. 601 luma, so the same coefficients are used
 * here. Removing chrominance keeps the captured brightness and detail while hiding the coloured
 * sensor noise that is especially visible in low-light night footage.
 */
object Grayscale {
    const val LUMA_R = 0.299f
    const val LUMA_G = 0.587f
    const val LUMA_B = 0.114f

    /** Chroma midpoint for 8-bit YUV; a neutral sample carries no colour. */
    const val NEUTRAL_CHROMA = 128

    /**
     * Replaces RGB with Rec. 601 luma and leaves alpha untouched. Reused by the JPEG night-still
     * path, where the decoded bitmap is the only image buffer available.
     */
    fun colorMatrix(): ColorMatrix = ColorMatrix(
        floatArrayOf(
            LUMA_R, LUMA_G, LUMA_B, 0f, 0f,
            LUMA_R, LUMA_G, LUMA_B, 0f, 0f,
            LUMA_R, LUMA_G, LUMA_B, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /**
     * Sets the chroma half of a dense YUV420 buffer to neutral, preserving all Y (luminance)
     * samples byte-for-byte. Works for NV21, NV12 and I420 because every layout stores the U/V
     * bytes after the Y plane, and 128 is neutral in each. This is the preferred night-still path:
     * it adds no JPEG decode or re-encode and does not depend on chroma row/pixel strides because
     * [convertYUV420toNV21]/[convertBitmapToNV21] and the H.264 input conversion all produce
     * tightly packed buffers.
     */
    fun neutralizeYuv420Chroma(yuv: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        val end = minOf(ySize + ySize / 2, yuv.size)
        if (end <= ySize) return
        java.util.Arrays.fill(yuv, ySize, end, NEUTRAL_CHROMA.toByte())
    }
}

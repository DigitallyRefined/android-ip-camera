package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun convertYUV420toNV21(image: ImageProxy, reuse: ByteArray? = null, reuseRow: ByteArray? = null): ByteArray {
    val crop: Rect = image.cropRect
    val format: Int = image.format
    val width: Int = crop.width()
    val height: Int = crop.height()
    val planes: Array<ImageProxy.PlaneProxy> = image.planes
    val requiredSize = width * height * ImageFormat.getBitsPerPixel(format) / 8
    val data = if (reuse != null && reuse.size == requiredSize) reuse else ByteArray(requiredSize)
    val rowStride = planes[0].rowStride
    val rowData = if (reuseRow != null && reuseRow.size >= rowStride) reuseRow else ByteArray(rowStride)

    var channelOffset = 0
    var outputStride = 1
    for (i in planes.indices) {
        when (i) {
            0 -> {
                channelOffset = 0
                outputStride = 1
            }
            1 -> {
                channelOffset = width * height + 1
                outputStride = 2
            }
            2 -> {
                channelOffset = width * height
                outputStride = 2
            }
        }

        val buffer: ByteBuffer = planes[i].buffer
        val rowStride: Int = planes[i].rowStride
        val pixelStride: Int = planes[i].pixelStride

        val shift: Int = if (i == 0) 0 else 1
        val w: Int = width shr shift
        val h: Int = height shr shift
        buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
        for (row in 0 until h) {
            val length: Int
            if (pixelStride == 1 && outputStride == 1) {
                length = w
                buffer.get(data, channelOffset, length)
                channelOffset += length
            } else {
                length = (w - 1) * pixelStride + 1
                buffer.get(rowData, 0, length)
                for (col in 0 until w) {
                    data[channelOffset] = rowData[col * pixelStride]
                    channelOffset += outputStride
                }
            }
            if (row < h - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }
    return data
}

fun convertNV21toJPEG(nv21: ByteArray, width: Int, height: Int, quality: Int = 80): ByteArray {
    val out = ByteArrayOutputStream()
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}

/**
 * Convert a decoded bitmap to an NV21 buffer (Y plane followed by interleaved V/U chroma).
 * Used by the Night mode stills path, which gets JPEGs rather than camera YUV frames and has to feed
 * them into the H.264 encoder. Night frames arrive slowly, so the per-pixel conversion is cheap
 * relative to the capture itself.
 */
fun convertBitmapToNV21(bitmap: android.graphics.Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    val ySize = width * height
    val out = ByteArray(ySize + ySize / 2)
    var yIndex = 0
    for (row in 0 until height) {
        for (col in 0 until width) {
            val pixel = argb[yIndex]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            out[yIndex] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            // One chroma sample per 2x2 block, from its top-left pixel.
            if (row % 2 == 0 && col % 2 == 0) {
                val chromaIndex = ySize + (row / 2) * width + col
                out[chromaIndex] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                out[chromaIndex + 1] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            }
            yIndex++
        }
    }
    return out
}

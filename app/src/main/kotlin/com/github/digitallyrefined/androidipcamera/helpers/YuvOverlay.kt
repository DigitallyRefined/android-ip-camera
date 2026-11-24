package com.github.digitallyrefined.androidipcamera.helpers

import android.media.Image

/**
 * Blends a rendered [CameraOverlayRenderer.Frame] into YUV420 buffers. The GL surface path draws
 * the overlay as a texture; this class covers the CPU/byte-buffer H.264 path and the night-still
 * path, where frames are fed to MediaCodec as NV12/I420 byte arrays or as a codec input Image.
 */
object YuvOverlay {

    fun blendIntoDenseYuv(
        dst: ByteArray,
        width: Int,
        height: Int,
        semiPlanar: Boolean,
        frame: CameraOverlayRenderer.Frame,
    ) {
        if (width < 2 || height < 2) return
        val ySize = width * height
        val cw = width / 2
        val ch = height / 2
        val uOffset = ySize
        val vOffset = if (semiPlanar) ySize else ySize + cw * ch

        forEachPixel(frame, width, height) { x, y, argb, alpha ->
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val yIndex = y * width + x
            dst[yIndex] = blend(dst[yIndex].toInt() and 0xff, luma(r, g, b), alpha).toByte()
            if ((x and 1) == 0 && (y and 1) == 0) {
                val chroma = if (semiPlanar) {
                    ySize + (y / 2) * width + (x / 2) * 2
                } else {
                    uOffset + (y / 2) * cw + x / 2
                }
                val vIndex = if (semiPlanar) chroma + 1 else vOffset + (y / 2) * cw + x / 2
                dst[chroma] = blend(dst[chroma].toInt() and 0xff, chromaU(r, g, b), alpha).toByte()
                dst[vIndex] = blend(dst[vIndex].toInt() and 0xff, chromaV(r, g, b), alpha).toByte()
            }
        }
    }

    /** NV21: Y plane followed by interleaved V,U chroma. */
    fun blendIntoNv21(nv21: ByteArray, width: Int, height: Int, frame: CameraOverlayRenderer.Frame) {
        if (width < 2 || height < 2) return
        val ySize = width * height
        forEachPixel(frame, width, height) { x, y, argb, alpha ->
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val yIndex = y * width + x
            nv21[yIndex] = blend(nv21[yIndex].toInt() and 0xff, luma(r, g, b), alpha).toByte()
            if ((x and 1) == 0 && (y and 1) == 0) {
                val chroma = ySize + (y / 2) * width + (x / 2) * 2
                nv21[chroma] = blend(nv21[chroma].toInt() and 0xff, chromaV(r, g, b), alpha).toByte()
                nv21[chroma + 1] = blend(nv21[chroma + 1].toInt() and 0xff, chromaU(r, g, b), alpha).toByte()
            }
        }
    }

    /**
     * Blend into a MediaCodec input [Image], honouring each plane's row/pixel stride (hardware
     * encoders frequently pad rows). Chroma is updated once per 2×2 block.
     */
    fun blendIntoImage(
        image: Image,
        frame: CameraOverlayRenderer.Frame,
        width: Int = image.width,
        height: Int = image.height,
    ) {
        if (width < 2 || height < 2) return
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPixel = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPixel = vPlane.pixelStride

        forEachPixel(frame, width, height) { x, y, argb, alpha ->
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val yIndex = y * yRow + x * yPixel
            yBuffer.put(yIndex, blend(yBuffer.get(yIndex).toInt() and 0xff, luma(r, g, b), alpha).toByte())
            if ((x and 1) == 0 && (y and 1) == 0) {
                val uIndex = (y / 2) * uRow + (x / 2) * uPixel
                val vIndex = (y / 2) * vRow + (x / 2) * vPixel
                uBuffer.put(uIndex, blend(uBuffer.get(uIndex).toInt() and 0xff, chromaU(r, g, b), alpha).toByte())
                vBuffer.put(vIndex, blend(vBuffer.get(vIndex).toInt() and 0xff, chromaV(r, g, b), alpha).toByte())
            }
        }
    }

    private inline fun forEachPixel(
        frame: CameraOverlayRenderer.Frame,
        width: Int,
        height: Int,
        action: (x: Int, y: Int, argb: Int, alpha: Int) -> Unit,
    ) {
        val x0 = maxOf(0, frame.x)
        val y0 = maxOf(0, frame.y)
        val x1 = minOf(width, frame.x + frame.bitmap.width)
        val y1 = minOf(height, frame.y + frame.bitmap.height)
        if (x0 >= x1 || y0 >= y1) return
        val bitmapWidth = frame.bitmap.width
        for (y in y0 until y1) {
            val row = (y - frame.y) * bitmapWidth
            for (x in x0 until x1) {
                val argb = frame.pixels[row + (x - frame.x)]
                val alpha = argb ushr 24
                if (alpha != 0) action(x, y, argb, alpha)
            }
        }
    }

    private fun blend(src: Int, overlay: Int, alpha: Int): Int {
        if (alpha >= 255) return overlay
        return (overlay * alpha + src * (255 - alpha) + 127) / 255
    }

    private fun luma(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b + 128) shr 8

    private fun chromaU(r: Int, g: Int, b: Int): Int =
        ((-43 * r - 85 * g + 128 * b + 128) shr 8) + 128

    private fun chromaV(r: Int, g: Int, b: Int): Int =
        ((128 * r - 107 * g - 21 * b + 128) shr 8) + 128
}

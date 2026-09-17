package com.github.digitallyrefined.androidipcamera.helpers

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy

/**
 * Hardware H.264 encoder. Two input modes:
 *  - surface mode (useSurface=true): camera renders directly into [inputSurface] (zero CPU copy ->
 *    real 30fps, supports 1080p). Used for H.264-only viewers.
 *  - byte-buffer mode: fed YUV from CameraX ImageAnalysis frames via [feed] (CPU-bound; the color
 *    layout, NV12 or I420, is chosen from the codec's caps for cross-device compatibility).
 * Emits Annex-B NAL units via [onNal] (bytes, isKeyframe); SPS/PPS prepended to keyframes.
 */
class H264HardwareEncoder(
    val width: Int,
    val height: Int,
    frameRate: Int,
    bitRate: Int,
    val useSurface: Boolean,
    private val onNal: (ByteArray, Boolean) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val codecLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var codecReleased = false
    var inputSurface: Surface? = null
        private set
    @Volatile private var running = true
    private var thread: Thread? = null
    private var semiPlanar = true   // byte-buffer layout: NV12 (interleaved UV) vs I420 (planar U then V)
    private val parameterSets = H264ParameterSetCache()
    private val accessUnitAssembler = H264AccessUnitAssembler()
    private var partialFlags = 0
    private var frameCount = 0

    /** Optional recording callback. Called with (annexBBytes, isKeyframe, presentationTimeUs). */
    @Volatile var onRecordFrame: ((ByteArray, Boolean, Long) -> Unit)? = null

    @Volatile var hasSurfaceBeenProvided = false
    @Volatile private var isStopped = false
    @Volatile private var isReleasedByCameraX = false

    /** Exposes the release state of the underlying MediaCodec and input surface. */
    val isReleased: Boolean
        get() = codecReleased

    init {
        val colorFormat = if (useSurface) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                          else pickYuvFormat()   // sets semiPlanar; NV12 if the HW takes it, else I420
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            try { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) } catch (_: Exception) {}
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (useSurface) inputSurface = codec.createInputSurface()
        codec.start()
        thread = Thread { drainLoop() }.apply { isDaemon = true; start() }
        Log.i(TAG, "H264HardwareEncoder started ${width}x$height @${frameRate}fps ${bitRate / 1000}kbps surface=$useSurface")
    }

    fun requestKeyFrame() {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    /** ponytail: NV12 vs I420 is the classic cross-device MediaCodec gotcha — ask the codec which it takes. */
    private fun pickYuvFormat(): Int {
        val supported = try { codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet() }
                        catch (_: Exception) { emptySet<Int>() }
        return when {
            supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) && !supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar.also { semiPlanar = false }   // I420-only HW
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar.also { semiPlanar = true } // NV12 (the common case)
        }
    }

    private var yuv: ByteArray? = null
    private var rotYuv: ByteArray? = null

    /**
     * Convert one analyzer frame to the encoder's YUV layout and feed it. Drops the frame if no input
     * buffer is free. [rotation] is an angle in degrees (multiple of 90) applied before encoding so the
     * emitted H.264 stream carries the rotation baked in — downstream clients need no extra transcode.
     * When [rotation] is 90/270 the encoder must already have been created with swapped width/height.
     */
    fun feed(image: ImageProxy, ptsUs: Long, mirror: Boolean = false, rotation: Int = 0) {
        if (useSurface) return
        synchronized(codecLock) {
            if (!running) return
            try {
                val idx = codec.dequeueInputBuffer(2000)
                if (idx < 0) return

                if (rotation % 360 == 0) {
                    // Always use getInputImage for proper stride handling; when mirror is needed
                    // we copy source pixels in reversed column order instead of post-mirroring.
                    val dstImage = codec.getInputImage(idx)
                    if (dstImage != null) {
                        if (mirror) mirroredCopyYuv(image, dstImage)
                        else copyYuv(image, dstImage)
                        val size = width * height * 3 / 2
                        codec.queueInputBuffer(idx, 0, size, ptsUs, 0)
                        return
                    }

                    // Fallback for legacy / safety (only reached if getInputImage returns null)
                    val buf = codec.getInputBuffer(idx)
                    if (buf == null) { codec.queueInputBuffer(idx, 0, 0, ptsUs, 0); return }
                    val size = width * height * 3 / 2
                    var arr = yuv
                    if (arr == null || arr.size != size) { arr = ByteArray(size); yuv = arr }
                    toYuv420(image, arr)
                    buf.clear(); buf.put(arr, 0, size)
                    codec.queueInputBuffer(idx, 0, size, ptsUs, 0)
                    return
                }

                // Rotation path: pack the source frame (its own dimensions, which are swapped vs
                // the encoder when rotating 90/270) into a dense YUV array, rotate it, then feed.
                // Prefer getInputImage: the codec's input Image carries its native row/pixel strides,
                // which raw getInputBuffer bytes cannot express — tight-packed buffer input is exactly
                // what some hardware encoders reject (misaligned rows, green/garbled bands).
                val dstImage = codec.getInputImage(idx)
                if (dstImage != null) {
                    val srcSize = image.width * image.height * 3 / 2
                    var srcArr = yuv
                    if (srcArr == null || srcArr.size != srcSize) { srcArr = ByteArray(srcSize); yuv = srcArr }
                    toYuv420(image, srcArr, image.width, image.height)
                    val dstSize = width * height * 3 / 2
                    var dstArr = rotYuv
                    if (dstArr == null || dstArr.size != dstSize) { dstArr = ByteArray(dstSize); rotYuv = dstArr }
                    rotateYuv(srcArr, image.width, image.height, rotation, mirror, semiPlanar, dstArr)
                    writeRotatedDenseToInputImage(dstImage, dstArr, width, height)
                    codec.queueInputBuffer(idx, 0, dstSize, ptsUs, 0)
                    return
                }

                // Fallback for legacy / safety (only reached if getInputImage returns null)
                val srcSize = image.width * image.height * 3 / 2
                var srcArr = yuv
                if (srcArr == null || srcArr.size != srcSize) { srcArr = ByteArray(srcSize); yuv = srcArr }
                toYuv420(image, srcArr, image.width, image.height)
                val dstSize = width * height * 3 / 2
                var dstArr = rotYuv
                if (dstArr == null || dstArr.size != dstSize) { dstArr = ByteArray(dstSize); rotYuv = dstArr }
                rotateYuv(srcArr, image.width, image.height, rotation, mirror, semiPlanar, dstArr)
                val buf = codec.getInputBuffer(idx)
                if (buf == null) { codec.queueInputBuffer(idx, 0, 0, ptsUs, 0); return }
                buf.clear(); buf.put(dstArr, 0, dstSize)
                codec.queueInputBuffer(idx, 0, dstSize, ptsUs, 0)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "feed: ${e.message}")
            }
        }
    }

    /**
     * Rotate a densely packed YUV420 buffer (as produced by [toYuv420] — NV12 interleaved UV or I420
     * planar UV per [semiPlanar]) into [dst] by a 90° multiple. [mirror] mirrors the source frame
     * horizontally before rotation, matching the MJPEG encoder's mirror-then-rotate behaviour.
     */
    private fun rotateYuv(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        rotation: Int,
        mirror: Boolean,
        semiPlanar: Boolean,
        dst: ByteArray
    ) {
        val swap = rotation == 90 || rotation == 270
        val dstW = if (swap) srcH else srcW
        val dstH = if (swap) srcW else srcH

        val cw = srcW / 2; val ch = srcH / 2
        val dcw = dstW / 2; val dch = dstH / 2

        // Y plane
        for (dy in 0 until dstH) {
            val dRowOff = dy * dstW
            for (dx in 0 until dstW) {
                val (sx, sy) = rotatedSrcCoords(dx, dy, srcW, srcH, rotation, mirror)
                dst[dRowOff + dx] = src[sy * srcW + sx]
            }
        }

        if (semiPlanar) {
            // NV12: U,V interleaved, one block of cw x ch pixels (2 bytes each)
            val sOff = srcW * srcH
            val dOff = dstW * dstH
            for (dy in 0 until dch) {
                val dRowOff = dOff + dy * dcw * 2
                for (dx in 0 until dcw) {
                    val (sx, sy) = rotatedSrcCoords(dx, dy, cw, ch, rotation, mirror)
                    val sIdx = sOff + (sy * cw + sx) * 2
                    val dIdx = dRowOff + dx * 2
                    dst[dIdx] = src[sIdx]
                    dst[dIdx + 1] = src[sIdx + 1]
                }
            }
        } else {
            // I420: planar U then V, each cw x ch
            val uOff = srcW * srcH
            val vOff = srcW * srcH + cw * ch
            val duOff = dstW * dstH
            val dvOff = dstW * dstH + dcw * dch
            for (dy in 0 until dch) {
                val dRowOffU = duOff + dy * dcw
                val dRowOffV = dvOff + dy * dcw
                for (dx in 0 until dcw) {
                    val (sx, sy) = rotatedSrcCoords(dx, dy, cw, ch, rotation, mirror)
                    val sIdx = sy * cw + sx
                    dst[dRowOffU + dx] = src[uOff + sIdx]
                    dst[dRowOffV + dx] = src[vOff + sIdx]
                }
            }
        }
    }

    /** Map a destination pixel in a plane of width [sw] x height [sh] back to its source pixel after
     *  rotating [rotation]° (clockwise) and optionally mirroring the source horizontally first. */
    private fun rotatedSrcCoords(
        dx: Int,
        dy: Int,
        sw: Int,
        sh: Int,
        rotation: Int,
        mirror: Boolean
    ): Pair<Int, Int> {
        val sx: Int
        val sy: Int
        when (rotation % 360) {
            90 -> { sx = sw - 1 - dy; sy = dx }
            180 -> { sx = sw - 1 - dx; sy = sh - 1 - dy }
            270 -> { sx = dy; sy = sh - 1 - dx }
            else -> { sx = dx; sy = dy }
        }
        return if (mirror) (sw - 1 - sx) to sy else sx to sy
    }

    private fun mirrorYuvArray(arr: ByteArray, w: Int, h: Int) {
        val rowBuf = ByteArray(w)
        val ySize = w * h
        for (row in 0 until h) {
            val off = row * w
            System.arraycopy(arr, off, rowBuf, 0, w)
            for (col in 0 until w) arr[off + col] = rowBuf[w - 1 - col]
        }
        val cw = w / 2; val ch = h / 2
        val uvBuf = ByteArray(if (semiPlanar) w else cw)
        var off = ySize
        if (semiPlanar) {
            for (row in 0 until ch) {
                System.arraycopy(arr, off, uvBuf, 0, w)
                for (col in 0 until cw) {
                    arr[off + col * 2] = uvBuf[(cw - 1 - col) * 2]
                    arr[off + col * 2 + 1] = uvBuf[(cw - 1 - col) * 2 + 1]
                }
                off += w
            }
        } else {
            for (row in 0 until ch) {
                System.arraycopy(arr, off, uvBuf, 0, cw)
                for (col in 0 until cw) arr[off + col] = uvBuf[cw - 1 - col]
                off += cw
            }
            for (row in 0 until ch) {
                System.arraycopy(arr, off, uvBuf, 0, cw)
                for (col in 0 until cw) arr[off + col] = uvBuf[cw - 1 - col]
                off += cw
            }
        }
    }

    private fun copyYuv(src: ImageProxy, dst: Image) {
        val srcPlanes = src.planes
        val dstPlanes = dst.planes

        // Y plane
        copyPlane(srcPlanes[0], dstPlanes[0], src.width, src.height)

        // U and V planes
        val uvW = src.width / 2
        val uvH = src.height / 2
        copyPlane(srcPlanes[1], dstPlanes[1], uvW, uvH)
        copyPlane(srcPlanes[2], dstPlanes[2], uvW, uvH)
    }

    /**
     * Write a densely packed YUV420 buffer (as produced by [rotateYuv] — NV12 interleaved UV or I420
     * planar U,V per [semiPlanar]) into the codec's input [Image] planes, honouring each plane's
     * rowStride/pixelStride. This is the safe counterpart to [copyPlane]: it lets the hardware encoder
     * consume the rotated frame at its native layout instead of tight-packed buffer bytes.
     */
    private fun writeRotatedDenseToInputImage(image: Image, src: ByteArray, w: Int, h: Int) {
        val dstPlanes = image.planes

        // Y plane (dense, pixelStride 1)
        writeDensePlane(src, 0, w, 1, dstPlanes[0], w, h)

        val cw = w / 2
        val ch = h / 2
        if (semiPlanar) {
            // NV12: interleaved UV block starting at w*h, (cw x ch) pairs of 2 bytes each
            val uvOff = w * h
            writeDensePlane(src, uvOff, cw * 2, 2, dstPlanes[1], cw, ch)      // U at even bytes
            writeDensePlane(src, uvOff + 1, cw * 2, 2, dstPlanes[2], cw, ch)  // V at odd bytes
        } else {
            // I420: planar U then planar V, each cw x ch
            val uOff = w * h
            val vOff = w * h + cw * ch
            writeDensePlane(src, uOff, cw, 1, dstPlanes[1], cw, ch)
            writeDensePlane(src, vOff, cw, 1, dstPlanes[2], cw, ch)
        }
    }

    /**
     * Stride-aware copy of one dense plane (as laid out in a packed YUV420 buffer: row bytes
     * [srcRowBytes] contiguous, pixel stride [srcPix]) into a codec input [Image.Plane] with its own
     * rowStride/pixelStride. Mirrors [copyPlane]'s handling of interleaved chroma (reads the existing
     * dst row first when pixelStride > 1 so sibling bytes are preserved).
     */
    private fun writeDensePlane(
        src: ByteArray,
        srcOffset: Int,
        srcRowBytes: Int,
        srcPix: Int,
        dst: Image.Plane,
        w: Int,
        h: Int
    ) {
        val dBuf = dst.buffer
        val dRow = dst.rowStride
        val dPix = dst.pixelStride

        var dRBuf: ByteArray? = null
        if (srcPix != 1 || dPix != 1) {
            dRBuf = planeDstRowBuf
            if (dRBuf == null || dRBuf.size < dRow) { dRBuf = ByteArray(dRow).also { planeDstRowBuf = it } }
        }

        for (row in 0 until h) {
            val srcRowOff = srcOffset + row * srcRowBytes
            dBuf.position(row * dRow)
            if (srcPix == 1 && dPix == 1) {
                val bytesToWrite = minOf(w, dBuf.remaining())
                dBuf.put(src, srcRowOff, bytesToWrite)
            } else if (dRBuf != null) {
                val limit = minOf(w * dPix, dRow)
                val bytesToCopy = minOf(limit, dBuf.remaining())

                if (dPix > 1) {
                    dBuf.get(dRBuf, 0, bytesToCopy)
                    dBuf.position(row * dRow)
                }

                for (col in 0 until w) {
                    val dstIdx = col * dPix
                    val srcIdx = col * srcPix
                    if (srcIdx < srcRowBytes && dstIdx < limit) {
                        dRBuf[dstIdx] = src[srcRowOff + srcIdx]
                    }
                }
                dBuf.put(dRBuf, 0, bytesToCopy)
            }
        }
    }

    /** Like [copyYuv] but copies source pixels in reversed column order to achieve a horizontal
     *  mirror. Uses the [getInputImage] path so codec stride/padding requirements are met. */
    private fun mirroredCopyYuv(src: ImageProxy, dst: Image) {
        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        mirroredCopyPlane(srcPlanes[0], dstPlanes[0], src.width, src.height)
        val uvW = src.width / 2
        val uvH = src.height / 2
        mirroredCopyPlane(srcPlanes[1], dstPlanes[1], uvW, uvH)
        mirroredCopyPlane(srcPlanes[2], dstPlanes[2], uvW, uvH)
    }

    private var planeRowBuf: ByteArray? = null
    private var planeDstRowBuf: ByteArray? = null

    private fun copyPlane(
        src: ImageProxy.PlaneProxy,
        dst: Image.Plane,
        w: Int,
        h: Int
    ) {
        val sBuf = src.buffer
        val dBuf = dst.buffer
        val sRow = src.rowStride
        val dRow = dst.rowStride
        val sPix = src.pixelStride
        val dPix = dst.pixelStride

        var rBuf = planeRowBuf
        if (rBuf == null || rBuf.size < sRow) { rBuf = ByteArray(sRow).also { planeRowBuf = it } }

        var dRBuf: ByteArray? = null
        if (sPix != 1 || dPix != 1) {
            dRBuf = planeDstRowBuf
            if (dRBuf == null || dRBuf.size < dRow) { dRBuf = ByteArray(dRow).also { planeDstRowBuf = it } }
        }

        for (row in 0 until h) {
            sBuf.position(row * sRow)
            val bytesToRead = minOf(sRow, sBuf.remaining())
            sBuf.get(rBuf, 0, bytesToRead)

            dBuf.position(row * dRow)
            if (sPix == 1 && dPix == 1) {
                val bytesToWrite = minOf(w, dBuf.remaining())
                dBuf.put(rBuf, 0, bytesToWrite)
            } else if (dRBuf != null) {
                val limit = minOf(w * dPix, dRow)
                val bytesToCopy = minOf(limit, dBuf.remaining())

                if (dPix > 1) {
                    dBuf.get(dRBuf, 0, bytesToCopy)
                    dBuf.position(row * dRow)
                }

                for (col in 0 until w) {
                    val dstIdx = col * dPix
                    val srcIdx = col * sPix
                    if (srcIdx < bytesToRead && dstIdx < limit) {
                        dRBuf[dstIdx] = rBuf[srcIdx]
                    }
                }
                dBuf.put(dRBuf, 0, bytesToCopy)
            }
        }
    }

    /** Like [copyPlane] but copies source pixels in reversed column order to mirror horizontally. */
    private fun mirroredCopyPlane(
        src: ImageProxy.PlaneProxy,
        dst: Image.Plane,
        w: Int,
        h: Int
    ) {
        val sBuf = src.buffer
        val dBuf = dst.buffer
        val sRow = src.rowStride
        val dRow = dst.rowStride
        val sPix = src.pixelStride
        val dPix = dst.pixelStride

        var rBuf = planeRowBuf
        if (rBuf == null || rBuf.size < sRow) { rBuf = ByteArray(sRow).also { planeRowBuf = it } }

        var dRBuf: ByteArray? = null
        if (sPix != 1 || dPix != 1) {
            dRBuf = planeDstRowBuf
            if (dRBuf == null || dRBuf.size < dRow) { dRBuf = ByteArray(dRow).also { planeDstRowBuf = it } }
        }

        for (row in 0 until h) {
            sBuf.position(row * sRow)
            val bytesToRead = minOf(sRow, sBuf.remaining())
            sBuf.get(rBuf, 0, bytesToRead)

            dBuf.position(row * dRow)
            if (sPix == 1 && dPix == 1) {
                val bytesToWrite = minOf(w, dBuf.remaining())
                for (i in 0 until bytesToWrite) {
                    dBuf.put(rBuf[bytesToWrite - 1 - i])
                }
            } else if (dRBuf != null) {
                val limit = minOf(w * dPix, dRow)
                val bytesToCopy = minOf(limit, dBuf.remaining())

                if (dPix > 1) {
                    dBuf.get(dRBuf, 0, bytesToCopy)
                    dBuf.position(row * dRow)
                }

                for (col in 0 until w) {
                    val dstIdx = col * dPix
                    val srcIdx = (w - 1 - col) * sPix
                    if (srcIdx < bytesToRead && dstIdx < limit) {
                        dRBuf[dstIdx] = rBuf[srcIdx]
                    }
                }
                dBuf.put(dRBuf, 0, bytesToCopy)
            }
        }
    }

    private var yRowBuf: ByteArray? = null
    private var uRowBuf: ByteArray? = null
    private var vRowBuf: ByteArray? = null

    /**
     * YUV_420_888 (any plane stride) -> Y plane, then NV12 interleaved UV or I420 planar U,V per [semiPlanar].
     * Reads each source row in ONE bulk ByteBuffer.get into a reused array, then strides in the array —
     * per-pixel ByteBuffer.get() is the throughput killer (bounds-checked native read per byte).
     */
    private fun toYuv420(image: ImageProxy, out: ByteArray, w: Int = width, h: Int = height) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        var o = 0
        if (yPix == 1 && yRow == w) {
            yB.position(0); yB.get(out, 0, w * h); o = w * h       // contiguous: one copy
        } else {
            var rb = yRowBuf; if (rb == null || rb.size < yRow) { rb = ByteArray(yRow); yRowBuf = rb }
            for (r in 0 until h) {
                yB.position(r * yRow); yB.get(rb, 0, minOf(yRow, yB.remaining()))
                if (yPix == 1) System.arraycopy(rb, 0, out, o, w)
                else for (c in 0 until w) out[o + c] = rb[c * yPix]
                o += w
            }
        }
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val cw = w / 2; val ch = h / 2
        var ub = uRowBuf; if (ub == null || ub.size < uRow) { ub = ByteArray(uRow); uRowBuf = ub }
        var vb = vRowBuf; if (vb == null || vb.size < vRow) { vb = ByteArray(vRow); vRowBuf = vb }
        if (semiPlanar) {                                  // NV12: U,V interleaved
            o = w * h
            for (r in 0 until ch) {
                uB.position(r * uRow); uB.get(ub, 0, minOf(uRow, uB.remaining()))
                vB.position(r * vRow); vB.get(vb, 0, minOf(vRow, vB.remaining()))
                for (c in 0 until cw) { out[o++] = ub[c * uPix]; out[o++] = vb[c * vPix] }
            }
        } else {                                           // I420: full U plane, then full V plane
            var uo = w * h; var vo = w * h + cw * ch
            for (r in 0 until ch) {
                uB.position(r * uRow); uB.get(ub, 0, minOf(uRow, uB.remaining()))
                vB.position(r * vRow); vB.get(vb, 0, minOf(vRow, vB.remaining()))
                for (c in 0 until cw) out[uo++] = ub[c * uPix]
                for (c in 0 until cw) out[vo++] = vb[c * vPix]
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var partialPtsUs = 0L
        try {
            while (running) {
                val idx = synchronized(codecLock) { codec.dequeueOutputBuffer(info, 10000) }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = synchronized(codecLock) { codec.outputFormat }
                    cacheCodecSpecificData(format)
                } else if (idx >= 0) {
                    var prepared: H264ParameterSetCache.AccessUnit? = null
                    try {
                        val buf = synchronized(codecLock) { codec.getOutputBuffer(idx) }
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buf.get(data)

                            if (partialFlags == 0) partialPtsUs = info.presentationTimeUs
                            partialFlags = partialFlags or info.flags
                            val isPartial = (info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0
                            val accessUnit = accessUnitAssembler.append(data, isPartial)
                            if (accessUnit != null) {
                                val flags = partialFlags
                                partialFlags = 0
                                prepared = parameterSets.prepare(
                                    accessUnit,
                                    (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                )
                            }
                        }
                    } finally {
                        synchronized(codecLock) { codec.releaseOutputBuffer(idx, false) }
                    }
                    // Never retain a MediaCodec output buffer while network work is scheduled.
                    prepared?.let {
                        onRecordFrame?.invoke(it.bytes, it.isKeyframe, partialPtsUs)
                        onNal(it.bytes, it.isKeyframe)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "drain: ${e.message}")
        }
    }

    private fun cacheCodecSpecificData(format: MediaFormat) {
        for (key in arrayOf("csd-0", "csd-1")) {
            try {
                val source = format.getByteBuffer(key)?.duplicate() ?: continue
                val data = ByteArray(source.remaining())
                source.get(data)
                parameterSets.prepare(data, false)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read $key: ${e.message}")
            }
        }
        if (!parameterSets.hasCompleteParameters()) {
            Log.w(TAG, "Output format did not contain complete AVC SPS/PPS")
        }
    }

    fun stop() {
        Log.i(TAG, "stop() called for encoder: $this, useSurface=$useSurface, hasSurfaceBeenProvided=$hasSurfaceBeenProvided")
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        isStopped = true
        checkRelease()
    }

    fun release() {
        Log.i(TAG, "release() called for encoder: $this, useSurface=$useSurface, hasSurfaceBeenProvided=$hasSurfaceBeenProvided")
        isReleasedByCameraX = true
        checkRelease()
    }

    private fun checkRelease() {
        synchronized(codecLock) {
            val shouldRelease = when {
                !useSurface -> true
                !hasSurfaceBeenProvided && isStopped -> {
                    Log.i(TAG, "Encoder stopped before surface was provided to CameraX. Releasing immediately: $this")
                    true
                }
                hasSurfaceBeenProvided && isStopped && isReleasedByCameraX -> {
                    Log.i(TAG, "Encoder stopped and surface released by CameraX. Releasing now: $this")
                    true
                }
                else -> {
                    Log.i(TAG, "Deferred release conditions not met yet for $this (isStopped=$isStopped, isReleasedByCameraX=$isReleasedByCameraX, hasSurfaceBeenProvided=$hasSurfaceBeenProvided)")
                    false
                }
            }
            if (shouldRelease) {
                doRealRelease()
            }
        }
    }

    private fun doRealRelease() {
        synchronized(codecLock) {
            if (codecReleased) return
            codecReleased = true
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            accessUnitAssembler.reset()
            partialFlags = 0
            Log.i(TAG, "H264HardwareEncoder resources actually released for: $this")
        }
    }

    companion object {
        private const val TAG = "H264HardwareEncoder"

        /** Cached AVC encoder capabilities, queried once. Makes sizing/bitrate device-agnostic. */
        data class Caps(val maxW: Int, val maxH: Int, val minBitrate: Int, val maxBitrate: Int)

        @Volatile private var cachedCaps: Caps? = null

        /** Query the device's AVC hardware encoder for its real limits (cached). */
        fun caps(): Caps {
            cachedCaps?.let { return it }
            val fallback = Caps(1920, 1080, 100_000, 20_000_000)
            val found = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
                    .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    .mapNotNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
                    .map {
                        Caps(it.supportedWidths.upper, it.supportedHeights.upper,
                             it.bitrateRange.lower, it.bitrateRange.upper)
                    }
                    // Prefer the encoder advertising the largest frame so we don't undercut HW.
                    .maxByOrNull { it.maxW.toLong() * it.maxH }
            } catch (e: Exception) { Log.w(TAG, "caps query failed: ${e.message}"); null }
            val c = found ?: fallback
            cachedCaps = c
            Log.i(TAG, "AVC encoder caps ${c.maxW}x${c.maxH} bitrate ${c.minBitrate}..${c.maxBitrate}")
            return c
        }

        /**
         * Target ~0.2 bits/pixel/frame at 30fps (w*h*6) — enough to avoid visible H.264 macroblocking
         * (the old w*h*3 ≈ 0.1 bpp looked "compressed" at 1440x1080). Clamped to the device's range.
         */
        fun bitrateFor(w: Int, h: Int): Int {
            val c = caps()
            return (w.toLong() * h * 6).coerceIn(c.minBitrate.toLong(), c.maxBitrate.toLong()).toInt()
        }
    }
}

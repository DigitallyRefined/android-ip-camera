package com.github.digitallyrefined.androidipcamera.helpers

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
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
class H264Encoder(
    val width: Int,
    val height: Int,
    frameRate: Int,
    bitRate: Int,
    private val useSurface: Boolean,
    private val onNal: (ByteArray, Boolean) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    var inputSurface: Surface? = null
        private set
    @Volatile private var running = true
    private var thread: Thread? = null
    private var semiPlanar = true   // byte-buffer layout: NV12 (interleaved UV) vs I420 (planar U then V)

    init {
        val colorFormat = if (useSurface) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                          else pickYuvFormat()   // sets semiPlanar; NV12 if the HW takes it, else I420
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            try { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (useSurface) inputSurface = codec.createInputSurface()
        codec.start()
        thread = Thread { drainLoop() }.apply { isDaemon = true; start() }
        Log.i(TAG, "H264Encoder started ${width}x$height @${frameRate}fps ${bitRate / 1000}kbps surface=$useSurface")
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
    private var fc = 0; private var fNoBuf = 0; private var ft0 = 0L; private var fConvNs = 0L

    /** Convert one analyzer frame to the encoder's YUV layout and feed it. Drops the frame if no input buffer is free. */
    fun feed(image: ImageProxy, ptsUs: Long) {
        if (!running || useSurface) return
        try {
            val idx = codec.dequeueInputBuffer(2000)
            if (idx < 0) { fNoBuf++; return }
            val buf = codec.getInputBuffer(idx)
            if (buf == null) { codec.queueInputBuffer(idx, 0, 0, ptsUs, 0); return }
            val size = width * height * 3 / 2
            var arr = yuv
            if (arr == null || arr.size != size) { arr = ByteArray(size); yuv = arr }
            val t = System.nanoTime()
            toYuv420(image, arr)
            fConvNs += System.nanoTime() - t
            buf.clear(); buf.put(arr, 0, size)
            codec.queueInputBuffer(idx, 0, size, ptsUs, 0)
            fc++
            val now = System.currentTimeMillis(); if (ft0 == 0L) ft0 = now
            else if (now - ft0 >= 3000) {
                Log.i(TAG, "feed ${fc * 1000 / (now - ft0)}fps conv ${fConvNs / 1_000_000 / maxOf(fc, 1)}ms/f nobuf=$fNoBuf")
                fc = 0; fNoBuf = 0; fConvNs = 0; ft0 = now
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "feed: ${e.message}")
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
    private fun toYuv420(image: ImageProxy, out: ByteArray) {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        var o = 0
        if (yPix == 1 && yRow == width) {
            yB.position(0); yB.get(out, 0, width * height); o = width * height       // contiguous: one copy
        } else {
            var rb = yRowBuf; if (rb == null || rb.size < yRow) { rb = ByteArray(yRow); yRowBuf = rb }
            for (r in 0 until height) {
                yB.position(r * yRow); yB.get(rb, 0, minOf(yRow, yB.remaining()))
                if (yPix == 1) System.arraycopy(rb, 0, out, o, width)
                else for (c in 0 until width) out[o + c] = rb[c * yPix]
                o += width
            }
        }
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val cw = width / 2; val ch = height / 2
        var ub = uRowBuf; if (ub == null || ub.size < uRow) { ub = ByteArray(uRow); uRowBuf = ub }
        var vb = vRowBuf; if (vb == null || vb.size < vRow) { vb = ByteArray(vRow); vRowBuf = vb }
        if (semiPlanar) {                                  // NV12: U,V interleaved
            o = width * height
            for (r in 0 until ch) {
                uB.position(r * uRow); uB.get(ub, 0, minOf(uRow, uB.remaining()))
                vB.position(r * vRow); vB.get(vb, 0, minOf(vRow, vB.remaining()))
                for (c in 0 until cw) { out[o++] = ub[c * uPix]; out[o++] = vb[c * vPix] }
            }
        } else {                                           // I420: full U plane, then full V plane
            var uo = width * height; var vo = width * height + cw * ch
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
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10000)
                if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size); buf.get(data)
                        onNal(data, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "drain: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "H264Encoder"

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

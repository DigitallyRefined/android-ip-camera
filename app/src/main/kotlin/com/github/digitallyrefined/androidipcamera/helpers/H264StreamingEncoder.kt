package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 streaming encoder implementation.
 * Handles H.264 encoding and broadcasting to H.264 clients.
 */
class H264StreamingEncoder(
    private val context: Context,
    private var streamingServerHelper: StreamingServerHelper?,
    private val onLog: (String) -> Unit = {}
) : StreamingEncoder {
    companion object {
        private const val TAG = "H264StreamingEncoder"
    }

    @Volatile var h264HardwareEncoder: H264HardwareEncoder? = null
        private set
    /** Shared OSD renderer; the surface path uses the GL pipe, the byte-buffer path blends here. */
    @Volatile var overlayRenderer: CameraOverlayRenderer? = null
    /**
     * Serializes encoder replacement between the analyzer (byte-buffer fallback) and the camera
     * thread (surface encoder). A stale analysis frame from a session that is being torn down could
     * otherwise install a byte-buffer encoder just after a surface encoder was set, silently
     * switching a full-HD stream to the slow CPU path.
     */
    private val encoderLock = Any()
    /** RTSP server sharing this encoder's output, when RTSP is enabled. */
    @Volatile internal var rtspServer: RtspServer? = null
    /**
     * True while a separate, smaller encoder feeds the RTSP sub stream. Otherwise (no sub encoder
     * running, or none possible on this capture path) sub stream viewers get this encoder's frames.
     */
    @Volatile internal var subStreamEncoded = false
    private val releasingEncoders = java.util.concurrent.CopyOnWriteArrayList<H264HardwareEncoder>()
    private var glPipe: CameraGlPipe? = null
    private var backend: CaptureBackend? = null
    private var captureRunning = false
    private val writerGeneration = AtomicLong()
    private val writerQueueCapacity = DeviceMemoryHelper.h264WriterQueueCapacity(context)
    private val networkWriter = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(writerQueueCapacity),
        { runnable -> Thread(runnable, "H264NetworkWriter").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_CRITICAL still arrives on older devices (< API 34).
    override fun processFrame(image: ImageProxy) {
        val enc = h264HardwareEncoder
        if (enc != null && enc.useSurface) {
            // Already running in surface mode, camera renders directly to it.
            return
        }
        try {
            var encMutable = enc
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val camId = prefs.getString("camera_id", null)
            // Rotation is controlled only by the per-camera rotate= setting. The camera's
            // sensor orientation is intentionally ignored so the stream never auto-rotates.
            val userRotation = readRotatePref(prefs, camId)
            val totalRotation = ((userRotation % 360) + 360) % 360
            // H.264 YUV rotation is done on 90° boundaries (the rotate control is arbitrary, but
            // arbitrary-angle rotation would require a full software resample per frame).
            val rotation = ((totalRotation + 45) / 90 * 90) % 360
            val swap = rotation == 90 || rotation == 270
            val rotatedW = if (swap) image.height else image.width
            val rotatedH = if (swap) image.width else image.height
            val crop = CropRegion.read(prefs, camId)?.takeUnless { it.isFullFrame() }
            val outW: Int
            val outH: Int
            if (crop != null) {
                val bounds = crop.outputSize(rotatedW, rotatedH)
                outW = bounds.width
                outH = bounds.height
            } else {
                outW = rotatedW
                outH = rotatedH
            }
            if (encMutable == null || encMutable.width != outW || encMutable.height != outH) {
                encMutable = synchronized(encoderLock) {
                    val current = h264HardwareEncoder
                    if (current != null && current.useSurface) {
                        // A surface encoder was installed while this (possibly stale) frame was
                        // being prepared. Never replace it with a byte-buffer encoder.
                        return
                    }
                    if (current != null && current.width == outW && current.height == outH) {
                        current
                    } else {
                        invalidatePendingWrites()
                        current?.stop()
                        createByteBufferEncoder(outW, outH)
                    }
                }
            }
            val mirror = run {
                val phys = camId?.substringAfter(':', camId)
                when {
                    camId != null && prefs.contains("mirror_$camId") -> prefs.getString("mirror_$camId", null)?.toBoolean() ?: false
                    phys != null && prefs.contains("mirror_$phys") -> prefs.getString("mirror_$phys", null)?.toBoolean() ?: false
                    else -> false
                }
            }
            val grayscale = NightMode.effectiveGrayscale(context)
            encMutable.feed(
                image,
                image.imageInfo.timestamp / 1000,
                mirror,
                rotation,
                crop,
                grayscale = grayscale,
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "processFrame OOM: ${e.message}")
            synchronized(encoderLock) {
                invalidatePendingWrites()
                h264HardwareEncoder?.stop()
                h264HardwareEncoder = null
            }
            DeviceMemoryHelper.updateMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            try { System.gc() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "feed: ${e.message}")
        }
    }

    private fun createByteBufferEncoder(outW: Int, outH: Int): H264HardwareEncoder {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
        val fpsCoerced = fps.coerceIn(1, 60)
        val encConfig = StreamEncoding.encoderConfig(prefs, outW, outH, fpsCoerced)
        val enc = H264HardwareEncoder(
            outW,
            outH,
            fpsCoerced,
            encConfig.bitrateBps,
            false,
            encConfig.keyframeIntervalSec,
            encConfig.bitrateMode,
        ) { d, k, pts -> broadcastH264(d, k, pts) }
        attachOverlay(enc)
        h264HardwareEncoder = enc
        enc.requestKeyFrame()
        streamingServerHelper?.resetH264Wait()
        return enc
    }

    /** Wire the shared OSD renderer into an encoder created here or by the camera backend. */
    fun attachOverlay(enc: H264HardwareEncoder) {
        enc.overlayProvider = { w, h -> overlayRenderer?.frame(w, h) }
    }

    /**
     * Encode one night-mode still into the H.264 stream. The still is a JPEG, so it is decoded,
     * rotated/mirrored/cropped exactly like a camera frame, scaled into [maxWidth]×[maxHeight], and
     * fed to a byte-buffer encoder as NV21. Night captures are far slower than video, so the CPU cost
     * of this path is negligible next to the exposure itself.
     */
    fun processStillJpeg(jpeg: ByteArray, maxWidth: Int, maxHeight: Int, sensorRotationDegrees: Int = 0) {
        if (!hasClients()) return
        var bitmap: Bitmap? = null
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val camId = prefs.getString("camera_id", null)
            val rotation = quantized((((readRotatePref(prefs, camId) % 360) + 360) % 360))
            val mirror = run {
                val phys = camId?.substringAfter(':', camId)
                when {
                    camId != null && prefs.contains("mirror_$camId") -> prefs.getString("mirror_$camId", null)?.toBoolean() ?: false
                    phys != null && prefs.contains("mirror_$phys") -> prefs.getString("mirror_$phys", null)?.toBoolean() ?: false
                    else -> false
                }
            }
            val crop = CropRegion.read(prefs, camId)?.takeUnless { it.isFullFrame() }
            val grayscale = NightMode.effectiveGrayscale(context)

            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            var current = if (sensorRotationDegrees != 0) {
                NightStillTransform.toSensorSpace(bitmap, sensorRotationDegrees).also { bitmap = it }
            } else {
                bitmap
            }
            if (rotation != 0 || mirror) {
                val matrix = Matrix().apply {
                    if (mirror) {
                        postScale(-1f, 1f)
                        postTranslate(current.width.toFloat(), 0f)
                    }
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                val transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
                if (transformed !== current) {
                    current.recycle()
                    current = transformed
                    bitmap = transformed
                }
            }
            if (crop != null) {
                val bounds = crop.pixelBounds(current.width, current.height)
                if (bounds.width < current.width || bounds.height < current.height) {
                    val cropped = Bitmap.createBitmap(current, bounds.left, bounds.top, bounds.width, bounds.height)
                    if (cropped !== current) {
                        current.recycle()
                        current = cropped
                        bitmap = cropped
                    }
                }
            }
            if (maxWidth > 0 && maxHeight > 0) {
                // Cap by the long side so a rotated portrait still keeps its stream aspect ratio
                // instead of being shrunk to fit inside a landscape box.
                val limit = maxOf(maxWidth, maxHeight)
                val longest = maxOf(current.width, current.height)
                if (longest > limit) {
                    val scale = limit.toFloat() / longest
                    val scaled = current.scale(
                        evenAtLeastTwo((current.width * scale).toInt()),
                        evenAtLeastTwo((current.height * scale).toInt()),
                        true,
                    )
                    if (scaled !== current) {
                        current.recycle()
                        current = scaled
                        bitmap = scaled
                    }
                }
            }
            if (current.width % 2 != 0 || current.height % 2 != 0) {
                val even = current.scale(
                    evenAtLeastTwo(current.width),
                    evenAtLeastTwo(current.height),
                    true,
                )
                if (even !== current) {
                    current.recycle()
                    current = even
                    bitmap = even
                }
            }

            var enc = synchronized(encoderLock) {
                val existing = h264HardwareEncoder
                if (existing != null && !existing.useSurface &&
                    existing.width == current.width && existing.height == current.height
                ) {
                    existing
                } else {
                    invalidatePendingWrites()
                    existing?.stop()
                    createByteBufferEncoder(current.width, current.height)
                }
            }
            val nv21 = convertBitmapToNV21(current)
            if (grayscale) {
                // YUV-domain monochrome: every Y sample is kept, V/U become neutral. No extra
                // bitmap allocation or JPEG decode/encode is needed on this path.
                Grayscale.neutralizeYuv420Chroma(nv21, current.width, current.height)
            }
            enc.feedNv21(nv21, SystemClock.elapsedRealtimeNanos() / 1000)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "processStillJpeg OOM: ${e.message}")
            try { System.gc() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "processStillJpeg: ${e.message}")
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }

    private fun quantized(angle: Int): Int = ((angle + 45) / 90 * 90) % 360

    private fun evenAtLeastTwo(value: Int): Int {
        var v = value.coerceAtLeast(2)
        if (v % 2 != 0) v--
        return v.coerceAtLeast(2)
    }

    /** Per-camera rotate= control (token then physical id fallback, mirroring the MJPEG encoder). */
    private fun readRotatePref(prefs: android.content.SharedPreferences, camId: String?): Int {
        if (camId == null) return 0
        val phys = camId.substringAfter(':', camId)
        return when {
            prefs.contains("camera_rotate_$camId") -> prefs.getInt("camera_rotate_$camId", 0)
            prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
            else -> 0
        }
    }

    override fun handleRemoteControl(key: String, value: String): Boolean {
        // H.264 encoder doesn't have specific remote controls
        // All camera controls are handled at the backend level
        return false
    }

    override fun start() {
        // H.264 encoder is started on-demand when frames arrive
        captureRunning = true
    }

    override fun stop() {
        backend?.stop()
        backend = null
        glPipe?.stop()
        glPipe = null
        synchronized(encoderLock) {
            h264HardwareEncoder?.let { enc ->
                enc.stop()
                if (enc.useSurface && enc.hasSurfaceBeenProvided && !enc.isReleased) {
                    releasingEncoders.add(enc)
                }
            }
            h264HardwareEncoder = null
        }
        invalidatePendingWrites()
        captureRunning = false
    }

    suspend fun awaitRelease() {
        if (releasingEncoders.isEmpty()) return
        Log.i(TAG, "Awaiting release of ${releasingEncoders.size} old H.264 hardware encoder(s)")
        val iterator = releasingEncoders.iterator()
        while (iterator.hasNext()) {
            val enc = iterator.next()
            var attempts = 0
            while (!enc.isReleased && attempts < 30) {
                delay(100)
                attempts++
            }
            if (enc.isReleased) {
                Log.i(TAG, "Old H.264 hardware encoder $enc fully released after ${attempts * 100}ms")
            } else {
                Log.w(TAG, "Timeout waiting for old H.264 hardware encoder to release: $enc")
            }
            releasingEncoders.remove(enc)
        }
    }

    override fun hasClients(): Boolean {
        return streamingServerHelper?.getH264Clients()?.isNotEmpty() == true ||
            rtspServer?.hasVideoClients() == true
    }

    /**
     * Broadcast H.264 data to all connected H.264 clients (HTTP and RTSP).
     */
    fun broadcastH264(data: ByteArray, isKey: Boolean, ptsUs: Long) {
        // RTSP sessions queue frames on their own sender threads, so this never blocks the codec.
        rtspServer?.let { rtsp ->
            rtsp.onVideoFrame(data, isKey, ptsUs, RtspStream.MAIN)
            if (!subStreamEncoded) rtsp.onVideoFrame(data, isKey, ptsUs, RtspStream.SUB)
        }
        val helper = streamingServerHelper ?: return
        val clients = helper.getH264Clients()
        if (clients.isEmpty()) return
        val generation = writerGeneration.get()

        // One bounded worker preserves access-unit ordering without ever blocking MediaCodec.
        if (networkWriter.queue.remainingCapacity() == 0) {
            disconnectStalledClients(helper)
            return
        }
        try {
            // Capture recipients now so queued frames from a disconnected viewer can never be
            // delivered as a stale burst to a viewer that connects later.
            networkWriter.execute { writeH264(data, isKey, clients, generation) }
        } catch (_: RejectedExecutionException) {
            disconnectStalledClients(helper)
        }
    }

    private fun disconnectStalledClients(helper: StreamingServerHelper) {
        invalidatePendingWrites()
        val stalled = helper.getH264Clients()
        Log.w(TAG, "H.264 writer stalled; disconnecting ${stalled.size} client(s)")
        onLog("H.264 client disconnected because its network writer stalled")
        stalled.forEach { helper.removeH264Client(it) }
    }

    private fun writeH264(
        data: ByteArray,
        isKey: Boolean,
        clients: List<StreamingServerHelper.Client>,
        generation: Long
    ) {
        if (generation != writerGeneration.get()) return
        val helper = streamingServerHelper ?: return
        val toRemove = mutableListOf<StreamingServerHelper.Client>()
        for (c in clients) {
            if (generation != writerGeneration.get()) return
            try {
                val wasWaitingForKey = c.waitingKey
                if (wasWaitingForKey && !isKey) continue
                c.outputStream.write(data)
                c.outputStream.flush()
                // A restart may happen while a socket write is blocked. Never let an access unit
                // from the old encoder generation mark the new stream as synchronized.
                if (wasWaitingForKey && generation == writerGeneration.get()) c.waitingKey = false
            } catch (e: Exception) {
                toRemove.add(c)
            }
        }
        toRemove.forEach { helper.removeH264Client(it) }
    }

    /**
     * Reset H.264 wait state for all clients (called when encoder restarts).
     */
    fun resetWait() {
        invalidatePendingWrites()
        streamingServerHelper?.resetH264Wait()
    }

    /**
     * Check if H.264 encoder is running.
     */
    fun isRunning(): Boolean = captureRunning

    /**
     * Update the streaming server helper reference (called after server initialization).
     */
    fun updateServerHelper(helper: StreamingServerHelper) {
        streamingServerHelper = helper
    }

    /**
     * Set the H.264 hardware encoder (used by Camera1 backend).
     */
    fun setEncoder(encoder: H264HardwareEncoder?) {
        synchronized(encoderLock) {
            invalidatePendingWrites()
            encoder?.let { attachOverlay(it) }
            h264HardwareEncoder?.let { old ->
                old.stop()
                if (old.useSurface && old.hasSurfaceBeenProvided && !old.isReleased) {
                    releasingEncoders.add(old)
                }
            }
            h264HardwareEncoder = encoder
        }
    }

    /**
     * Hands the current encoder over without stopping it, for a caller that must stop it later: a
     * Camera1 GL pipe keeps drawing into the encoder's surface until its preview stops.
     */
    fun detachEncoder(): H264HardwareEncoder? = synchronized(encoderLock) {
        invalidatePendingWrites()
        val enc = h264HardwareEncoder
        h264HardwareEncoder = null
        enc
    }

    private fun invalidatePendingWrites() {
        writerGeneration.incrementAndGet()
        networkWriter.queue.clear()
    }
}

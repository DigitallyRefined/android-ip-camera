package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
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

    var h264HardwareEncoder: H264HardwareEncoder? = null
        private set
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

    override fun processFrame(image: ImageProxy) {
        val enc = h264HardwareEncoder
        if (enc != null && enc.useSurface) {
            // Already running in surface mode, camera renders directly to it.
            return
        }
        try {
            var encMutable = enc
            if (encMutable == null || encMutable.width != image.width || encMutable.height != image.height) {
                invalidatePendingWrites()
                encMutable?.stop()
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
                val fpsCoerced = fps.coerceIn(1, 60)
                encMutable = H264HardwareEncoder(
                    image.width,
                    image.height,
                    fpsCoerced,
                    H264HardwareEncoder.bitrateFor(image.width, image.height),
                    false
                ) { d, k -> broadcastH264(d, k) }
                h264HardwareEncoder = encMutable
                encMutable.requestKeyFrame()
                streamingServerHelper?.resetH264Wait()
            }
            encMutable.feed(image, image.imageInfo.timestamp / 1000)
        } catch (e: Exception) {
            Log.e(TAG, "feed: ${e.message}")
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
        h264HardwareEncoder?.let { enc ->
            enc.stop()
            if (enc.useSurface && enc.hasSurfaceBeenProvided && !enc.isReleased) {
                releasingEncoders.add(enc)
            }
        }
        h264HardwareEncoder = null
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
        return streamingServerHelper?.getH264Clients()?.isNotEmpty() == true
    }

    /**
     * Broadcast H.264 data to all connected H.264 clients.
     */
    fun broadcastH264(data: ByteArray, isKey: Boolean) {
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
        invalidatePendingWrites()
        h264HardwareEncoder?.let { old ->
            old.stop()
            if (old.useSurface && old.hasSurfaceBeenProvided && !old.isReleased) {
                releasingEncoders.add(old)
            }
        }
        h264HardwareEncoder = encoder
    }

    private fun invalidatePendingWrites() {
        writerGeneration.incrementAndGet()
        networkWriter.queue.clear()
    }
}

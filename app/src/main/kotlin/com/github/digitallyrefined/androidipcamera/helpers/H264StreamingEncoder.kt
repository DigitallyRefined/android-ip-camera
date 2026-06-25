package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import kotlinx.coroutines.launch

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
    private var glPipe: CameraGlPipe? = null
    private var backend: CaptureBackend? = null
    private var captureRunning = false

    override fun processFrame(image: ImageProxy): Boolean {
        try {
            var enc = h264HardwareEncoder
            if (enc == null || enc.width != image.width || enc.height != image.height) {
                enc?.stop()
                enc = H264HardwareEncoder(
                    image.width,
                    image.height,
                    30,
                    H264HardwareEncoder.bitrateFor(image.width, image.height),
                    false
                ) { d, k -> broadcastH264(d, k) }
                h264HardwareEncoder = enc
                enc.requestKeyFrame()
                streamingServerHelper?.resetH264Wait()
            }
            enc.feed(image, image.imageInfo.timestamp / 1000)
        } catch (e: Exception) {
            Log.e(TAG, "feed: ${e.message}")
        }
        return true // Always close the image after feeding to H.264
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
        h264HardwareEncoder?.stop()
        h264HardwareEncoder = null
        captureRunning = false
    }

    override fun hasClients(): Boolean {
        return streamingServerHelper?.getH264Clients()?.isNotEmpty() == true
    }

    /**
     * Broadcast H.264 data to all connected H.264 clients.
     */
    fun broadcastH264(data: ByteArray, isKey: Boolean) {
        val helper = streamingServerHelper ?: return
        val list = helper.getH264Clients()
        for (c in list) {
            try {
                if (c.waitingKey) {
                    if (!isKey) continue
                    c.waitingKey = false
                }
                c.outputStream.write(data)
                c.outputStream.flush()
            } catch (e: Exception) {
                helper.removeH264Client(c)
            }
        }
    }

    /**
     * Reset H.264 wait state for all clients (called when encoder restarts).
     */
    fun resetWait() {
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
    fun setEncoder(encoder: H264HardwareEncoder) {
        h264HardwareEncoder = encoder
    }
}

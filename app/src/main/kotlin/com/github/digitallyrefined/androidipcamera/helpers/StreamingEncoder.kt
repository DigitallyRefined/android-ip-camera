package com.github.digitallyrefined.androidipcamera.helpers

import androidx.camera.core.ImageProxy

/**
 * Base interface for streaming encoders (H.264, MJPEG, etc.)
 * Defines the contract for processing camera frames and handling encoder-specific remote controls.
 */
interface StreamingEncoder {
    /**
     * Process a camera frame for this encoder.
     * @param image The camera frame to process (caller closes after all encoders run)
     */
    fun processFrame(image: ImageProxy)

    /**
     * Handle an encoder-specific remote control command.
     * @param key The command key
     * @param value The command value
     * @return true if the command was handled by this encoder, false otherwise
     */
    fun handleRemoteControl(key: String, value: String): Boolean

    /**
     * Called when the encoder should start/resume streaming.
     */
    fun start()

    /**
     * Called when the encoder should stop streaming.
     */
    fun stop()

    /**
     * Check if this encoder has any active clients.
     */
    fun hasClients(): Boolean
}

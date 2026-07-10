package com.github.digitallyrefined.androidipcamera.helpers

/** Timestamp-based frame pacing for camera surfaces that produce faster than the encoder target. */
internal class FrameRateLimiter(targetFps: Int) {
    private val intervalNs = 1_000_000_000L / targetFps.coerceIn(1, 60)
    private var nextFrameNs = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE

    fun shouldEmit(timestampNs: Long): Boolean {
        if (lastTimestampNs != Long.MIN_VALUE && timestampNs < lastTimestampNs) reset()
        lastTimestampNs = timestampNs

        if (nextFrameNs == Long.MIN_VALUE) {
            nextFrameNs = timestampNs + intervalNs
            return true
        }
        if (timestampNs < nextFrameNs) return false

        do {
            nextFrameNs += intervalNs
        } while (nextFrameNs <= timestampNs)
        return true
    }

    fun reset() {
        nextFrameNs = Long.MIN_VALUE
        lastTimestampNs = Long.MIN_VALUE
    }
}

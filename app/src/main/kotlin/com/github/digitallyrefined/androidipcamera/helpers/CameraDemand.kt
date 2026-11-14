package com.github.digitallyrefined.androidipcamera.helpers

/**
 * What the camera's consumers ask of a session right now. StreamingService builds a session from
 * one of these and keeps it in a [CameraSession], so a viewer joining or leaving restarts the camera
 * only when the running session cannot serve the new set of consumers.
 */
data class CameraDemand(
    /** H.264 viewers over HTTP, RTSP (either stream) or the relay. */
    val h264Viewers: Boolean = false,
    val mjpegViewers: Boolean = false,
    /** A recording runs or is being started, so the H.264 encoder is needed. */
    val recording: Boolean = false,
    val motion: Boolean = false,
    /** A /video/snapshot is running. */
    val snapshot: Boolean = false,
    /** The on-phone preview is attached. */
    val preview: Boolean = false,
) {
    /** Some consumer needs frames: the start checks that CameraX delivers them, or uses Camera1. */
    val frames: Boolean get() = h264Viewers || mjpegViewers || recording || motion || snapshot
    /** CameraX feeds H.264 through the encoder surface, on cameras that support one. */
    val h264: Boolean get() = h264Viewers || recording
    /** Camera1 installs its NV21 preview callback, which feeds MJPEG and motion detection. */
    val nv21: Boolean get() = mjpegViewers || motion
    /** CameraX binds the on-phone preview: attached and no network viewer watching. */
    val screenPreview: Boolean get() = preview && !h264Viewers && !mjpegViewers
}

/** The running camera session: the demand it was built for, its camera, its backend and the settings it read. */
data class CameraSession(
    val demand: CameraDemand,
    val camera1: Boolean,
    /** The camera could feed the CameraX H.264 encoder surface (back camera, FULL or LEVEL_3). */
    val surfaceEncoder: Boolean,
    /** stream_fps at the start. The surface encoders only pick up a new value through a restart. */
    val fps: Int,
    /** The camera it opened, as the service names it: a Camera2 id, or "front"/"back". */
    val camera: String,
) {
    /**
     * True when this session serves [next] as it is, with stream_fps now at [fps] and [camera] selected.
     * A session left running on another camera (a snapshot's, kept open by the torch) never serves.
     * Frames starting or stopping always restart, since that moves between the idle CameraX session
     * and a streaming one. Camera1 always feeds H.264 and never shows the on-phone preview, so only a
     * missing NV21 callback matters there. CameraX hands every analysis frame to MJPEG and motion
     * detection, so only the encoder surface and the on-phone preview matter. An fps change used to
     * reach the encoder at the next viewer change, and still does.
     */
    fun serves(next: CameraDemand, fps: Int, camera: String): Boolean = when {
        camera != this.camera -> false
        fps != this.fps -> false
        demand.frames != next.frames -> false
        camera1 -> demand.nv21 || !next.nv21
        else -> (!surfaceEncoder || demand.h264 == next.h264) && demand.screenPreview == next.screenPreview
    }

    companion object {
        /**
         * True when a session started at [startedAt] still delivers at [now]: its camera drew a frame
         * in the last [stallMs], and so did its encoder when [encodedAt] is given (an encoder fed on
         * every camera frame). A session younger than [stallMs] counts as delivering.
         */
        fun delivering(now: Long, startedAt: Long, stallMs: Long, frameAt: Long, encodedAt: Long? = null): Boolean {
            fun fresh(at: Long) = now - maxOf(startedAt, at) < stallMs
            return fresh(frameAt) && (encodedAt == null || fresh(encodedAt))
        }
    }
}

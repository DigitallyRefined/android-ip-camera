package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionTest {
    private val fps = 30
    private val back = "0"
    private fun camera1(demand: CameraDemand) = CameraSession(demand, camera1 = true, surfaceEncoder = true, fps = fps, camera = back)
    private fun cameraX(demand: CameraDemand, surfaceEncoder: Boolean) =
        CameraSession(demand, camera1 = false, surfaceEncoder = surfaceEncoder, fps = fps, camera = back)
    private val allBackends = listOf<(CameraDemand) -> CameraSession>(
        ::camera1, { cameraX(it, true) }, { cameraX(it, false) }
    )

    private val idle = CameraDemand()
    private val preview = CameraDemand(preview = true)
    private val h264 = CameraDemand(h264Viewers = true)
    private val mjpeg = CameraDemand(mjpegViewers = true)
    private val both = CameraDemand(h264Viewers = true, mjpegViewers = true)
    private val motion = CameraDemand(motion = true)

    @Test
    fun `the same viewers are served without a restart`() {
        // A VLC reconnect or a second RTSP client: H.264 viewers before and after.
        for (session in allBackends) {
            for (demand in listOf(h264, mjpeg, both, motion, CameraDemand(recording = true))) {
                assertTrue(session(demand).serves(demand, fps, back))
            }
        }
    }

    @Test
    fun `frames starting or stopping always restart`() {
        for (session in allBackends) {
            assertFalse(session(idle).serves(h264, fps, back))
            assertFalse(session(preview).serves(CameraDemand(preview = true, mjpegViewers = true), fps, back))
            assertFalse(session(h264).serves(idle, fps, back))
            assertFalse(session(CameraDemand(h264Viewers = true, preview = true)).serves(preview, fps, back))
        }
    }

    @Test
    fun `camera1 restarts only for a missing NV21 callback`() {
        // An MJPEG viewer joining an H.264 only session needs the preview callback installed.
        assertFalse(camera1(h264).serves(both, fps, back))
        // Motion detection installed it already.
        assertTrue(camera1(CameraDemand(h264Viewers = true, motion = true))
            .serves(CameraDemand(h264Viewers = true, motion = true, mjpegViewers = true), fps, back))
        assertTrue(camera1(motion).serves(CameraDemand(motion = true, mjpegViewers = true), fps, back))
        // An MJPEG viewer leaving.
        assertTrue(camera1(both).serves(h264, fps, back))
        // H.264 and the on-phone preview do not change a Camera1 session.
        assertTrue(camera1(mjpeg).serves(both, fps, back))
        assertTrue(camera1(both).serves(mjpeg, fps, back))
        assertTrue(camera1(CameraDemand(motion = true, preview = true))
            .serves(CameraDemand(motion = true, preview = true, h264Viewers = true), fps, back))
    }

    @Test
    fun `cameraX with the encoder surface restarts when H264 or the preview flips`() {
        assertFalse(cameraX(mjpeg, true).serves(both, fps, back))
        assertFalse(cameraX(both, true).serves(mjpeg, fps, back))
        assertFalse(cameraX(motion, true).serves(CameraDemand(motion = true, recording = true), fps, back))
        // MJPEG joining or leaving with H.264 unchanged.
        assertTrue(cameraX(h264, true).serves(both, fps, back))
        assertTrue(cameraX(both, true).serves(h264, fps, back))
        // The on-phone preview hides when a network viewer joins and comes back when it leaves.
        val previewMotion = CameraDemand(motion = true, preview = true)
        val previewMotionMjpeg = CameraDemand(motion = true, preview = true, mjpegViewers = true)
        assertFalse(cameraX(previewMotion, true).serves(previewMotionMjpeg, fps, back))
        assertFalse(cameraX(previewMotionMjpeg, true).serves(previewMotion, fps, back))
    }

    @Test
    fun `cameraX without the encoder surface keeps serving H264 changes`() {
        // The byte-buffer encoder takes analysis frames, so H.264 viewers need no new session.
        assertTrue(cameraX(mjpeg, false).serves(both, fps, back))
        assertTrue(cameraX(both, false).serves(mjpeg, fps, back))
        assertTrue(cameraX(motion, false).serves(CameraDemand(motion = true, recording = true), fps, back))
        val previewMotion = CameraDemand(motion = true, preview = true)
        val previewMotionH264 = CameraDemand(motion = true, preview = true, h264Viewers = true)
        assertFalse(cameraX(previewMotion, false).serves(previewMotionH264, fps, back))
        assertFalse(cameraX(previewMotionH264, false).serves(previewMotion, fps, back))
    }

    @Test
    fun `a session on another camera is never served`() {
        // A snapshot's session on the front camera, left open by the torch while back is selected.
        for (session in allBackends) {
            for (demand in listOf(h264, mjpeg, both, motion, CameraDemand(snapshot = true))) {
                assertFalse(session(demand).serves(demand, fps, "1"))
                assertFalse(session(demand).serves(demand, fps, "front"))
            }
        }
    }

    @Test
    fun `a young session counts as delivering`() {
        val stall = 5_000L
        val startedAt = 100_000L
        assertTrue(CameraSession.delivering(startedAt + 4_999, startedAt, stall, frameAt = 0L))
        assertTrue(CameraSession.delivering(startedAt + 4_999, startedAt, stall, frameAt = 0L, encodedAt = 0L))
        assertFalse(CameraSession.delivering(startedAt + 5_000, startedAt, stall, frameAt = 0L))
    }

    @Test
    fun `a session without camera frames lately is stalled`() {
        val stall = 5_000L
        val startedAt = 100_000L
        val now = startedAt + 60_000
        assertTrue(CameraSession.delivering(now, startedAt, stall, frameAt = now - 4_999))
        assertFalse(CameraSession.delivering(now, startedAt, stall, frameAt = now - 5_000))
    }

    @Test
    fun `a surface encoder without output lately is stalled while the camera runs`() {
        // The codec was reclaimed or its drain failed: the camera keeps drawing, nothing comes out.
        val stall = 5_000L
        val startedAt = 100_000L
        val now = startedAt + 60_000
        assertTrue(CameraSession.delivering(now, startedAt, stall, frameAt = now - 30, encodedAt = now - 40))
        assertFalse(CameraSession.delivering(now, startedAt, stall, frameAt = now - 30, encodedAt = now - 5_000))
        assertFalse(CameraSession.delivering(now, startedAt, stall, frameAt = now - 30, encodedAt = 0L))
        // No encoder to watch (byte-buffer path): the camera frames decide.
        assertTrue(CameraSession.delivering(now, startedAt, stall, frameAt = now - 30, encodedAt = null))
    }

    @Test
    fun `a stream_fps change is never served`() {
        for (session in allBackends) {
            for (demand in listOf(idle, preview, h264, mjpeg, both, motion)) {
                assertFalse(session(demand).serves(demand, 15, back))
                assertFalse(session(demand).serves(demand, 60, back))
            }
        }
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDemandTest {
    @Test
    fun `every consumer but the preview needs frames`() {
        assertTrue(CameraDemand(h264Viewers = true).frames)
        assertTrue(CameraDemand(mjpegViewers = true).frames)
        assertTrue(CameraDemand(recording = true).frames)
        assertTrue(CameraDemand(motion = true).frames)
        assertTrue(CameraDemand(keepAlive = true).frames)
        assertTrue(CameraDemand(snapshot = true).frames)
        assertFalse(CameraDemand(preview = true).frames)
        assertFalse(CameraDemand(lightMeter = true).frames)
        assertFalse(CameraDemand().frames)
    }

    @Test
    fun `camera ae metering is passive but requests the camera1 preview callback`() {
        // Camera AE never starts or holds the camera open: it only samples a running session.
        assertFalse(CameraDemand(lightMeter = true).frames)
        // On Camera1 the luma plane comes from the NV21 preview callback, so it must be installed
        // when a session is already running for another consumer.
        assertTrue(CameraDemand(lightMeter = true).nv21)
    }

    @Test
    fun `h264 follows H264 viewers and recording`() {
        assertTrue(CameraDemand(h264Viewers = true).h264)
        assertTrue(CameraDemand(recording = true).h264)
        assertFalse(CameraDemand(mjpegViewers = true, motion = true, snapshot = true, preview = true).h264)
    }

    @Test
    fun `nv21 follows MJPEG viewers, motion detection and camera keep-alive`() {
        assertTrue(CameraDemand(mjpegViewers = true).nv21)
        assertTrue(CameraDemand(motion = true).nv21)
        assertTrue(CameraDemand(keepAlive = true).nv21)
        assertFalse(CameraDemand(h264Viewers = true, recording = true, snapshot = true, preview = true).nv21)
    }

    @Test
    fun `screen preview shows only while no network viewer watches`() {
        assertTrue(CameraDemand(preview = true).screenPreview)
        assertTrue(CameraDemand(preview = true, recording = true).screenPreview)
        assertTrue(CameraDemand(preview = true, motion = true).screenPreview)
        assertFalse(CameraDemand(preview = true, h264Viewers = true).screenPreview)
        assertFalse(CameraDemand(preview = true, mjpegViewers = true).screenPreview)
        assertFalse(CameraDemand(recording = true).screenPreview)
        assertEquals(false, CameraDemand().screenPreview)
    }
}

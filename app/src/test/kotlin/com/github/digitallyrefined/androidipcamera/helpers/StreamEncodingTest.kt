package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamEncodingTest {
    @Test
    fun `manual bitrate parses inside the supported range`() {
        assertEquals(500, StreamEncoding.parseBitrateKbps("500"))
        assertEquals(8000, StreamEncoding.parseBitrateKbps(" 8000 "))
    }

    @Test
    fun `auto or out-of-range values mean auto`() {
        assertNull(StreamEncoding.parseBitrateKbps(null))
        assertNull(StreamEncoding.parseBitrateKbps("auto"))
        assertNull(StreamEncoding.parseBitrateKbps(""))
        assertNull(StreamEncoding.parseBitrateKbps("499"))
        assertNull(StreamEncoding.parseBitrateKbps("8001"))
        assertNull(StreamEncoding.parseBitrateKbps("not-a-number"))
    }

    @Test
    fun `bitrate mode falls back to vbr`() {
        assertEquals(StreamEncoding.MODE_VBR, StreamEncoding.normalizeMode(null))
        assertEquals(StreamEncoding.MODE_VBR, StreamEncoding.normalizeMode(""))
        assertEquals(StreamEncoding.MODE_CBR, StreamEncoding.normalizeMode("CBR"))
        assertEquals(StreamEncoding.MODE_VBR, StreamEncoding.normalizeMode("something-else"))
    }

    @Test
    fun `keyframe interval defaults and clamps to one through 120 seconds`() {
        assertEquals(1, StreamEncoding.parseKeyframeIntervalSec(null))
        assertEquals(1, StreamEncoding.parseKeyframeIntervalSec("0"))
        assertEquals(120, StreamEncoding.parseKeyframeIntervalSec("999"))
        assertEquals(30, StreamEncoding.parseKeyframeIntervalSec("30"))
        assertEquals(1, StreamEncoding.parseKeyframeIntervalSec("invalid"))
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsHelperTest {
    @Test
    fun `accepts plain recording file names`() {
        assertTrue(RecordingsHelper.isValidFileName("ipcam_20260821_093000.mp4"))
        assertTrue(RecordingsHelper.isValidFileName("clip (1).mp4"))
        assertTrue(RecordingsHelper.isValidFileName("a+b&c.mp4"))
    }

    @Test
    fun `rejects path traversal and separators`() {
        assertFalse(RecordingsHelper.isValidFileName("../secret.txt"))
        assertFalse(RecordingsHelper.isValidFileName("foo/bar.mp4"))
        assertFalse(RecordingsHelper.isValidFileName("foo\\bar.mp4"))
        assertFalse(RecordingsHelper.isValidFileName("/etc/passwd"))
        assertFalse(RecordingsHelper.isValidFileName(".."))
        assertFalse(RecordingsHelper.isValidFileName("."))
    }

    @Test
    fun `rejects blank names and control characters`() {
        assertFalse(RecordingsHelper.isValidFileName(""))
        assertFalse(RecordingsHelper.isValidFileName("   "))
        assertFalse(RecordingsHelper.isValidFileName("bad\u0000name.mp4"))
        assertFalse(RecordingsHelper.isValidFileName("bad\r\nInjected-Header: 1"))
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `normalizes nested recording paths`() {
        assertEquals("", RecordingsHelper.normalizeRelativePath(null))
        assertEquals("", RecordingsHelper.normalizeRelativePath(""))
        assertEquals("2026", RecordingsHelper.normalizeRelativePath("2026"))
        assertEquals("2026/11/09", RecordingsHelper.normalizeRelativePath("2026/11/09"))
        assertEquals("2026/11/09", RecordingsHelper.normalizeRelativePath("2026/11/09/"))
        assertEquals("2026/11/09/clip.mp4", RecordingsHelper.normalizeRelativePath("2026/11/09/clip.mp4"))
    }

    @Test
    fun `rejects unsafe relative paths`() {
        assertNull(RecordingsHelper.normalizeRelativePath("/2026/11/09"))
        assertNull(RecordingsHelper.normalizeRelativePath("../secret.mp4"))
        assertNull(RecordingsHelper.normalizeRelativePath("2026/../../secret.mp4"))
        assertNull(RecordingsHelper.normalizeRelativePath("2026\\11\\09\\clip.mp4"))
        assertNull(RecordingsHelper.normalizeRelativePath("2026/11/09/bad\u0000name.mp4"))
    }
}

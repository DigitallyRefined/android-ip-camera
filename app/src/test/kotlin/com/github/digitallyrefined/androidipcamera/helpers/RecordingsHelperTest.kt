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

    @Test
    fun `storage cleanup leaves space below the limit alone`() {
        // 100 GB drive, 20 GB free: 80% used, below the default 90% limit.
        assertEquals(0L, RecordingsHelper.bytesToFree(100_000_000_000, 20_000_000_000, 90))
        assertFalse(RecordingsHelper.shouldTrim(100_000_000_000, 20_000_000_000, 90))
    }

    @Test
    fun `storage cleanup targets the free space needed at the limit`() {
        // 100 GB drive, 5 GB free at 95% used: keep 10 GB free, so free 5 GB.
        assertEquals(5_000_000_000L, RecordingsHelper.bytesToFree(100_000_000_000, 5_000_000_000, 90))
        assertTrue(RecordingsHelper.shouldTrim(100_000_000_000, 5_000_000_000, 90))
    }

    @Test
    fun `storage cleanup treats a full drive as needing all recordings gone`() {
        assertEquals(10_000_000_000L, RecordingsHelper.bytesToFree(100_000_000_000, 0, 90))
        assertEquals(0L, RecordingsHelper.bytesToFree(0, 0, 90))
    }

    @Test
    fun `storage limit is clamped to a sane range`() {
        // Below 1% clamps to 1 (keep 99% free); above 99 clamps to 99 (keep 1% free).
        assertEquals(98L, RecordingsHelper.bytesToFree(100, 1, -5))
        assertEquals(1L, RecordingsHelper.bytesToFree(100, 0, 200))
    }
}

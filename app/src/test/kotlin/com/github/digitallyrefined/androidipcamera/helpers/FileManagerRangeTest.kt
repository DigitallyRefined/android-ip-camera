package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class FileManagerRangeTest {
    private val fileSize = 10_000_000L

    private fun partial(header: String?, size: Long = fileSize): ByteRange {
        val result = parseRangeHeader(header, size)
        assertEquals("expected a partial response for $header", RangeRequest.Partial::class, result::class)
        return (result as RangeRequest.Partial).range
    }

    @Test
    fun `no range header serves the whole file`() {
        assertEquals(RangeRequest.Full, parseRangeHeader(null, fileSize))
        assertEquals(RangeRequest.Full, parseRangeHeader("", fileSize))
    }

    @Test
    fun `open-ended range extends to the end of the file`() {
        val range = partial("bytes=1234-")
        assertEquals(1234L, range.start)
        assertEquals(fileSize - 1, range.endInclusive)
        assertEquals(fileSize - 1234, range.length)
    }

    @Test
    fun `closed range is clamped to the file size`() {
        val range = partial("bytes=0-999")
        assertEquals(0L, range.start)
        assertEquals(999L, range.endInclusive)
        assertEquals(1000L, range.length)

        val clamped = partial("bytes=9000-999999999")
        assertEquals(9000L, clamped.start)
        assertEquals(fileSize - 1, clamped.endInclusive)
    }

    @Test
    fun `single byte probe is supported`() {
        val range = partial("bytes=0-0")
        assertEquals(0L, range.start)
        assertEquals(0L, range.endInclusive)
        assertEquals(1L, range.length)
    }

    @Test
    fun `suffix range returns the last bytes`() {
        val range = partial("bytes=-500")
        assertEquals(fileSize - 500, range.start)
        assertEquals(fileSize - 1, range.endInclusive)

        val whole = partial("bytes=-20000000")
        assertEquals(0L, whole.start)
        assertEquals(fileSize - 1, whole.endInclusive)
    }

    @Test
    fun `range outside the file is unsatisfiable`() {
        assertEquals(RangeRequest.Unsatisfiable, parseRangeHeader("bytes=10000000-", fileSize))
        assertEquals(RangeRequest.Unsatisfiable, parseRangeHeader("bytes=0-", 0))
        assertEquals(RangeRequest.Unsatisfiable, parseRangeHeader("bytes=-0", fileSize))
    }

    @Test
    fun `malformed and multi-range headers fall back to the whole file`() {
        assertEquals(RangeRequest.Full, parseRangeHeader("bytes=abc-def", fileSize))
        assertEquals(RangeRequest.Full, parseRangeHeader("items=0-5", fileSize))
        assertEquals(RangeRequest.Full, parseRangeHeader("bytes=0-5,10-20", fileSize))
        assertEquals(RangeRequest.Full, parseRangeHeader("bytes=500-100", fileSize))
    }
}

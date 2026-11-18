package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintWriter

class HttpResponseWriterTest {
    private fun write(
        status: String,
        contentType: String?,
        body: String,
        extraHeaders: String = ""
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = PrintWriter(out, true)
        writeFixedResponse(writer, out, status, contentType, body, extraHeaders)
        return out.toByteArray()
    }

    private fun split(wire: ByteArray): Pair<String, String> {
        val separator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val index = wire.indexOfSubArray(separator)
        require(index >= 0) { "response has no header/body separator" }
        val headers = String(wire, 0, index, Charsets.ISO_8859_1)
        val body = String(wire, index + separator.size, wire.size - index - separator.size, Charsets.UTF_8)
        return headers to body
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Test
    fun `frames the body with its utf8 byte length`() {
        val body = "{\"name\":\"café\"}"
        val (headers, wireBody) = split(write("200 OK", "application/json", body))
        assertEquals(body, wireBody)
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headers.contains("Content-Type: application/json\r\n"))
        assertTrue(headers.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n"))
    }

    @Test
    fun `keeps extra headers and omits content type when null`() {
        val (headers, wireBody) = split(
            write("405 Method Not Allowed", null, "", "Allow: GET\r\n")
        )
        assertFalse(headers.contains("Content-Type:"))
        assertTrue(headers.contains("Content-Length: 0\r\n"))
        assertTrue(headers.contains("Allow: GET\r\n"))
        assertEquals("", wireBody)
    }
}

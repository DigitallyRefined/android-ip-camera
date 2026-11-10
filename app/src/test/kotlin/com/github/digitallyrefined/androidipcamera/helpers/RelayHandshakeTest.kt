package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHandshakeTest {
    @Test
    fun `device not registered is terminal for 401 and 403`() {
        assertTrue(RelayHandshake.isTerminalRejection(401, "device not registered"))
        assertTrue(RelayHandshake.isTerminalRejection(403, "Device Not Registered."))
        assertTrue(RelayHandshake.isTerminalRejection(403, "device_not_registered"))
        assertTrue(RelayHandshake.isTerminalRejection(403, """{"error":"device not registered"}"""))
    }

    @Test
    fun `WAF and generic errors stay retryable`() {
        assertFalse(RelayHandshake.isTerminalRejection(403, "Access denied"))
        assertFalse(RelayHandshake.isTerminalRejection(403, "Just a moment..."))
        assertFalse(RelayHandshake.isTerminalRejection(401, "Unauthorized"))
        assertFalse(RelayHandshake.isTerminalRejection(403, null))
        assertFalse(RelayHandshake.isTerminalRejection(403, "   "))
    }

    @Test
    fun `other statuses are never terminal`() {
        assertFalse(RelayHandshake.isTerminalRejection(101, "device not registered"))
        assertFalse(RelayHandshake.isTerminalRejection(500, "device not registered"))
        assertFalse(RelayHandshake.isTerminalRejection(503, "device not registered"))
    }

    @Test
    fun `already unregistered responses are recognised`() {
        assertTrue(RelayHandshake.isAlreadyUnregistered(404, """{"error":"Unknown device"}"""))
        assertTrue(RelayHandshake.isAlreadyUnregistered(401, "device not registered"))
        assertTrue(RelayHandshake.isAlreadyUnregistered(403, "Device Not Registered."))
    }

    @Test
    fun `generic 404s remain retryable`() {
        assertFalse(RelayHandshake.isAlreadyUnregistered(404, """{"error":"Not found"}"""))
        assertFalse(RelayHandshake.isAlreadyUnregistered(404, null))
        assertFalse(RelayHandshake.isAlreadyUnregistered(404, "<html>Not Found</html>"))
        assertFalse(RelayHandshake.isAlreadyUnregistered(500, "unknown device"))
    }

    @Test
    fun `status code is parsed from the response line`() {
        assertEquals(403, RelayHandshake.parseStatusCode("HTTP/1.1 403 Forbidden"))
        assertEquals(401, RelayHandshake.parseStatusCode("HTTP/1.1 401 Unauthorized"))
        assertEquals(0, RelayHandshake.parseStatusCode("not a status line"))
    }

    @Test
    fun `rejection message includes the relay error body`() {
        assertEquals(
            "Relay rejected this camera (HTTP 401: device not registered)",
            RelayHandshake.rejectionMessage(401, "device not registered\n"),
        )
    }

    @Test
    fun `rejection message falls back to the status code`() {
        assertEquals(
            "Relay rejected this camera (HTTP 403)",
            RelayHandshake.rejectionMessage(403, null),
        )
        assertEquals(
            "Relay rejected this camera (HTTP 403)",
            RelayHandshake.rejectionMessage(403, "   "),
        )
    }

    @Test
    fun `summary collapses whitespace and truncates long bodies`() {
        assertEquals(
            "device not registered",
            RelayHandshake.summarize("  device\nnot\tregistered  "),
        )

        val long = "x".repeat(500)
        val summary = RelayHandshake.summarize(long)
        assertNotNull(summary)
        assertEquals(201, summary!!.length)
        assertTrue(summary.endsWith("…"))
    }
}

package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAuthTest {
    private val credentials = CameraAuth.Credentials("admin", "Passw0rd!")

    @Test
    fun `disabled authentication allows passwordless connections`() {
        val result = CameraAuth.verify(CameraAuth.Mode.DISABLED, null, "", "")
        assertTrue(result.ok)
        assertEquals("auth-disabled", result.reason)
    }

    @Test
    fun `missing stored credentials rejects connections`() {
        val result = CameraAuth.verify(CameraAuth.Mode.NOT_CONFIGURED, null, "", "")
        assertFalse(result.ok)
        assertEquals(403, result.status)
        assertEquals("not-configured", result.reason)
    }

    @Test
    fun `missing provided credentials are rejected`() {
        val result = CameraAuth.verify(CameraAuth.Mode.REQUIRED, credentials, "", "")
        assertFalse(result.ok)
        assertEquals(401, result.status)
        assertEquals("missing-credentials", result.reason)
    }

    @Test
    fun `missing password is rejected as missing credentials`() {
        val result = CameraAuth.verify(
            CameraAuth.Mode.REQUIRED,
            credentials,
            "admin",
            "",
        )
        assertFalse(result.ok)
        assertEquals(401, result.status)
        assertEquals("missing-credentials", result.reason)
    }

    @Test
    fun `wrong credentials are rejected`() {
        val result = CameraAuth.verify(
            CameraAuth.Mode.REQUIRED,
            credentials,
            "admin",
            "wrong",
        )
        assertFalse(result.ok)
        assertEquals(401, result.status)
        assertEquals("invalid-credentials", result.reason)
    }

    @Test
    fun `correct credentials are accepted`() {
        val result = CameraAuth.verify(
            CameraAuth.Mode.REQUIRED,
            credentials,
            "admin",
            "Passw0rd!",
        )
        assertTrue(result.ok)
        assertEquals(200, result.status)
        assertEquals("ok", result.reason)
    }
}

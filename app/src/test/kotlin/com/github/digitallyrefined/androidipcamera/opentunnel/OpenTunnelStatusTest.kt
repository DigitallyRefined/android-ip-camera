package com.github.digitallyrefined.androidipcamera.opentunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTunnelStatusTest {
    @Test
    fun parsesNativeStatus() {
        val status = OpenTunnelStatus.parse(
            """{"registered":true,"hasFiles":true,"state":"connected","url":"https://abc.opentunnel.xyz",""" +
                """"hostname":"abc.opentunnel.xyz","profile":"default","connections":2}"""
        )
        assertTrue(status.registered)
        assertTrue(status.hasFiles)
        assertTrue(status.connected)
        assertEquals("https://abc.opentunnel.xyz", status.url)
        assertEquals(2, status.connections)
    }

    @Test
    fun describesProvisioningStages() {
        val status = OpenTunnelStatus(
            registered = false,
            hasFiles = true,
            state = OpenTunnelStatus.STATE_PROVISIONING,
            stage = "waiting_certificate"
        )
        assertEquals("OpenTunnel: waiting for certificate…", status.progressText())
    }

    @Test
    fun showsTheSetupHintUntilTapped() {
        assertEquals(
            "Tap to setup optional remote access",
            OpenTunnelStatus(registered = false).progressText()
        )
    }

    @Test
    fun showsConnectionErrors() {
        val status = OpenTunnelStatus(
            registered = true,
            hasFiles = true,
            state = OpenTunnelStatus.STATE_STOPPED,
            error = "bridge closed"
        )
        assertEquals("OpenTunnel: bridge closed", status.progressText())
    }
}

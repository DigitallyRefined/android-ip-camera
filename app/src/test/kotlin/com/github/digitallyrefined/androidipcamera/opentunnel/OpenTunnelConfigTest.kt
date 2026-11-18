package com.github.digitallyrefined.androidipcamera.opentunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenTunnelConfigTest {
    @Test
    fun normalizesServerUrls() {
        assertEquals("https://opentunnel.xyz", OpenTunnelConfig.normalizeApiUrl("https://opentunnel.xyz/"))
        assertEquals("https://tunnel.example.com:8443", OpenTunnelConfig.normalizeApiUrl(" HTTPS://Tunnel.Example.com:8443/ "))
        assertEquals("http://127.0.0.1:8080", OpenTunnelConfig.normalizeApiUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun rejectsUnusableServerUrls() {
        assertNull(OpenTunnelConfig.normalizeApiUrl(""))
        assertNull(OpenTunnelConfig.normalizeApiUrl("opentunnel.xyz"))
        assertNull(OpenTunnelConfig.normalizeApiUrl("ftp://opentunnel.xyz"))
        assertNull(OpenTunnelConfig.normalizeApiUrl("https://opentunnel.xyz/base"))
    }
}

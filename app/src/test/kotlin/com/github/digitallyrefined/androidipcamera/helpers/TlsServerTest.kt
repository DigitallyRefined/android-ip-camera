package com.github.digitallyrefined.androidipcamera.helpers

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Verifies the shared TLS material is actually used by the RTSP and ONVIF listeners. */
class TlsServerTest {
    private val protocol = "TLSv1.2"

    private class Material(val config: TlsServerConfig, val certificate: X509Certificate)

    private fun material(): Material {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val subject = X500Name("CN=localhost")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            Date(now.time + 86_400_000L),
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("test", keyPair.private, "changeit".toCharArray(), arrayOf(certificate))
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "changeit".toCharArray())
        }.keyManagers
        val context = SSLContext.getInstance(protocol)
        context.init(keyManagers, null, null)
        return Material(TlsServerConfig(protocol, context.serverSocketFactory), certificate)
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        }
        return SSLContext.getInstance(protocol).apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    @Test
    fun `tls listener presents the configured certificate`() {
        val material = material()
        val listener = material.config.createServerSocket(0)
        val port = listener.localPort
        val failure = arrayOfNulls<Throwable>(1)
        val accepted = Thread {
            try {
                listener.accept().use { socket ->
                    socket.getInputStream().read()
                }
            } catch (e: Throwable) {
                failure[0] = e
            } finally {
                listener.close()
            }
        }.apply { isDaemon = true; start() }

        try {
            val client = trustAllContext().socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            client.use { socket ->
                socket.soTimeout = 5_000
                socket.startHandshake()
                val peer = socket.session.peerCertificates.first() as X509Certificate
                assertTrue("server presented a different certificate", peer.encoded.contentEquals(material.certificate.encoded))
                assertEquals(protocol, socket.session.protocol)
                socket.outputStream.write(1)
                socket.outputStream.flush()
            }
        } finally {
            accepted.join(5_000)
        }
        failure[0]?.let { throw AssertionError("TLS accept failed", it) }
    }

    @Test
    fun `rtsp server answers over tls`() {
        val material = material()
        val server = RtspServer(FakeRtspHost(), tls = { material.config })
        server.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.boundPort() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val port = server.boundPort() ?: throw AssertionError("RTSP server did not bind")
        try {
            val socket = trustAllContext().socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            socket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                assertEquals(protocol, it.session.protocol)
                it.outputStream.write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                it.outputStream.flush()
                val status = BufferedReader(InputStreamReader(it.inputStream, Charsets.US_ASCII)).readLine()
                assertEquals("RTSP/1.0 200 OK", status)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `onvif server serves https and advertises tls URLs`() {
        val material = material()
        val server = OnvifServer(
            port = 0,
            host = { "192.168.3.194" },
            info = { OnvifDeviceInfo("Google", "Pixel Tablet", "0.14.0", "SER123", "tangorpro", "a1b2c3d4e5f6", "Living Room Tablet") },
            stream = { OnvifStreamInfo("rtsp://192.168.3.194:8554/live", 1920, 1080, 30, 6000) },
            credentials = { null },
            snapshot = { byteArrayOf(1, 2, 3) },
            enableDiscovery = false,
            tls = { material.config }
        )
        server.start()
        try {
            val base = "https://192.168.3.194:${server.localPort}"
            val caps = server.handle("POST", "/onvif/device_service", emptyMap(), envelope("<tds:GetCapabilities/>"))
            assertEquals(caps.text, 200, caps.status)
            assertTrue(caps.text.contains("$base/onvif/device_service"))
            assertTrue(caps.text.contains("$base/onvif/media_service"))
            assertTrue(caps.text.contains("<tt:TLS1.2>true</tt:TLS1.2>"))

            val stream = server.handle("POST", "/onvif/media_service", emptyMap(), envelope(
                "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                    "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                    "<trt:ProfileToken>main</trt:ProfileToken></trt:GetStreamUri>"
            ))
            assertEquals(stream.text, 200, stream.status)
            assertTrue(stream.text.contains("rtsps://192.168.3.194:8554/live"))

            val snapshot = server.handle("POST", "/onvif/media_service", emptyMap(), envelope(
                "<trt:GetSnapshotUri><trt:ProfileToken>main</trt:ProfileToken></trt:GetSnapshotUri>"
            ))
            assertTrue(snapshot.text.contains("$base/onvif/snapshot.jpg"))

            // End-to-end: the listener really speaks TLS.
            val connection = URL("https://127.0.0.1:${server.localPort}/onvif/snapshot.jpg").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = trustAllContext().socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("image/jpeg", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(byteArrayOf(1, 2, 3)))
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    private fun envelope(body: String) =
        ("<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:tds=\"${OnvifNs.TDS}\" xmlns:trt=\"${OnvifNs.TRT}\" " +
            "xmlns:tt=\"${OnvifNs.TT}\"><s:Header/><s:Body>$body</s:Body></s:Envelope>").toByteArray()

    private class FakeRtspHost : RtspServer.Host {
        override fun port() = 0
        override fun credentials(): RtspCredentials = RtspCredentials.Disabled
        override fun isStreamingEnabled() = true
        override fun audioFormat(): AacFormat? = null
        override fun createAudioSource(onAccessUnit: (ByteArray, Long) -> Unit): RtspAudioSource? = null
        override fun onClientConnected() {}
        override fun onClientDisconnected() {}
        override fun requestKeyFrame(stream: RtspStream) {}
        override fun log(message: String) {}
    }
}

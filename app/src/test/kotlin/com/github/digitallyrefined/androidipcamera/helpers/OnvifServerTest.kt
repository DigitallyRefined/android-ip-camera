package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OnvifServerTest {
    private val user = "admin"
    private val pass = "secret"
    private var server: OnvifServer? = null

    private fun newServer(withCreds: Boolean = true, snapshot: (() -> ByteArray?)? = { byteArrayOf(1, 2, 3) }) = OnvifServer(
        port = 0,
        host = { "192.168.3.194" },
        info = { OnvifDeviceInfo("Google", "Pixel Tablet", "0.14.0", "SER123", "tangorpro", "a1b2c3d4e5f6", "Living Room Tablet") },
        stream = { OnvifStreamInfo("rtsp://192.168.3.194:8554/live", 1920, 1080, 30, 6000) },
        credentials = { if (withCreds) user to pass else null },
        snapshot = snapshot,
        enableDiscovery = false
    ).also { server = it }

    @After fun tearDown() { server?.stop() }

    private fun usernameToken(password: String = pass): String {
        val nonce = ByteArray(16) { it.toByte() }
        val created = "2026-10-19T12:00:00Z"
        val digest = OnvifXml.passwordDigest(nonce, created, password)
        return "<wsse:Security xmlns:wsse=\"${OnvifNs.WSSE}\" xmlns:wsu=\"${OnvifNs.WSU}\"><wsse:UsernameToken>" +
            "<wsse:Username>$user</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</wsse:Password>" +
            "<wsse:Nonce>${OnvifBase64.encode(nonce)}</wsse:Nonce><wsu:Created>$created</wsu:Created>" +
            "</wsse:UsernameToken></wsse:Security>"
    }

    private fun soap(body: String, header: String = usernameToken()) =
        "<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:tds=\"${OnvifNs.TDS}\" xmlns:trt=\"${OnvifNs.TRT}\" xmlns:tr2=\"${OnvifNs.TR2}\" " +
            "xmlns:tev=\"${OnvifNs.TEV}\" xmlns:wsnt=\"${OnvifNs.WSNT}\" xmlns:tt=\"${OnvifNs.TT}\" xmlns:wsa=\"${OnvifNs.WSA}\">" +
            "<s:Header>$header</s:Header><s:Body>$body</s:Body></s:Envelope>"

    private fun call(s: OnvifServer, body: String, path: String = "/onvif/device_service", header: String = usernameToken()) =
        s.handle("POST", path, emptyMap(), soap(body, header).toByteArray())

    private fun parseOk(r: OnvifServer.HttpResponse) = OnvifXml.parse(r.text).also {
        assertEquals(r.text, 200, r.status)
        assertNotNull("response must be well-formed XML: ${r.text}", it)
    }!!

    @Test fun `password digest matches the WS-Security spec example`() {
        // Example from the WS-Security UsernameToken profile style: deterministic inputs.
        val nonce = OnvifBase64.decode("LKqI6G/AikKCQrN0zqZFlg==")!!
        val d = OnvifXml.passwordDigest(nonce, "2010-09-16T07:50:45Z", "userpassword")
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", d)
    }

    @Test fun `base64 round trips`() {
        for (n in 0..20) {
            val b = ByteArray(n) { (it * 37).toByte() }
            assertTrue(b.contentEquals(OnvifBase64.decode(OnvifBase64.encode(b))))
        }
        assertEquals("aGVsbG8=", OnvifBase64.encode("hello".toByteArray()))
    }

    @Test fun `durations and datetimes parse`() {
        assertEquals(60_000L, OnvifXml.parseDuration("PT60S"))
        assertEquals(600_000L, OnvifXml.parseDuration("PT10M"))
        assertEquals(500L, OnvifXml.parseDuration("PT0.5S"))
        assertEquals(86_400_000L + 3_600_000L, OnvifXml.parseDuration("P1DT1H"))
        assertEquals(0L, OnvifXml.parseDateTime("1970-01-01T00:00:00Z"))
        assertEquals(1500L, OnvifXml.parseDateTime("1970-01-01T00:00:01.5Z"))
        assertEquals(-3_600_000L, OnvifXml.parseDateTime("1970-01-01T00:00:00+01:00"))
    }

    @Test fun `username token digest and text are verified, wrong password rejected`() {
        val s = newServer()
        assertEquals(200, call(s, "<tds:GetDeviceInformation/>").status)
        val bad = call(s, "<tds:GetDeviceInformation/>", header = usernameToken("nope"))
        assertEquals(400, bad.status)
        assertTrue(bad.text.contains("ter:NotAuthorized"))
        val text = "<wsse:Security xmlns:wsse=\"${OnvifNs.WSSE}\"><wsse:UsernameToken><wsse:Username>$user</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText\">$pass</wsse:Password></wsse:UsernameToken></wsse:Security>"
        assertEquals(200, call(s, "<tds:GetDeviceInformation/>", header = text).status)
    }

    @Test fun `missing credentials gives 401 with digest challenge, but date and time is open`() {
        val s = newServer()
        val r = call(s, "<tds:GetDeviceInformation/>", header = "")
        assertEquals(401, r.status)
        assertTrue(r.headers.any { it.first == "WWW-Authenticate" && it.second.startsWith("Digest") })
        val dt = parseOk(call(s, "<tds:GetSystemDateAndTime/>", header = ""))
        assertNotNull(OnvifXml.find(dt.documentElement, "UTCDateTime"))
        assertEquals(200, call(s, "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>", header = "").status)
    }

    @Test fun `http digest auth is accepted`() {
        val s = newServer()
        val challenge = call(s, "<tds:GetDeviceInformation/>", header = "").headers.first { it.second.startsWith("Digest") }.second
        val nonce = Regex("nonce=\"([^\"]+)\"").find(challenge)!!.groupValues[1]
        val uri = "/onvif/device_service"
        val ha1 = OnvifXml.md5Hex("$user:AndroidIPCamera:$pass")
        val ha2 = OnvifXml.md5Hex("POST:$uri")
        val resp = OnvifXml.md5Hex("$ha1:$nonce:00000001:abc:auth:$ha2")
        val auth = "Digest username=\"$user\", realm=\"AndroidIPCamera\", nonce=\"$nonce\", uri=\"$uri\", qop=auth, nc=00000001, cnonce=\"abc\", response=\"$resp\""
        val r = s.handle("POST", uri, mapOf("authorization" to auth), soap("<tds:GetDeviceInformation/>", "").toByteArray())
        assertEquals(r.text, 200, r.status)
    }

    @Test fun `device information and network interface carry identity`() {
        val s = newServer()
        val di = parseOk(call(s, "<tds:GetDeviceInformation/>")).documentElement
        assertEquals("Google", OnvifXml.text(di, "Manufacturer"))
        assertEquals("SER123", OnvifXml.text(di, "SerialNumber"))
        val ni = parseOk(call(s, "<tds:GetNetworkInterfaces/>")).documentElement
        assertEquals("a1:b2:c3:d4:e5:f6", OnvifXml.text(ni, "HwAddress"))
        val caps = parseOk(call(s, "<tds:GetCapabilities/>")).documentElement
        assertEquals("true", OnvifXml.text(OnvifXml.find(caps, "Events"), "WSPullPointSupport"))
        assertTrue(OnvifXml.text(OnvifXml.find(caps, "Media"), "XAddr")!!.endsWith("/onvif/media_service"))
        val services = parseOk(call(s, "<tds:GetServices><tds:IncludeCapability>true</tds:IncludeCapability></tds:GetServices>")).documentElement
        assertTrue(services.getElementsByTagNameNS(OnvifNs.TDS, "Service").length >= 4)
    }

    @Test fun `media profiles and stream uri`() {
        val s = newServer()
        val profiles = parseOk(call(s, "<trt:GetProfiles/>", "/onvif/media_service")).documentElement
        val list = profiles.getElementsByTagNameNS(OnvifNs.TRT, "Profiles")
        assertEquals(2, list.length)
        assertEquals("main", (list.item(0) as org.w3c.dom.Element).getAttribute("token"))
        assertEquals("H264", OnvifXml.text(profiles, "Encoding"))
        assertEquals("1920", OnvifXml.text(OnvifXml.find(profiles, "Resolution"), "Width"))
        assertEquals("vs0", OnvifXml.text(profiles, "SourceToken"))
        val uri = parseOk(call(s, "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>sub</trt:ProfileToken></trt:GetStreamUri>", "/onvif/media_service"))
        assertEquals("rtsp://192.168.3.194:8554/live", OnvifXml.text(uri.documentElement, "Uri"))
        val snap = parseOk(call(s, "<trt:GetSnapshotUri><trt:ProfileToken>main</trt:ProfileToken></trt:GetSnapshotUri>", "/onvif/media_service"))
        assertTrue(OnvifXml.text(snap.documentElement, "Uri")!!.endsWith("/onvif/snapshot.jpg"))
        val m2 = parseOk(call(s, "<tr2:GetStreamUri><tr2:Protocol>RTSP</tr2:Protocol><tr2:ProfileToken>main</tr2:ProfileToken></tr2:GetStreamUri>", "/onvif/media2_service"))
        assertEquals("rtsp://192.168.3.194:8554/live", OnvifXml.text(m2.documentElement, "Uri"))
        val m2p = parseOk(call(s, "<tr2:GetProfiles><tr2:Type>All</tr2:Type></tr2:GetProfiles>", "/onvif/media2_service")).documentElement
        assertEquals(2, m2p.getElementsByTagNameNS(OnvifNs.TR2, "Profiles").length)
    }

    @Test fun `unknown action is an ActionNotSupported fault`() {
        val s = newServer()
        val r = call(s, "<tds:SystemReboot/>")
        assertEquals(400, r.status)
        val doc = OnvifXml.parse(r.text)!!
        assertNotNull(OnvifXml.find(doc.documentElement, "Fault"))
        assertTrue(r.text.contains("ter:ActionNotSupported"))
    }

    @Test fun `pull point flow delivers initial state then motion`() {
        val s = newServer()
        val created = parseOk(call(s, "<tev:CreatePullPointSubscription><tev:InitialTerminationTime>PT60S</tev:InitialTerminationTime></tev:CreatePullPointSubscription>", "/onvif/events_service")).documentElement
        val addr = OnvifXml.text(created, "Address")!!
        val path = addr.substringAfter("192.168.3.194:${s.localPort}")
        assertTrue(path.startsWith("/onvif/pullpoint/"))

        val pull = "<tev:PullMessages><tev:Timeout>PT2S</tev:Timeout><tev:MessageLimit>10</tev:MessageLimit></tev:PullMessages>"
        val first = parseOk(call(s, pull, path)).documentElement
        val init = first.getElementsByTagNameNS(OnvifNs.WSNT, "NotificationMessage")
        assertEquals(2, init.length)
        assertTrue(first.textContent.contains("tns1:RuleEngine/CellMotionDetector/Motion"))
        assertTrue(OnvifXml.parse(call(s, "<tev:GetEventProperties/>", "/onvif/events_service").text) != null)

        // Long-poll: motion arrives while PullMessages is waiting.
        Thread { Thread.sleep(200); s.setMotion(true) }.start()
        val t0 = System.currentTimeMillis()
        val r = call(s, "<tev:PullMessages><tev:Timeout>PT5S</tev:Timeout><tev:MessageLimit>10</tev:MessageLimit></tev:PullMessages>", path)
        assertTrue("should return as soon as motion is published", System.currentTimeMillis() - t0 < 4000)
        val doc = parseOk(r).documentElement
        val items = doc.getElementsByTagNameNS(OnvifNs.TT, "SimpleItem")
        val isMotion = (0 until items.length).map { items.item(it) as org.w3c.dom.Element }.first { it.getAttribute("Name") == "IsMotion" }
        assertEquals("true", isMotion.getAttribute("Value"))
        assertTrue(r.text.contains("PropertyOperation=\"Changed\""))
        assertTrue(r.text.contains("tns1:VideoSource/MotionAlarm"))

        // Sync point re-sends current state; renew + unsubscribe work.
        assertEquals(200, call(s, "<tev:SetSynchronizationPoint/>", path).status)
        assertTrue(call(s, pull, path).text.contains("Initialized"))
        assertEquals(200, call(s, "<wsnt:Renew><wsnt:TerminationTime>PT120S</wsnt:TerminationTime></wsnt:Renew>", path).status)
        assertEquals(200, call(s, "<wsnt:Unsubscribe/>", path).status)
        assertEquals(400, call(s, pull, path).status)
    }

    @Test fun `no credentials configured means open access`() {
        val s = newServer(withCreds = false)
        assertEquals(200, call(s, "<trt:GetProfiles/>", "/onvif/media_service", header = "").status)
    }

    @Test fun `http server serves SOAP and snapshot over sockets`() {
        val s = newServer(withCreds = false, snapshot = { byteArrayOf(9, 8, 7) })
        s.start()
        val c = URL("http://127.0.0.1:${s.localPort}/onvif/device_service").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/soap+xml")
        c.outputStream.use { it.write(soap("<tds:GetDeviceInformation/>", "").toByteArray()) }
        assertEquals(200, c.responseCode)
        assertTrue(c.inputStream.readBytes().toString(Charsets.UTF_8).contains("Pixel Tablet"))
        val snap = URL("http://127.0.0.1:${s.localPort}/onvif/snapshot.jpg").openConnection() as HttpURLConnection
        assertEquals(200, snap.responseCode)
        assertEquals("image/jpeg", snap.contentType)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(snap.inputStream.readBytes()))
    }

    // ---------------- WS-Discovery ----------------

    private val discovery = OnvifDiscovery(
        endpointUuid = { OnvifDiscovery.uuidFor("a1b2c3d4e5f6") },
        xaddr = { "http://192.168.3.194:8000/onvif/device_service" },
        scopes = { OnvifDiscovery.defaultScopes("Living Room Tablet", "Pixel Tablet") }
    )

    private fun probe(types: String?, wsd: String = OnvifDiscovery.WSD_2005, wsa: String = OnvifDiscovery.WSA_2004, id: String = "urn:uuid:${UUID.randomUUID()}") =
        "<?xml version=\"1.0\"?><e:Envelope xmlns:e=\"${OnvifNs.SOAP}\" xmlns:w=\"$wsa\" xmlns:d=\"$wsd\" xmlns:dn=\"${OnvifDiscovery.DN}\" xmlns:tds=\"${OnvifNs.TDS}\">" +
            "<e:Header><w:MessageID>$id</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>$wsd/Probe</w:Action></e:Header>" +
            "<e:Body><d:Probe>${if (types != null) "<d:Types>$types</d:Types>" else ""}</d:Probe></e:Body></e:Envelope>"

    @Test fun `probe for NetworkVideoTransmitter gets a ProbeMatch with xaddrs and scopes`() {
        val id = "urn:uuid:11111111-2222-3333-4444-555555555555"
        val reply = discovery.buildProbeMatch(probe("dn:NetworkVideoTransmitter", id = id))
        assertNotNull(reply)
        val doc = OnvifXml.parse(reply!!)!!.documentElement
        assertEquals(id, OnvifXml.text(doc, "RelatesTo"))
        assertEquals("http://192.168.3.194:8000/onvif/device_service", OnvifXml.text(doc, "XAddrs"))
        assertTrue(OnvifXml.text(doc, "Scopes")!!.contains("onvif://www.onvif.org/Profile/Streaming"))
        assertTrue(OnvifXml.text(doc, "Scopes")!!.contains("onvif://www.onvif.org/name/Living%20Room%20Tablet"))
        assertTrue(OnvifXml.text(doc, "Types")!!.contains("NetworkVideoTransmitter"))
        assertEquals(OnvifDiscovery.WSD_2005, OnvifXml.find(doc, "ProbeMatches")!!.namespaceURI)
    }

    @Test fun `probe variants`() {
        assertNotNull(discovery.buildProbeMatch(probe(null)))
        assertNotNull(discovery.buildProbeMatch(probe("tds:Device")))
        assertNull(discovery.buildProbeMatch(probe("tdn:Printer")))
        assertNull(discovery.buildProbeMatch("not xml"))
        val r2009 = discovery.buildProbeMatch(probe("dn:NetworkVideoTransmitter", OnvifDiscovery.WSD_2009, OnvifDiscovery.WSA_2005))!!
        assertEquals(OnvifDiscovery.WSD_2009, OnvifXml.find(OnvifXml.parse(r2009)!!.documentElement, "ProbeMatches")!!.namespaceURI)
        assertFalse(discovery.buildAnnounce(false).isEmpty())
        assertNotNull(OnvifXml.parse(discovery.buildAnnounce(true)))
    }
}

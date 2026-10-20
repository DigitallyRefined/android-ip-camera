package com.github.digitallyrefined.androidipcamera.helpers

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class OnvifDeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serial: String,
    val hardwareId: String,
    val mac: String,
    val name: String
)

data class OnvifStreamInfo(
    val rtspUrl: String,   // no credentials embedded
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
)

/**
 * Minimal ONVIF Profile S (+ PullPoint events) device over plain HTTP/SOAP 1.2, plus WS-Discovery.
 * Targets Home Assistant's ONVIF integration and UniFi Protect third-party adoption.
 * Pure JVM; the Android caller must hold a WifiManager.MulticastLock for discovery.
 */
class OnvifServer(
    private val port: Int = 8000,
    private val host: () -> String,
    private val info: () -> OnvifDeviceInfo,
    private val stream: () -> OnvifStreamInfo,
    private val credentials: () -> Pair<String, String>?,
    private val snapshot: (() -> ByteArray?)? = null,
    private val onLog: (String) -> Unit = {},
    private val discoveryPort: Int = 3702,
    private val enableDiscovery: Boolean = true
) {
    companion object {
        private const val REALM = "AndroidIPCamera"
        private const val MAX_BODY = 1 shl 20
        private const val MAX_SUBSCRIPTIONS = 16
        private const val MAX_QUEUED = 200
        private const val DEFAULT_SUB_MS = 10 * 60_000L
        private const val MAX_PULL_WAIT_MS = 60_000L
        private const val TOPIC_DIALECT = "http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet"
        private const val TOPIC_CELL = "tns1:RuleEngine/CellMotionDetector/Motion"
        private const val TOPIC_ALARM = "tns1:VideoSource/MotionAlarm"

        /** Operations ONVIF allows without credentials (plus GetCapabilities for Protect probing). */
        private val UNAUTHENTICATED = setOf(
            "GetSystemDateAndTime", "GetServices", "GetServiceCapabilities", "GetCapabilities",
            "GetWsdlUrl", "GetEndpointReference"
        )
    }

    internal data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/soap+xml; charset=utf-8",
        val headers: List<Pair<String, String>> = emptyList()
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private class Subscription(val id: String, @Volatile var expiresAt: Long) {
        val queue = ArrayDeque<String>()
        val lock = Object()
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var stopped = false
    private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private var discovery: OnvifDiscovery? = null
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val issuedNonces = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    @Volatile private var motion = false
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    /** Actual bound TCP port (useful when constructed with port 0). */
    val localPort: Int get() = serverSocket?.localPort ?: port

    // ===================================================================== lifecycle

    @Synchronized
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 50)
        serverSocket = ss
        running = true
        stopped = false
        pool = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "OnvifHttp").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop(ss) }, "OnvifAccept").apply { isDaemon = true; start() }
        onLog("ONVIF server listening on tcp/${ss.localPort}")
        if (enableDiscovery) {
            discovery = OnvifDiscovery(
                endpointUuid = { endpointUuid() },
                xaddr = { "${baseUrl()}/onvif/device_service" },
                scopes = { scopes() },
                port = discoveryPort,
                onLog = onLog
            ).also { it.start() }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stopped = true
        discovery?.stop(); discovery = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        openSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openSockets.clear()
        subscriptions.values.forEach { s -> synchronized(s.lock) { s.lock.notifyAll() } }
        subscriptions.clear()
        pool?.shutdownNow(); pool = null
        try { acceptThread?.join(1000) } catch (_: Exception) {}
        acceptThread = null
    }

    /** Publish motion state to every PullPoint subscriber (both CellMotionDetector and MotionAlarm topics). */
    fun setMotion(active: Boolean) {
        if (motion == active) return
        motion = active
        val now = System.currentTimeMillis()
        for (s in subscriptions.values) enqueue(s, motionMessages(active, "Changed", now))
    }

    // ===================================================================== HTTP

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try { ss.accept() } catch (_: Exception) { if (running) continue else break }
            try {
                pool?.execute { serve(sock) } ?: sock.close()
            } catch (_: RejectedExecutionException) {
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private val remoteHost = ThreadLocal<String>()

    private fun serve(sock: Socket) {
        remoteHost.set(sock.inetAddress?.hostAddress ?: "?")
        openSockets += sock
        try {
            sock.soTimeout = 30_000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            while (running) {
                val req = try { readRequest(input) } catch (_: SocketTimeoutException) { null } ?: break
                val resp = try {
                    handle(req.method, req.path, req.headers, req.body)
                } catch (e: Exception) {
                    onLog("ONVIF handler error: ${e.message}")
                    HttpResponse(500, OnvifXml.fault("ter:Action", "Internal error", sender = false).toByteArray())
                }
                val keepAlive = !req.headers["connection"].equals("close", true) && req.version != "HTTP/1.0"
                writeResponse(out, resp, keepAlive)
                if (!keepAlive) break
            }
        } catch (_: Exception) {
        } finally {
            openSockets -= sock
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private class Req(val method: String, val path: String, val version: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IllegalStateException("header line too long")
        }
    }

    private fun readRequest(input: InputStream): Req? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IllegalStateException("body too large")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return Req(parts[0].uppercase(), parts[1], parts.getOrElse(2) { "HTTP/1.1" }, headers, body)
    }

    private fun writeResponse(out: OutputStream, r: HttpResponse, keepAlive: Boolean) {
        val reason = when (r.status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "Internal Server Error" }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\n")
        sb.append("Content-Type: ${r.contentType}\r\n")
        sb.append("Content-Length: ${r.body.size}\r\n")
        sb.append("Server: AndroidIPCamera-ONVIF\r\n")
        sb.append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
        r.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    // ===================================================================== dispatch

    internal fun handle(method: String, rawPath: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        val path = rawPath.substringBefore('?')
        if (path == "/onvif/snapshot.jpg" || path == "/snapshot.jpg") {
            if (method != "GET" && method != "HEAD") return HttpResponse(405, ByteArray(0), "text/plain")
            if (!httpAuthOk(method, rawPath, headers)) return unauthorized("text/plain", "Unauthorized".toByteArray())
            val jpeg = try { snapshot?.invoke() } catch (_: Exception) { null }
                ?: return HttpResponse(503, "No snapshot available".toByteArray(), "text/plain")
            return HttpResponse(200, if (method == "HEAD") ByteArray(0) else jpeg, "image/jpeg", listOf("Cache-Control" to "no-store"))
        }
        if (method != "POST") {
            return HttpResponse(if (path.startsWith("/onvif")) 405 else 404, ByteArray(0), "text/plain")
        }
        val doc = OnvifXml.parse(String(body, Charsets.UTF_8))
            ?: return soapFault("ter:WellFormed", "Malformed SOAP request", 400)
        val env = doc.documentElement
        val header = OnvifXml.child(env, "Header")
        val bodyEl = OnvifXml.child(env, "Body") ?: return soapFault("ter:WellFormed", "Missing SOAP Body", 400)
        val op = OnvifXml.children(bodyEl).firstOrNull() ?: return soapFault("ter:WellFormed", "Empty SOAP Body", 400)
        val action = OnvifXml.localName(op)
        val ns = op.namespaceURI.orEmpty()
        val who = remoteHost.get() ?: "?"
        if (action != "PullMessages") onLog("ONVIF $action from $who")

        if (action !in UNAUTHENTICATED) {
            when (checkAuth(header, method, rawPath, headers)) {
                AuthResult.OK -> {}
                AuthResult.MISSING -> return unauthorized(
                    "application/soap+xml; charset=utf-8",
                    OnvifXml.fault("ter:NotAuthorized", "Sender not authorized").toByteArray()
                )
                AuthResult.BAD -> return soapFault("ter:NotAuthorized", "Sender not authorized", 400)
            }
        }

        val result: String? = when (ns) {
            OnvifNs.TDS -> device(action, op)
            OnvifNs.TRT -> media(action, op)
            OnvifNs.TR2 -> media2(action, op)
            OnvifNs.TEV -> events(action, op, path, header)
            OnvifNs.WSNT -> notification(action, op, path, header)
            OnvifNs.TIMG -> imaging(action)
            else -> null
        }
        if (result != null && result.startsWith("FAULT:")) {
            val (code, msg) = result.removePrefix("FAULT:").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return soapFault(code, msg, 400)
        }
        if (result == null) onLog("ONVIF unsupported action $action ($ns) from $who")
        return if (result == null) soapFault("ter:ActionNotSupported", "Action $action not supported", 400)
        else HttpResponse(200, OnvifXml.envelope(result).toByteArray(Charsets.UTF_8))
    }

    private fun fault(code: String, msg: String) = "FAULT:$code|$msg"

    private fun soapFault(code: String, msg: String, status: Int) =
        HttpResponse(status, OnvifXml.fault(code, msg, sender = status < 500).toByteArray(Charsets.UTF_8))

    private fun unauthorized(ct: String, body: ByteArray): HttpResponse {
        val nonce = OnvifXml.randomHex()
        synchronized(issuedNonces) { issuedNonces[nonce] = System.currentTimeMillis() }
        return HttpResponse(401, body, ct, listOf(
            "WWW-Authenticate" to "Digest realm=\"$REALM\", qop=\"auth\", nonce=\"$nonce\", algorithm=MD5",
            "WWW-Authenticate" to "Basic realm=\"$REALM\""
        ))
    }

    // ===================================================================== auth

    internal enum class AuthResult { OK, MISSING, BAD }

    private fun creds(): Pair<String, String>? = credentials()?.takeIf { it.first.isNotBlank() }

    internal fun checkAuth(header: Element?, method: String, uri: String, headers: Map<String, String>): AuthResult {
        val c = creds() ?: return AuthResult.OK
        val token = OnvifXml.find(header, "UsernameToken")
        if (token != null) return if (verifyUsernameToken(token, c)) AuthResult.OK else AuthResult.BAD
        if (headers["authorization"] != null) return if (httpAuthOk(method, uri, headers)) AuthResult.OK else AuthResult.BAD
        return AuthResult.MISSING
    }

    internal fun verifyUsernameToken(token: Element, c: Pair<String, String>): Boolean {
        val user = OnvifXml.text(token, "Username") ?: return false
        val pwEl = OnvifXml.find(token, "Password") ?: return false
        val pw = pwEl.textContent?.trim().orEmpty()
        if (!OnvifXml.constantTimeEquals(user, c.first)) return false
        val type = pwEl.getAttribute("Type").orEmpty()
        return if (type.endsWith("#PasswordText")) {
            OnvifXml.constantTimeEquals(pw, c.second)
        } else {
            // Default/#PasswordDigest. Clock skew is common on cameras, so Created isn't range-checked.
            val nonce = OnvifXml.text(token, "Nonce")?.let { OnvifBase64.decode(it) } ?: return false
            val created = OnvifXml.text(token, "Created") ?: return false
            OnvifXml.constantTimeEquals(pw, OnvifXml.passwordDigest(nonce, created, c.second))
        }
    }

    internal fun httpAuthOk(method: String, uri: String, headers: Map<String, String>): Boolean {
        val c = creds() ?: return true
        val auth = headers["authorization"] ?: return false
        return when {
            auth.startsWith("Basic ", true) -> {
                val dec = OnvifBase64.decode(auth.substring(6).trim())?.toString(Charsets.UTF_8) ?: return false
                val i = dec.indexOf(':')
                i > 0 && OnvifXml.constantTimeEquals(dec.substring(0, i), c.first) &&
                    OnvifXml.constantTimeEquals(dec.substring(i + 1), c.second)
            }
            auth.startsWith("Digest ", true) -> {
                val p = parseAuthParams(auth.substring(7))
                if (p["username"] != c.first) return false
                val nonce = p["nonce"] ?: return false
                if (synchronized(issuedNonces) { !issuedNonces.containsKey(nonce) }) return false
                val realm = p["realm"] ?: REALM
                val ha1 = OnvifXml.md5Hex("${c.first}:$realm:${c.second}")
                val ha2 = OnvifXml.md5Hex("$method:${p["uri"] ?: uri}")
                val expected = if (p["qop"] != null)
                    OnvifXml.md5Hex("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2")
                else OnvifXml.md5Hex("$ha1:$nonce:$ha2")
                OnvifXml.constantTimeEquals(expected, p["response"].orEmpty())
            }
            else -> false
        }
    }

    private fun parseAuthParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^\s,]+))""").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return out
    }

    // ===================================================================== helpers

    private fun baseUrl() = "http://${host()}:$localPort"
    private fun endpointUuid() = info().let { OnvifDiscovery.uuidFor(it.mac.ifBlank { it.serial }) }
    private fun scopes() = info().let { OnvifDiscovery.defaultScopes(it.name, it.model) }
    private fun e(s: String?) = OnvifXml.esc(s)

    private fun rtspPort(): Int = try { URI(stream().rtspUrl).port.takeIf { it > 0 } ?: 554 } catch (_: Exception) { 554 }

    private fun macColon(): String {
        val hex = info().mac.filter { it.isLetterOrDigit() }.lowercase()
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else info().mac
    }

    private fun profileTokens() = listOf("main", "sub")

    private fun dateTimeXml(prefix: String, ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        return "<tt:$prefix><tt:Time><tt:Hour>${cal.get(java.util.Calendar.HOUR_OF_DAY)}</tt:Hour><tt:Minute>${cal.get(java.util.Calendar.MINUTE)}</tt:Minute><tt:Second>${cal.get(java.util.Calendar.SECOND)}</tt:Second></tt:Time>" +
            "<tt:Date><tt:Year>${cal.get(java.util.Calendar.YEAR)}</tt:Year><tt:Month>${cal.get(java.util.Calendar.MONTH) + 1}</tt:Month><tt:Day>${cal.get(java.util.Calendar.DAY_OF_MONTH)}</tt:Day></tt:Date></tt:$prefix>"
    }

    // ===================================================================== device service

    private fun device(action: String, op: Element): String? {
        val i = info()
        val b = baseUrl()
        return when (action) {
            "GetSystemDateAndTime" -> {
                val now = System.currentTimeMillis()
                "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>" +
                    "<tt:DateTimeType>NTP</tt:DateTimeType><tt:DaylightSavings>false</tt:DaylightSavings>" +
                    "<tt:TimeZone><tt:TZ>GMT0</tt:TZ></tt:TimeZone>" +
                    dateTimeXml("UTCDateTime", now) + dateTimeXml("LocalDateTime", now) +
                    "</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"
            }
            "GetDeviceInformation" -> "<tds:GetDeviceInformationResponse>" +
                "<tds:Manufacturer>${e(i.manufacturer)}</tds:Manufacturer><tds:Model>${e(i.model)}</tds:Model>" +
                "<tds:FirmwareVersion>${e(i.firmware)}</tds:FirmwareVersion><tds:SerialNumber>${e(i.serial)}</tds:SerialNumber>" +
                "<tds:HardwareId>${e(i.hardwareId)}</tds:HardwareId></tds:GetDeviceInformationResponse>"
            "GetCapabilities" -> "<tds:GetCapabilitiesResponse><tds:Capabilities>" +
                "<tt:Device><tt:XAddr>$b/onvif/device_service</tt:XAddr>" +
                "<tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration><tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS></tt:Network>" +
                "<tt:System><tt:DiscoveryResolve>false</tt:DiscoveryResolve><tt:DiscoveryBye>true</tt:DiscoveryBye><tt:RemoteDiscovery>false</tt:RemoteDiscovery><tt:SystemBackup>false</tt:SystemBackup><tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>" +
                "<tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>60</tt:Minor></tt:SupportedVersions></tt:System>" +
                "<tt:Security><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2><tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration><tt:AccessPolicyConfig>false</tt:AccessPolicyConfig><tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken><tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security>" +
                "</tt:Device>" +
                "<tt:Events><tt:XAddr>$b/onvif/events_service</tt:XAddr><tt:WSSubscriptionPolicySupport>false</tt:WSSubscriptionPolicySupport><tt:WSPullPointSupport>true</tt:WSPullPointSupport><tt:WSPausableSubscriptionManagerInterfaceSupport>false</tt:WSPausableSubscriptionManagerInterfaceSupport></tt:Events>" +
                "<tt:Imaging><tt:XAddr>$b/onvif/imaging_service</tt:XAddr></tt:Imaging>" +
                "<tt:Media><tt:XAddr>$b/onvif/media_service</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities>" +
                "<tt:Extension><tt:ProfileCapabilities><tt:MaximumNumberOfProfiles>2</tt:MaximumNumberOfProfiles></tt:ProfileCapabilities></tt:Extension></tt:Media>" +
                "</tds:Capabilities></tds:GetCapabilitiesResponse>"
            "GetServices" -> {
                val withCaps = OnvifXml.text(op, "IncludeCapability").equals("true", true)
                fun svc(ns: String, path: String, caps: String, major: Int = 2, minor: Int = 60) =
                    "<tds:Service><tds:Namespace>$ns</tds:Namespace><tds:XAddr>$b$path</tds:XAddr>" +
                        (if (withCaps) "<tds:Capabilities>$caps</tds:Capabilities>" else "") +
                        "<tds:Version><tt:Major>$major</tt:Major><tt:Minor>$minor</tt:Minor></tds:Version></tds:Service>"
                "<tds:GetServicesResponse>" +
                    svc(OnvifNs.TDS, "/onvif/device_service", deviceServiceCaps()) +
                    svc(OnvifNs.TRT, "/onvif/media_service", mediaServiceCaps()) +
                    svc(OnvifNs.TR2, "/onvif/media2_service", media2ServiceCaps()) +
                    svc(OnvifNs.TEV, "/onvif/events_service", eventServiceCaps()) +
                    svc(OnvifNs.TIMG, "/onvif/imaging_service", "<timg:Capabilities ImageStabilization=\"false\"/>") +
                    "</tds:GetServicesResponse>"
            }
            "GetServiceCapabilities" -> "<tds:GetServiceCapabilitiesResponse>${deviceServiceCaps()}</tds:GetServiceCapabilitiesResponse>"
            "GetScopes" -> "<tds:GetScopesResponse>" + scopes().joinToString("") {
                "<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>${e(it)}</tt:ScopeItem></tds:Scopes>"
            } + "</tds:GetScopesResponse>"
            "GetHostname" -> "<tds:GetHostnameResponse><tds:HostnameInformation><tt:FromDHCP>false</tt:FromDHCP>" +
                "<tt:Name>${e(i.name.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifEmpty { "android-ip-camera" })}</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>"
            "GetNetworkInterfaces" -> "<tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token=\"eth0\">" +
                "<tt:Enabled>true</tt:Enabled><tt:Info><tt:Name>wlan0</tt:Name><tt:HwAddress>${e(macColon())}</tt:HwAddress><tt:MTU>1500</tt:MTU></tt:Info>" +
                "<tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config><tt:Manual><tt:Address>${e(host())}</tt:Address><tt:PrefixLength>24</tt:PrefixLength></tt:Manual><tt:DHCP>true</tt:DHCP></tt:Config></tt:IPv4>" +
                "</tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>"
            "GetNetworkProtocols" -> "<tds:GetNetworkProtocolsResponse>" +
                "<tds:NetworkProtocols><tt:Name>HTTP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>$localPort</tt:Port></tds:NetworkProtocols>" +
                "<tds:NetworkProtocols><tt:Name>RTSP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>${rtspPort()}</tt:Port></tds:NetworkProtocols>" +
                "</tds:GetNetworkProtocolsResponse>"
            "GetDNS" -> "<tds:GetDNSResponse><tds:DNSInformation><tt:FromDHCP>true</tt:FromDHCP></tds:DNSInformation></tds:GetDNSResponse>"
            "GetNTP" -> "<tds:GetNTPResponse><tds:NTPInformation><tt:FromDHCP>true</tt:FromDHCP></tds:NTPInformation></tds:GetNTPResponse>"
            "GetNetworkDefaultGateway" -> "<tds:GetNetworkDefaultGatewayResponse><tds:NetworkGateway/></tds:GetNetworkDefaultGatewayResponse>"
            "GetZeroConfiguration" -> "<tds:GetZeroConfigurationResponse><tds:ZeroConfiguration><tt:InterfaceToken>eth0</tt:InterfaceToken><tt:Enabled>false</tt:Enabled></tds:ZeroConfiguration></tds:GetZeroConfigurationResponse>"
            "GetDiscoveryMode" -> "<tds:GetDiscoveryModeResponse><tds:DiscoveryMode>Discoverable</tds:DiscoveryMode></tds:GetDiscoveryModeResponse>"
            "GetUsers" -> "<tds:GetUsersResponse>" + (creds()?.let { "<tds:User><tt:Username>${e(it.first)}</tt:Username><tt:UserLevel>Administrator</tt:UserLevel></tds:User>" } ?: "") + "</tds:GetUsersResponse>"
            "GetWsdlUrl" -> "<tds:GetWsdlUrlResponse><tds:WsdlUrl>http://www.onvif.org/</tds:WsdlUrl></tds:GetWsdlUrlResponse>"
            "GetRelayOutputs" -> "<tds:GetRelayOutputsResponse/>"
            "GetEndpointReference" -> "<tds:GetEndpointReferenceResponse><tds:GUID>${e(endpointUuid())}</tds:GUID></tds:GetEndpointReferenceResponse>"
            else -> null
        }
    }

    private fun deviceServiceCaps() =
        "<tds:Capabilities><tds:Network IPFilter=\"false\" ZeroConfiguration=\"false\" IPVersion6=\"false\" DynDNS=\"false\" Dot11Configuration=\"false\" HostnameFromDHCP=\"false\" NTP=\"0\"/>" +
            "<tds:Security TLS1.0=\"false\" TLS1.1=\"false\" TLS1.2=\"false\" OnboardKeyGeneration=\"false\" AccessPolicyConfig=\"false\" DefaultAccessPolicy=\"false\" Dot1X=\"false\" RemoteUserHandling=\"false\" X.509Token=\"false\" SAMLToken=\"false\" KerberosToken=\"false\" UsernameToken=\"true\" HttpDigest=\"true\" RELToken=\"false\"/>" +
            "<tds:System DiscoveryResolve=\"false\" DiscoveryBye=\"true\" RemoteDiscovery=\"false\" SystemBackup=\"false\" SystemLogging=\"false\" FirmwareUpgrade=\"false\" HttpFirmwareUpgrade=\"false\" HttpSystemBackup=\"false\" HttpSystemLogging=\"false\" HttpSupportInformation=\"false\"/>" +
            "</tds:Capabilities>"

    // ===================================================================== media (ver10)

    private fun mediaServiceCaps() =
        "<trt:Capabilities SnapshotUri=\"${snapshot != null}\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\">" +
            "<trt:ProfileCapabilities MaximumNumberOfProfiles=\"2\"/>" +
            "<trt:StreamingCapabilities RTPMulticast=\"false\" RTP_TCP=\"true\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\" NoRTSPStreaming=\"false\"/>" +
            "</trt:Capabilities>"

    private fun videoSourceConfig(tag: String = "tt:VideoSourceConfiguration"): String {
        val s = stream()
        return "<$tag token=\"vsc0\"><tt:Name>VideoSourceConfig</tt:Name><tt:UseCount>2</tt:UseCount><tt:SourceToken>vs0</tt:SourceToken>" +
            "<tt:Bounds x=\"0\" y=\"0\" width=\"${s.width}\" height=\"${s.height}\"/></$tag>"
    }

    private fun videoEncoderConfig(token: String, tag: String = "tt:VideoEncoderConfiguration"): String {
        val s = stream()
        val fps = s.fps.coerceAtLeast(1)
        return "<$tag token=\"vec_$token\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution>" +
            "<tt:Quality>5</tt:Quality><tt:RateControl><tt:FrameRateLimit>$fps</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>${s.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:H264><tt:GovLength>${fps}</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>" +
            "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>" +
            "<tt:SessionTimeout>PT60S</tt:SessionTimeout></$tag>"
    }

    private fun profileXml(token: String, tag: String = "trt:Profiles"): String =
        "<$tag token=\"$token\" fixed=\"true\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name>" +
            videoSourceConfig() + videoEncoderConfig(token) + "</$tag>"

    private fun profileToken(op: Element): String? = OnvifXml.text(op, "ProfileToken")

    private fun streamUri(): String = stream().rtspUrl

    private fun media(action: String, op: Element): String? {
        val s = stream()
        return when (action) {
            "GetServiceCapabilities" -> "<trt:GetServiceCapabilitiesResponse>${mediaServiceCaps()}</trt:GetServiceCapabilitiesResponse>"
            "GetProfiles" -> "<trt:GetProfilesResponse>${profileTokens().joinToString("") { profileXml(it) }}</trt:GetProfilesResponse>"
            "GetProfile" -> {
                val t = profileToken(op) ?: "main"
                if (t !in profileTokens()) fault("ter:NoProfile", "Unknown profile $t")
                else "<trt:GetProfileResponse>${profileXml(t, "trt:Profile")}</trt:GetProfileResponse>"
            }
            "GetVideoSources" -> "<trt:GetVideoSourcesResponse><trt:VideoSources token=\"vs0\"><tt:Framerate>${s.fps}</tt:Framerate>" +
                "<tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>"
            "GetVideoSourceConfigurations" -> "<trt:GetVideoSourceConfigurationsResponse>${videoSourceConfig("trt:Configurations")}</trt:GetVideoSourceConfigurationsResponse>"
            "GetVideoSourceConfiguration" -> "<trt:GetVideoSourceConfigurationResponse>${videoSourceConfig("trt:Configuration")}</trt:GetVideoSourceConfigurationResponse>"
            "GetCompatibleVideoSourceConfigurations" -> "<trt:GetCompatibleVideoSourceConfigurationsResponse>${videoSourceConfig("trt:Configurations")}</trt:GetCompatibleVideoSourceConfigurationsResponse>"
            "GetVideoEncoderConfigurations" -> "<trt:GetVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { videoEncoderConfig(it, "trt:Configurations") }}</trt:GetVideoEncoderConfigurationsResponse>"
            "GetCompatibleVideoEncoderConfigurations" -> "<trt:GetCompatibleVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { videoEncoderConfig(it, "trt:Configurations") }}</trt:GetCompatibleVideoEncoderConfigurationsResponse>"
            "GetVideoEncoderConfiguration" -> {
                val t = OnvifXml.text(op, "ConfigurationToken")?.removePrefix("vec_") ?: "main"
                "<trt:GetVideoEncoderConfigurationResponse>${videoEncoderConfig(if (t in profileTokens()) t else "main", "trt:Configuration")}</trt:GetVideoEncoderConfigurationResponse>"
            }
            "GetVideoEncoderConfigurationOptions" -> "<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options>" +
                "<tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>" +
                "<tt:H264><tt:ResolutionsAvailable><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:ResolutionsAvailable>" +
                "<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>300</tt:Max></tt:GovLengthRange><tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>${s.fps.coerceAtLeast(1)}</tt:Max></tt:FrameRateRange>" +
                "<tt:EncodingIntervalRange><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:EncodingIntervalRange><tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported></tt:H264>" +
                "</trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>"
            "SetVideoEncoderConfiguration" -> "<trt:SetVideoEncoderConfigurationResponse/>"
            "GetGuaranteedNumberOfVideoEncoderInstances" -> "<trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse><trt:TotalNumber>2</trt:TotalNumber><trt:H264>2</trt:H264></trt:GetGuaranteedNumberOfVideoEncoderInstancesResponse>"
            "GetStreamUri" -> "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>${e(streamUri())}</tt:Uri>" +
                "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"
            "GetSnapshotUri" -> if (snapshot == null) fault("ter:ActionNotSupported", "No snapshot") else
                "<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>${e(baseUrl())}/onvif/snapshot.jpg</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetSnapshotUriResponse>"
            "GetAudioSources" -> "<trt:GetAudioSourcesResponse/>"
            "GetAudioSourceConfigurations" -> "<trt:GetAudioSourceConfigurationsResponse/>"
            "GetAudioEncoderConfigurations" -> "<trt:GetAudioEncoderConfigurationsResponse/>"
            "GetAudioOutputs" -> "<trt:GetAudioOutputsResponse/>"
            "GetVideoAnalyticsConfigurations" -> "<trt:GetVideoAnalyticsConfigurationsResponse/>"
            "GetMetadataConfigurations" -> "<trt:GetMetadataConfigurationsResponse/>"
            "GetOSDs" -> "<trt:GetOSDsResponse/>"
            "SetSynchronizationPoint" -> "<trt:SetSynchronizationPointResponse/>"
            else -> null
        }
    }

    // ===================================================================== media2 (ver20)

    private fun media2ServiceCaps() =
        "<tr2:Capabilities SnapshotUri=\"${snapshot != null}\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\" Mask=\"false\" SourceMask=\"false\">" +
            "<tr2:ProfileCapabilities MaximumNumberOfProfiles=\"2\" ConfigurationsSupported=\"VideoSource VideoEncoder\"/>" +
            "<tr2:StreamingCapabilities RTSPStreaming=\"true\" RTPMulticast=\"false\" RTP_RTSP_TCP=\"true\" NonAggregateControl=\"false\" AutoStartMulticast=\"false\"/>" +
            "</tr2:Capabilities>"

    private fun media2EncoderConfig(token: String, tag: String): String {
        val s = stream()
        return "<$tag token=\"vec_$token\" GovLength=\"${s.fps.coerceAtLeast(1)}\" Profile=\"Main\"><tt:Name>${if (token == "main") "MainStream" else "SubStream"}</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:Resolution>" +
            "<tt:RateControl ConstantBitRate=\"false\"><tt:FrameRateLimit>${s.fps}</tt:FrameRateLimit><tt:BitrateLimit>${s.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:Quality>5</tt:Quality></$tag>"
    }

    private fun media2(action: String, op: Element): String? = when (action) {
        "GetServiceCapabilities" -> "<tr2:GetServiceCapabilitiesResponse>${media2ServiceCaps()}</tr2:GetServiceCapabilitiesResponse>"
        "GetProfiles" -> {
            val want = OnvifXml.text(op, "Token")
            "<tr2:GetProfilesResponse>" + profileTokens().filter { want == null || it == want }.joinToString("") { t ->
                "<tr2:Profiles token=\"$t\" fixed=\"true\"><tr2:Name>${if (t == "main") "MainStream" else "SubStream"}</tr2:Name><tr2:Configurations>" +
                    videoSourceConfig("tr2:VideoSource") + media2EncoderConfig(t, "tr2:VideoEncoder") +
                    "</tr2:Configurations></tr2:Profiles>"
            } + "</tr2:GetProfilesResponse>"
        }
        "GetVideoSourceConfigurations" -> "<tr2:GetVideoSourceConfigurationsResponse>${videoSourceConfig("tr2:Configurations")}</tr2:GetVideoSourceConfigurationsResponse>"
        "GetVideoEncoderConfigurationOptions" -> {
            val s = stream()
            val fps = s.fps.coerceAtLeast(1)
            "<tr2:GetVideoEncoderConfigurationOptionsResponse><tr2:Options GovLengthRange=\"1 300\" FrameRatesSupported=\"$fps\" " +
                "ProfilesSupported=\"Main\" ConstantBitRateSupported=\"false\" GuaranteedInstances=\"2\">" +
                "<tt:Encoding>H264</tt:Encoding><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>" +
                "<tt:ResolutionsAvailable><tt:Width>${s.width}</tt:Width><tt:Height>${s.height}</tt:Height></tt:ResolutionsAvailable>" +
                "<tt:BitrateRange><tt:Min>${(s.bitrateKbps / 4).coerceAtLeast(64)}</tt:Min><tt:Max>${s.bitrateKbps * 2}</tt:Max></tt:BitrateRange>" +
                "</tr2:Options></tr2:GetVideoEncoderConfigurationOptionsResponse>"
        }
        // The stream is fixed by the app's own settings; accept (and ignore) client tuning so
        // NVRs that "auto-configure" the encoder don't treat the camera as broken.
        "SetVideoEncoderConfiguration" -> "<tr2:SetVideoEncoderConfigurationResponse/>"
        "GetVideoEncoderInstances" -> "<tr2:GetVideoEncoderInstancesResponse><tr2:Info><tr2:Codec><tr2:Encoding>H264</tr2:Encoding><tr2:Number>2</tr2:Number></tr2:Codec><tr2:Total>2</tr2:Total></tr2:Info></tr2:GetVideoEncoderInstancesResponse>"
        "GetVideoEncoderConfigurations" -> "<tr2:GetVideoEncoderConfigurationsResponse>${profileTokens().joinToString("") { media2EncoderConfig(it, "tr2:Configurations") }}</tr2:GetVideoEncoderConfigurationsResponse>"
        "GetStreamUri" -> "<tr2:GetStreamUriResponse><tr2:Uri>${e(streamUri())}</tr2:Uri></tr2:GetStreamUriResponse>"
        "GetSnapshotUri" -> if (snapshot == null) fault("ter:ActionNotSupported", "No snapshot")
            else "<tr2:GetSnapshotUriResponse><tr2:Uri>${e(baseUrl())}/onvif/snapshot.jpg</tr2:Uri></tr2:GetSnapshotUriResponse>"
        "GetAudioSourceConfigurations", "GetAudioEncoderConfigurations", "GetMetadataConfigurations", "GetAnalyticsConfigurations" ->
            "<tr2:${action}Response/>"
        "SetSynchronizationPoint" -> "<tr2:SetSynchronizationPointResponse/>"
        else -> null
    }

    // ===================================================================== imaging (minimal)

    private fun imaging(action: String): String? = when (action) {
        "GetServiceCapabilities" -> "<timg:GetServiceCapabilitiesResponse><timg:Capabilities ImageStabilization=\"false\"/></timg:GetServiceCapabilitiesResponse>"
        "GetImagingSettings" -> "<timg:GetImagingSettingsResponse><timg:ImagingSettings/></timg:GetImagingSettingsResponse>"
        "GetOptions" -> "<timg:GetOptionsResponse><timg:ImagingOptions/></timg:GetOptionsResponse>"
        "GetMoveOptions" -> "<timg:GetMoveOptionsResponse><timg:MoveOptions/></timg:GetMoveOptionsResponse>"
        "GetStatus" -> "<timg:GetStatusResponse><timg:Status/></timg:GetStatusResponse>"
        else -> null
    }

    // ===================================================================== events

    private fun eventServiceCaps() =
        "<tev:Capabilities WSSubscriptionPolicySupport=\"false\" WSPullPointSupport=\"true\" WSPausableSubscriptionManagerInterfaceSupport=\"false\" " +
            "MaxNotificationProducers=\"1\" MaxPullPoints=\"$MAX_SUBSCRIPTIONS\" PersistentNotificationStorage=\"false\"/>"

    private fun messageDescription(source: List<Pair<String, String>>, data: Pair<String, String>) =
        "<tt:MessageDescription IsProperty=\"true\"><tt:Source>" +
            source.joinToString("") { (n, t) -> "<tt:SimpleItemDescription Name=\"$n\" Type=\"$t\"/>" } +
            "</tt:Source><tt:Data><tt:SimpleItemDescription Name=\"${data.first}\" Type=\"${data.second}\"/></tt:Data></tt:MessageDescription>"

    private fun eventProperties() = "<tev:GetEventPropertiesResponse>" +
        "<tev:TopicNamespaceLocation>http://www.onvif.org/onvif/ver10/topics/topicns.xml</tev:TopicNamespaceLocation>" +
        "<wsnt:FixedTopicSet>true</wsnt:FixedTopicSet>" +
        "<wstop:TopicSet>" +
        "<tns1:RuleEngine wstop:topic=\"false\"><tns1:CellMotionDetector wstop:topic=\"false\"><tns1:Motion wstop:topic=\"true\">" +
        messageDescription(
            listOf("VideoSourceConfigurationToken" to "tt:ReferenceToken", "VideoAnalyticsConfigurationToken" to "tt:ReferenceToken", "Rule" to "xs:string"),
            "IsMotion" to "xs:boolean"
        ) + "</tns1:Motion></tns1:CellMotionDetector></tns1:RuleEngine>" +
        "<tns1:VideoSource wstop:topic=\"false\"><tns1:MotionAlarm wstop:topic=\"true\">" +
        messageDescription(listOf("Source" to "tt:ReferenceToken"), "State" to "xs:boolean") +
        "</tns1:MotionAlarm></tns1:VideoSource>" +
        "</wstop:TopicSet>" +
        "<wsnt:TopicExpressionDialect>$TOPIC_DIALECT</wsnt:TopicExpressionDialect>" +
        "<wsnt:TopicExpressionDialect>http://docs.oasis-open.org/wsnt/t-1/TopicExpression/ConcreteSet</wsnt:TopicExpressionDialect>" +
        "<tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter</tev:MessageContentFilterDialect>" +
        "<tev:MessageContentSchemaLocation>http://www.onvif.org/onvif/ver10/schema/onvif.xsd</tev:MessageContentSchemaLocation>" +
        "</tev:GetEventPropertiesResponse>"

    private fun notificationMessage(topic: String, utc: String, op: String, source: List<Pair<String, String>>, data: Pair<String, String>) =
        "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"$TOPIC_DIALECT\">$topic</wsnt:Topic><wsnt:Message>" +
            "<tt:Message UtcTime=\"$utc\" PropertyOperation=\"$op\"><tt:Source>" +
            source.joinToString("") { (n, v) -> "<tt:SimpleItem Name=\"$n\" Value=\"$v\"/>" } +
            "</tt:Source><tt:Data><tt:SimpleItem Name=\"${data.first}\" Value=\"${data.second}\"/></tt:Data></tt:Message>" +
            "</wsnt:Message></wsnt:NotificationMessage>"

    private fun motionMessages(active: Boolean, op: String, now: Long): List<String> {
        val utc = OnvifXml.isoUtc(now)
        return listOf(
            notificationMessage(
                TOPIC_CELL, utc, op,
                listOf("VideoSourceConfigurationToken" to "vsc0", "VideoAnalyticsConfigurationToken" to "vac0", "Rule" to "MotionDetectorRule"),
                "IsMotion" to active.toString()
            ),
            notificationMessage(TOPIC_ALARM, utc, op, listOf("Source" to "vs0"), "State" to active.toString())
        )
    }

    private fun enqueue(s: Subscription, msgs: List<String>) {
        synchronized(s.lock) {
            msgs.forEach { s.queue.addLast(it) }
            while (s.queue.size > MAX_QUEUED) s.queue.removeFirst()
            s.lock.notifyAll()
        }
    }

    private fun pruneExpired(now: Long) {
        subscriptions.values.filter { it.expiresAt < now }.forEach { s ->
            subscriptions.remove(s.id)
            synchronized(s.lock) { s.lock.notifyAll() }
        }
    }

    /** Find the subscription addressed by the request path or (fallback) the wsa:To header. */
    private fun subscriptionFor(path: String, header: Element?): Subscription? {
        val id = path.substringAfter("/onvif/pullpoint/", "").ifEmpty {
            OnvifXml.text(header, "To")?.substringAfter("/onvif/pullpoint/", "").orEmpty()
        }.trim('/')
        return if (id.isEmpty()) null else subscriptions[id]
    }

    private fun events(action: String, op: Element, path: String, header: Element?): String? {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        return when (action) {
            "GetServiceCapabilities" -> "<tev:GetServiceCapabilitiesResponse>${eventServiceCaps()}</tev:GetServiceCapabilitiesResponse>"
            "GetEventProperties" -> eventProperties()
            "CreatePullPointSubscription" -> {
                val term = OnvifXml.parseTermination(OnvifXml.text(op, "InitialTerminationTime"), now) ?: (now + DEFAULT_SUB_MS)
                if (subscriptions.size >= MAX_SUBSCRIPTIONS) {
                    subscriptions.values.minByOrNull { it.expiresAt }?.let { subscriptions.remove(it.id) }
                }
                val sub = Subscription(OnvifXml.randomHex(8), term.coerceAtLeast(now + 10_000))
                subscriptions[sub.id] = sub
                enqueue(sub, motionMessages(motion, "Initialized", now))
                onLog("ONVIF pull-point subscription ${sub.id} created")
                "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference>" +
                    "<wsa:Address>${baseUrl()}/onvif/pullpoint/${sub.id}</wsa:Address></tev:SubscriptionReference>" +
                    "<wsnt:CurrentTime>${OnvifXml.isoUtc(now)}</wsnt:CurrentTime><wsnt:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</wsnt:TerminationTime>" +
                    "</tev:CreatePullPointSubscriptionResponse>"
            }
            "PullMessages" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                val timeout = (OnvifXml.text(op, "Timeout")?.let { OnvifXml.parseDuration(it) } ?: 10_000L).coerceIn(0L, MAX_PULL_WAIT_MS)
                val limit = (OnvifXml.text(op, "MessageLimit")?.toIntOrNull() ?: 100).coerceAtLeast(1)
                val deadline = now + timeout
                val msgs = ArrayList<String>()
                synchronized(sub.lock) {
                    while (sub.queue.isEmpty() && !stopped && subscriptions.containsKey(sub.id)) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) break
                        sub.lock.wait(left)
                    }
                    while (msgs.size < limit && sub.queue.isNotEmpty()) msgs += sub.queue.removeFirst()
                }
                val t = System.currentTimeMillis()
                val summary = msgs.joinToString(",") { m ->
                    val v = Regex("Name=\"(?:IsMotion|State)\" Value=\"(\\w+)\"").find(m)?.groupValues?.get(1)
                    (if (m.contains("CellMotionDetector")) "Cell=" else "Alarm=") + v
                }
                onLog("ONVIF PullMessages from ${remoteHost.get()} timeout=${timeout}ms waited=${t - now}ms -> ${msgs.size} [$summary]")
                "<tev:PullMessagesResponse><tev:CurrentTime>${OnvifXml.isoUtc(t)}</tev:CurrentTime>" +
                    "<tev:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</tev:TerminationTime>" +
                    msgs.joinToString("") + "</tev:PullMessagesResponse>"
            }
            "SetSynchronizationPoint" -> {
                val sub = subscriptionFor(path, header)
                if (sub != null) enqueue(sub, motionMessages(motion, "Initialized", now))
                else subscriptions.values.forEach { enqueue(it, motionMessages(motion, "Initialized", now)) }
                "<tev:SetSynchronizationPointResponse/>"
            }
            "Seek" -> fault("ter:ActionNotSupported", "Seek not supported")
            else -> null
        }
    }

    /** WS-BaseNotification operations sent to the pull-point (subscription manager) address. */
    private fun notification(action: String, op: Element, path: String, header: Element?): String? {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        return when (action) {
            "Renew" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                sub.expiresAt = (OnvifXml.parseTermination(OnvifXml.text(op, "TerminationTime"), now) ?: (now + DEFAULT_SUB_MS)).coerceAtLeast(now + 10_000)
                "<wsnt:RenewResponse><wsnt:TerminationTime>${OnvifXml.isoUtc(sub.expiresAt)}</wsnt:TerminationTime>" +
                    "<wsnt:CurrentTime>${OnvifXml.isoUtc(now)}</wsnt:CurrentTime></wsnt:RenewResponse>"
            }
            "Unsubscribe" -> {
                val sub = subscriptionFor(path, header) ?: return fault("ter:InvalidArgVal", "Unknown subscription")
                subscriptions.remove(sub.id)
                synchronized(sub.lock) { sub.lock.notifyAll() }
                "<wsnt:UnsubscribeResponse/>"
            }
            // Push (Subscribe) isn't offered: HA falls back to PullPoint when this faults.
            else -> null
        }
    }
}

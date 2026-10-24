package com.github.digitallyrefined.androidipcamera.helpers

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.UUID

/**
 * WS-Discovery responder (ONVIF uses the 2005/04 draft; 2009/01 probes are answered in kind).
 * Answers multicast Probes with a unicast ProbeMatches and announces Hello/Bye.
 * The caller (Android service) must hold a WifiManager.MulticastLock while this runs.
 */
internal class OnvifDiscovery(
    private val endpointUuid: () -> String,     // "urn:uuid:..."
    private val xaddr: () -> String,            // http(s)://ip:port/onvif/device_service
    private val scopes: () -> List<String>,
    private val port: Int = 3702,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        const val GROUP = "239.255.255.250"
        const val WSD_2005 = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
        const val WSA_2004 = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        const val WSD_2009 = "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01"
        const val WSA_2005 = "http://www.w3.org/2005/08/addressing"
        const val DN = "http://www.onvif.org/ver10/network/wsdl"
        private val ACCEPTED_TYPES = setOf("NetworkVideoTransmitter", "Device")

        /** Stable endpoint UUID derived from a hardware identifier (MAC/serial). */
        fun uuidFor(seed: String): String = "urn:uuid:" + UUID.nameUUIDFromBytes(("onvif-$seed").toByteArray()).toString()

        fun defaultScopes(name: String, hardware: String): List<String> {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            return listOf(
                "onvif://www.onvif.org/type/video_encoder",
                "onvif://www.onvif.org/type/Network_Video_Transmitter",
                "onvif://www.onvif.org/Profile/Streaming",
                "onvif://www.onvif.org/Profile/S",
                "onvif://www.onvif.org/name/${enc(name)}",
                "onvif://www.onvif.org/hardware/${enc(hardware)}",
                "onvif://www.onvif.org/location/"
            )
        }
    }

    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    private var metadataVersion = 1

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "OnvifDiscovery").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        try { sendMulticast(buildAnnounce(bye = true)) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { thread?.join(1000) } catch (_: Exception) {}
        thread = null
    }

    private fun loop() {
        val group = InetAddress.getByName(GROUP)
        while (running) {
            try {
                // MulticastSocket(port) sets SO_REUSEADDR before binding; the unbound
                // MulticastSocket(null) + bind() path throws "port out of range:-1" on Android.
                val s = MulticastSocket(port).apply {
                    soTimeout = 2000
                    timeToLive = 4
                }
                joinAll(s, group)
                socket = s
                onLog("ONVIF WS-Discovery listening on udp/$port")
                try { sendMulticast(buildAnnounce(bye = false)) } catch (_: Exception) {}
                val buf = ByteArray(65_536)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val msg = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    val reply = buildProbeMatch(msg) ?: continue
                    val data = reply.toByteArray(Charsets.UTF_8)
                    try { s.send(DatagramPacket(data, data.size, pkt.socketAddress)) } catch (e: Exception) {
                        onLog("WS-Discovery reply failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    onLog("WS-Discovery error: ${e.message}; retrying")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun joinAll(s: MulticastSocket, group: InetAddress) {
        var joined = false
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is java.net.Inet4Address } }
                .forEach { ni ->
                    try { s.joinGroup(InetSocketAddress(group, port), ni); joined = true } catch (_: Exception) {}
                }
        } catch (_: Exception) {}
        if (!joined) s.joinGroup(group)
    }

    private fun sendMulticast(xml: String) {
        val s = socket ?: return
        val data = xml.toByteArray(Charsets.UTF_8)
        s.send(DatagramPacket(data, data.size, InetAddress.getByName(GROUP), port))
    }

    /** Returns a ProbeMatches envelope for a matching Probe, or null (not a probe / types don't match). */
    fun buildProbeMatch(probeXml: String): String? {
        val doc = OnvifXml.parse(probeXml) ?: return null
        val root = doc.documentElement ?: return null
        val body = OnvifXml.child(root, "Body") ?: return null
        val probe = OnvifXml.children(body).firstOrNull { OnvifXml.localName(it) == "Probe" } ?: return null
        val wsd = probe.namespaceURI ?: WSD_2005
        val wsa = if (wsd == WSD_2009) WSA_2005 else WSA_2004
        val header = OnvifXml.child(root, "Header")
        val messageId = OnvifXml.text(header, "MessageID") ?: return null

        val types = OnvifXml.child(probe, "Types")?.textContent?.trim().orEmpty()
        if (types.isNotEmpty()) {
            val wanted = types.split(Regex("\\s+")).map { it.substringAfter(':') }
            if (wanted.none { it in ACCEPTED_TYPES }) return null
        }
        val scopeFilter = OnvifXml.child(probe, "Scopes")?.textContent?.trim().orEmpty()
        val ours = scopes()
        if (scopeFilter.isNotEmpty()) {
            val req = scopeFilter.split(Regex("\\s+"))
            // RFC 3986 prefix match (default WS-Discovery rule).
            if (!req.all { r -> ours.any { o -> o == r || o.startsWith(r.trimEnd('/') + "/") } }) return null
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:a=\"$wsa\" xmlns:d=\"$wsd\" xmlns:dn=\"$DN\" xmlns:tds=\"${OnvifNs.TDS}\">" +
            "<s:Header>" +
            "<a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID>" +
            "<a:RelatesTo>${OnvifXml.esc(messageId)}</a:RelatesTo>" +
            "<a:To>${if (wsa == WSA_2004) "$WSA_2004/role/anonymous" else "$WSA_2005/anonymous"}</a:To>" +
            "<a:Action>$wsd/ProbeMatches</a:Action>" +
            "</s:Header><s:Body><d:ProbeMatches><d:ProbeMatch>" +
            "<a:EndpointReference><a:Address>${OnvifXml.esc(endpointUuid())}</a:Address></a:EndpointReference>" +
            "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>" +
            "<d:Scopes>${OnvifXml.esc(ours.joinToString(" "))}</d:Scopes>" +
            "<d:XAddrs>${OnvifXml.esc(xaddr())}</d:XAddrs>" +
            "<d:MetadataVersion>$metadataVersion</d:MetadataVersion>" +
            "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"
    }

    fun buildAnnounce(bye: Boolean): String {
        val kind = if (bye) "Bye" else "Hello"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"${OnvifNs.SOAP}\" xmlns:a=\"$WSA_2004\" xmlns:d=\"$WSD_2005\" xmlns:dn=\"$DN\" xmlns:tds=\"${OnvifNs.TDS}\">" +
            "<s:Header><a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID>" +
            "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>" +
            "<a:Action>$WSD_2005/$kind</a:Action></s:Header>" +
            "<s:Body><d:$kind><a:EndpointReference><a:Address>${OnvifXml.esc(endpointUuid())}</a:Address></a:EndpointReference>" +
            (if (bye) "" else "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>" +
                "<d:Scopes>${OnvifXml.esc(scopes().joinToString(" "))}</d:Scopes>" +
                "<d:XAddrs>${OnvifXml.esc(xaddr())}</d:XAddrs>") +
            "<d:MetadataVersion>$metadataVersion</d:MetadataVersion></d:$kind></s:Body></s:Envelope>"
    }
}

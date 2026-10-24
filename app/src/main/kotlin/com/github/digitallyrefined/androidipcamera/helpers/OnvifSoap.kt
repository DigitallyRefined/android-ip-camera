package com.github.digitallyrefined.androidipcamera.helpers

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Namespaces used by the ONVIF responses (Profile S + events). */
internal object OnvifNs {
    const val SOAP = "http://www.w3.org/2003/05/soap-envelope"
    const val TDS = "http://www.onvif.org/ver10/device/wsdl"
    const val TRT = "http://www.onvif.org/ver10/media/wsdl"
    const val TR2 = "http://www.onvif.org/ver20/media/wsdl"
    const val TT = "http://www.onvif.org/ver10/schema"
    const val TEV = "http://www.onvif.org/ver10/events/wsdl"
    const val TIMG = "http://www.onvif.org/ver20/imaging/wsdl"
    const val WSNT = "http://docs.oasis-open.org/wsn/b-2"
    const val WSA = "http://www.w3.org/2005/08/addressing"
    const val TNS1 = "http://www.onvif.org/ver10/topics"
    const val WSTOP = "http://docs.oasis-open.org/wsn/t-1"
    const val TER = "http://www.onvif.org/ver10/error"
    const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"

    /** xmlns declarations placed on every response envelope. */
    val DECLS: String = listOf(
        "s" to SOAP, "tds" to TDS, "trt" to TRT, "tr2" to TR2, "tt" to TT, "tev" to TEV,
        "timg" to TIMG, "wsnt" to WSNT, "wsa" to WSA, "tns1" to TNS1, "wstop" to WSTOP, "ter" to TER,
        "xs" to "http://www.w3.org/2001/XMLSchema"
    ).joinToString(" ") { (p, u) -> "xmlns:$p=\"$u\"" }
}

/** Small XML / crypto / time helpers shared by the ONVIF server and WS-Discovery. Pure JVM. */
internal object OnvifXml {
    fun esc(s: String?): String {
        val v = s ?: return ""
        val sb = StringBuilder(v.length)
        for (c in v) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun parse(xml: String): Document? = try {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    } catch (_: Exception) { null }

    fun children(e: Node?): List<Element> {
        if (e == null) return emptyList()
        val out = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let { out += it }
        return out
    }

    fun localName(e: Element): String = e.localName ?: e.nodeName.substringAfter(':')

    fun child(e: Node?, name: String): Element? = children(e).firstOrNull { localName(it) == name }

    /** First descendant element with the given local name (depth-first). */
    fun find(e: Node?, name: String): Element? {
        for (c in children(e)) {
            if (localName(c) == name) return c
            find(c, name)?.let { return it }
        }
        return null
    }

    fun text(e: Node?, name: String): String? = find(e, name)?.textContent?.trim()

    fun envelope(body: String, header: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope ${OnvifNs.DECLS}>" +
            (if (header.isNotEmpty()) "<s:Header>$header</s:Header>" else "") +
            "<s:Body>$body</s:Body></s:Envelope>"

    fun fault(subcode: String, reason: String, sender: Boolean = true): String = envelope(
        "<s:Fault><s:Code><s:Value>${if (sender) "s:Sender" else "s:Receiver"}</s:Value>" +
            "<s:Subcode><s:Value>$subcode</s:Value></s:Subcode></s:Code>" +
            "<s:Reason><s:Text xml:lang=\"en\">${esc(reason)}</s:Text></s:Reason></s:Fault>"
    )

    // ---------- time ----------
    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun isoUtc(ms: Long): String = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(ms))

    /** Parses xs:dateTime (UTC 'Z' or offset, optional fraction). */
    fun parseDateTime(s: String): Long? {
        val t = s.trim()
        val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""").find(t) ?: return null
        val base = fmt("yyyy-MM-dd'T'HH:mm:ss").parse(m.groupValues[1])?.time ?: return null
        val frac = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ("0" + it).toDouble() * 1000 }?.toLong() ?: 0L
        val tz = m.groupValues[3]
        val off = if (tz.isEmpty() || tz == "Z") 0L else {
            val sign = if (tz[0] == '-') -1 else 1
            val digits = tz.substring(1).replace(":", "")
            sign * (digits.substring(0, 2).toLong() * 3600_000 + digits.substring(2, 4).toLong() * 60_000)
        }
        return base + frac - off
    }

    /** Parses xs:duration like PT60S, PT1M, PT0.5S, P1DT2H. Months/years approximated. */
    fun parseDuration(s: String): Long? {
        val m = Regex("""^(-)?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""").find(s.trim()) ?: return null
        val g = m.groupValues
        fun n(i: Int) = g[i].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
        val ms = ((n(2) * 365 + n(3) * 30 + n(4)) * 86_400_000 + n(5) * 3_600_000 + n(6) * 60_000 + n(7) * 1000).toLong()
        return if (g[1] == "-") -ms else ms
    }

    /** Absolute time from either an xs:duration (relative to [now]) or an xs:dateTime. */
    fun parseTermination(s: String?, now: Long): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return if (t.startsWith("P") || t.startsWith("-P")) parseDuration(t)?.let { now + it } else parseDateTime(t)
    }

    // ---------- crypto / encoding ----------
    private val rng = SecureRandom()

    fun randomHex(bytes: Int = 16): String = ByteArray(bytes).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun md5Hex(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)

    /** WS-Security UsernameToken digest: Base64(SHA1(nonce + created + password)). */
    fun passwordDigest(nonce: ByteArray, created: String, password: String): String =
        OnvifBase64.encode(sha1(nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)))

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/** java.util.Base64 needs API 26 (minSdk is 24) and android.util.Base64 isn't pure JVM. */
internal object OnvifBase64 {
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(ALPHA[b0 shr 2]).append(ALPHA[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) ALPHA[((b1 and 0xf) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHA[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        val clean = s.filter { !it.isWhitespace() }.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buf = 0; var bits = 0
        for (c in clean) {
            val v = when (c) { '-' -> 62; '_' -> 63; else -> ALPHA.indexOf(c) }
            if (v < 0) return null
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xff) }
        }
        return out.toByteArray()
    }
}

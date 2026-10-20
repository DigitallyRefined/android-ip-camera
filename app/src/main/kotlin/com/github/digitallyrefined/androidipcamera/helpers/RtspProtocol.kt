package com.github.digitallyrefined.androidipcamera.helpers

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure, testable building blocks for [RtspServer]: request parsing, authentication, SDP and
 * RFC 6184 H.264 RTP packetization. No android.* dependencies.
 */

/** One parsed RTSP request (RFC 2326 §6). Header names are matched case-insensitively. */
internal data class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0)
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val cseq: String? get() = header("CSeq")?.trim()

    companion object {
        /** Parse a request head (request line + headers, CRLF or LF separated, no trailing blank line needed). */
        fun parseHead(head: String, body: ByteArray = ByteArray(0)): RtspRequest? {
            val lines = head.split("\r\n", "\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val parts = lines[0].trim().split(Regex("\\s+"))
            if (parts.size != 3 || !parts[2].startsWith("RTSP/")) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            return RtspRequest(parts[0].uppercase(), parts[1], parts[2], headers, body)
        }
    }
}

/**
 * Reads RTSP requests from a connection that may also carry interleaved binary frames
 * (`$` + channel + 16-bit length, RFC 2326 §10.12), e.g. RTCP receiver reports over TCP.
 */
internal class RtspRequestReader(private val input: InputStream) {
    sealed class Item {
        data class Request(val request: RtspRequest) : Item()
        data class Interleaved(val channel: Int, val data: ByteArray) : Item()
        /** Unparseable request head; the raw head is kept so the caller can answer 400. */
        data class Malformed(val head: String) : Item()
    }

    /** Returns the next item, or null at end of stream. */
    fun next(): Item? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            if (first == '$'.code) {
                val channel = input.read()
                val hi = input.read()
                val lo = input.read()
                if (channel < 0 || hi < 0 || lo < 0) return null
                val len = (hi shl 8) or lo
                val data = readFully(len) ?: return null
                return Item.Interleaved(channel, data)
            }
            if (first == '\r'.code || first == '\n'.code) continue // stray line breaks between messages
            val head = readHead(first) ?: return null
            val req = RtspRequest.parseHead(head) ?: return Item.Malformed(head)
            val len = req.header("Content-Length")?.trim()?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) return Item.Malformed(head)
            val body = if (len > 0) readFully(len) ?: return null else ByteArray(0)
            return Item.Request(req.copy(body = body))
        }
    }

    private fun readHead(first: Int): String? {
        val out = ByteArrayOutputStream()
        out.write(first)
        var prev3 = -1; var prev2 = -1; var prev1 = first
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (out.size() > MAX_HEAD) return null
            // End of head: CRLFCRLF or LFLF
            if ((prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) ||
                (prev1 == '\n'.code && b == '\n'.code)) {
                return out.toString(Charsets.ISO_8859_1.name())
            }
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
    }

    private fun readFully(len: Int): ByteArray? {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }

    companion object {
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 64 * 1024
    }
}

/** Parsed `Transport:` header (RFC 2326 §12.39), only the parts we support. */
internal data class RtspTransport(
    val tcp: Boolean,
    val multicast: Boolean,
    val interleaved: Pair<Int, Int>?,
    val clientPorts: Pair<Int, Int>?
) {
    companion object {
        /** Picks the first acceptable transport spec from a comma-separated list. */
        fun parse(header: String?): RtspTransport? {
            if (header.isNullOrBlank()) return null
            for (spec in header.split(',')) {
                val params = spec.trim().split(';').map { it.trim() }
                val proto = params.firstOrNull()?.uppercase() ?: continue
                if (!proto.startsWith("RTP/AVP")) continue
                val tcp = proto == "RTP/AVP/TCP"
                val multicast = params.any { it.equals("multicast", true) }
                if (multicast) continue
                fun range(key: String): Pair<Int, Int>? {
                    val v = params.firstOrNull { it.startsWith("$key=", true) }?.substringAfter('=') ?: return null
                    val a = v.split('-')
                    val lo = a[0].trim().toIntOrNull() ?: return null
                    val hi = a.getOrNull(1)?.trim()?.toIntOrNull() ?: (lo + 1)
                    return lo to hi
                }
                val inter = range("interleaved")
                val ports = range("client_port")
                if (tcp) return RtspTransport(true, false, inter ?: (0 to 1), null)
                if (ports != null) return RtspTransport(false, false, null, ports)
                // UDP without client_port is unusable for unicast
            }
            return null
        }
    }
}

/** RTSP authentication: HTTP Digest (MD5, RFC 2617 incl. qop=auth) and Basic. */
internal class RtspAuth(val realm: String = "AndroidIPCamera") {
    private val random = SecureRandom()
    private val nonces = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    fun newNonce(): String {
        val b = ByteArray(16); random.nextBytes(b)
        val n = hex(b)
        synchronized(nonces) { nonces[n] = System.currentTimeMillis() }
        return n
    }

    fun challengeHeaders(): List<String> = listOf(
        "WWW-Authenticate: Digest realm=\"$realm\", nonce=\"${newNonce()}\", algorithm=MD5",
        "WWW-Authenticate: Basic realm=\"$realm\""
    )

    /**
     * Verify an Authorization header for [method]. With [checkNonce] true the nonce must be one we
     * issued recently (12 h) — clients like ffmpeg keep reusing the first nonce for the session.
     */
    fun verify(authorization: String?, method: String, user: String, pass: String, checkNonce: Boolean = true): Boolean {
        if (authorization.isNullOrBlank()) return false
        val a = authorization.trim()
        if (a.startsWith("Basic ", ignoreCase = true)) {
            val decoded = Base64Lite.decode(a.substring(6).trim())?.toString(Charsets.UTF_8) ?: return false
            return constantTimeEquals(decoded, "$user:$pass")
        }
        if (!a.startsWith("Digest ", ignoreCase = true)) return false
        val p = parseDigestParams(a.substring(7))
        if (p["username"] != user) return false
        if (p["realm"] != realm) return false
        val nonce = p["nonce"] ?: return false
        if (checkNonce) {
            val issued = synchronized(nonces) { nonces[nonce] } ?: return false
            if (System.currentTimeMillis() - issued > 12 * 3600_000L) return false
        }
        val uri = p["uri"] ?: return false
        val response = p["response"] ?: return false
        val ha1 = md5Hex("$user:$realm:$pass")
        val ha2 = md5Hex("$method:$uri")
        val qop = p["qop"]
        val expected = if (qop != null) {
            md5Hex("$ha1:$nonce:${p["nc"] ?: ""}:${p["cnonce"] ?: ""}:$qop:$ha2")
        } else md5Hex("$ha1:$nonce:$ha2")
        return constantTimeEquals(expected, response.lowercase())
    }

    companion object {
        fun parseDigestParams(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            val re = Regex("""(\w+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))""")
            for (m in re.findAll(s)) out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            return out
        }

        fun md5Hex(s: String): String = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))

        fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) { sb.append("0123456789abcdef"[(x.toInt() shr 4) and 0xf]); sb.append("0123456789abcdef"[x.toInt() and 0xf]) }
            return sb.toString()
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}

/** Minimal RFC 4648 Base64 (java.util.Base64 needs API 26; minSdk is 24). */
internal object Base64Lite {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 0xf) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        val clean = s.trim().trimEnd('=')
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var acc = 0; var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) }
        }
        return out.toByteArray()
    }
}

/** SDP for a single H.264 video track (RFC 4566 + RFC 6184 §8.2.1). */
internal object RtspSdp {
    const val PAYLOAD_TYPE = 96
    const val TRACK_CONTROL = "trackID=0"

    /** profile-level-id = hex of SPS bytes 1..3 (profile_idc, constraint flags, level_idc). */
    fun profileLevelId(sps: ByteArray?): String {
        if (sps == null || sps.size < 4) return "42e01f"
        return RtspAuth.hex(sps.copyOfRange(1, 4))
    }

    fun build(sps: ByteArray?, pps: ByteArray?, host: String, sessionId: Long, sessionName: String = "Android IP Camera"): String {
        val fmtp = StringBuilder("packetization-mode=1;profile-level-id=${profileLevelId(sps)}")
        if (sps != null && pps != null) {
            fmtp.append(";sprop-parameter-sets=${Base64Lite.encode(sps)},${Base64Lite.encode(pps)}")
        }
        return buildString {
            append("v=0\r\n")
            append("o=- $sessionId 1 IN IP4 $host\r\n")
            append("s=$sessionName\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=tool:AndroidIPCamera\r\n")
            append("a=range:npt=0-\r\n")
            append("a=control:*\r\n")
            append("m=video 0 RTP/AVP $PAYLOAD_TYPE\r\n")
            append("a=rtpmap:$PAYLOAD_TYPE H264/90000\r\n")
            append("a=fmtp:$PAYLOAD_TYPE $fmtp\r\n")
            append("a=control:$TRACK_CONTROL\r\n")
        }
    }
}

/**
 * RFC 6184 packetization (non-interleaved mode): one NAL per packet when it fits, otherwise FU-A.
 * Produces payloads only; sequence numbers / SSRC / timestamp are applied per session.
 */
internal object RtpH264Packetizer {
    const val DEFAULT_MAX_PAYLOAD = 1400

    data class Payload(val bytes: ByteArray, val marker: Boolean)

    /** Split an Annex-B access unit into RTP payloads. AUD (type 9) NALs are dropped. */
    fun packetize(annexB: ByteArray, maxPayload: Int = DEFAULT_MAX_PAYLOAD): List<Payload> {
        val nals = H264Bitstream.nalRanges(annexB)
            .filter { it.type != 9 }
            .map { annexB.copyOfRange(it.start, it.end) }
            .filter { it.isNotEmpty() }
        val out = ArrayList<Payload>()
        nals.forEachIndexed { i, nal ->
            val last = i == nals.lastIndex
            if (nal.size <= maxPayload) {
                out += Payload(nal, last)
            } else {
                val header = nal[0].toInt() and 0xff
                val indicator = (header and 0xe0) or 28          // F + NRI, type = FU-A
                val type = header and 0x1f
                var off = 1
                val chunk = maxPayload - 2
                while (off < nal.size) {
                    val n = minOf(chunk, nal.size - off)
                    val start = off == 1
                    val end = off + n >= nal.size
                    val p = ByteArray(n + 2)
                    p[0] = indicator.toByte()
                    p[1] = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                    System.arraycopy(nal, off, p, 2, n)
                    out += Payload(p, last && end)
                    off += n
                }
            }
        }
        return out
    }

    /** 12-byte RTP header (RFC 3550 §5.1) + payload. */
    /**
     * RTCP compound packet: Sender Report (RFC 3550 §6.4.1) + SDES CNAME. Recorders use the SR's
     * NTP↔RTP mapping to place frames on a wall-clock timeline.
     */
    fun rtcpSenderReport(ssrc: Int, wallClockMs: Long, rtpTimestamp: Long, packets: Long, octets: Long, cname: String = "androidipcamera"): ByteArray {
        val ntpSec = wallClockMs / 1000 + 2_208_988_800L            // 1900 epoch
        val ntpFrac = ((wallClockMs % 1000) shl 32) / 1000
        val sr = java.nio.ByteBuffer.allocate(28)
        sr.put(0x80.toByte()); sr.put(200.toByte()); sr.putShort(6)
        sr.putInt(ssrc)
        sr.putInt(ntpSec.toInt()); sr.putInt(ntpFrac.toInt())
        sr.putInt(rtpTimestamp.toInt())
        sr.putInt(packets.toInt()); sr.putInt(octets.toInt())
        val name = cname.toByteArray(Charsets.US_ASCII)
        // SDES chunk: SSRC, CNAME item (type 1, len, text), END (0), padded to 32-bit words.
        val chunkLen = 4 + 2 + name.size + 1
        val padded = (chunkLen + 3) / 4 * 4
        val sdes = java.nio.ByteBuffer.allocate(4 + padded)
        sdes.put(0x81.toByte()); sdes.put(202.toByte()); sdes.putShort((padded / 4).toShort())
        sdes.putInt(ssrc); sdes.put(1); sdes.put(name.size.toByte()); sdes.put(name)
        return sr.array() + sdes.array()
    }

    fun rtpPacket(payload: Payload, seq: Int, timestamp: Long, ssrc: Int, payloadType: Int = RtspSdp.PAYLOAD_TYPE): ByteArray {
        val p = ByteArray(12 + payload.bytes.size)
        p[0] = 0x80.toByte()
        p[1] = ((if (payload.marker) 0x80 else 0) or (payloadType and 0x7f)).toByte()
        p[2] = (seq shr 8).toByte(); p[3] = seq.toByte()
        p[4] = (timestamp shr 24).toByte(); p[5] = (timestamp shr 16).toByte()
        p[6] = (timestamp shr 8).toByte(); p[7] = timestamp.toByte()
        p[8] = (ssrc shr 24).toByte(); p[9] = (ssrc shr 16).toByte()
        p[10] = (ssrc shr 8).toByte(); p[11] = ssrc.toByte()
        System.arraycopy(payload.bytes, 0, p, 12, payload.bytes.size)
        return p
    }

    /** Extract SPS (7) and PPS (8) NAL bodies (without start codes) from an access unit. */
    fun parameterSets(annexB: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null; var pps: ByteArray? = null
        for (r in H264Bitstream.nalRanges(annexB)) {
            if (r.type == 7 && sps == null) sps = annexB.copyOfRange(r.start, r.end)
            if (r.type == 8 && pps == null) pps = annexB.copyOfRange(r.start, r.end)
        }
        return sps to pps
    }
}

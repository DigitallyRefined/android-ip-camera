package com.github.digitallyrefined.androidipcamera.helpers

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Malformed or oversized RTSP input; the connection answers 400 and closes. */
internal class RtspProtocolException(message: String) : IOException(message)

/** One RTSP request (RFC 2326). Header names are matched case-insensitively. */
internal class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    private val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    /** CSeq reduced to its digits, so echoing it back can never inject a header. */
    val cseq: String? get() = header("cseq")?.filter { it.isDigit() }?.take(10)?.takeIf { it.isNotEmpty() }

    /** Session id without the optional `;timeout=` suffix. */
    val sessionId: String? get() = header("session")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
}

/** What arrives on an RTSP control connection: a request, or a `$`-framed interleaved packet. */
internal sealed class RtspInbound {
    class Request(val request: RtspRequest) : RtspInbound()

    /** RTCP receiver reports from TCP-interleaved clients; only their arrival matters. */
    class Interleaved(val channel: Int, val length: Int) : RtspInbound()
}

/**
 * Reads RTSP requests and interleaved binary frames off a control connection. Line, header and
 * body sizes are capped so a misbehaving client cannot make the server buffer unbounded data.
 */
internal class RtspRequestReader(private val input: InputStream) {
    companion object {
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_BODY_BYTES = 64 * 1024
    }

    private val line = ByteArrayOutputStream(256)

    /** Next message, or null once the peer has closed the connection. */
    fun read(): RtspInbound? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            if (first == '$'.code) {
                val channel = input.read()
                val high = input.read()
                val low = input.read()
                if (channel < 0 || high < 0 || low < 0) return null
                val length = (high shl 8) or low
                skipFully(length)
                return RtspInbound.Interleaved(channel, length)
            }
            // Tolerate blank lines between messages (some clients send a bare CRLF as keepalive).
            if (first == '\r'.code || first == '\n'.code) continue
            val requestLine = readLine(first) ?: return null
            return RtspInbound.Request(readRequest(requestLine))
        }
    }

    private fun readRequest(requestLine: String): RtspRequest {
        val parts = requestLine.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3 || !parts[2].startsWith("RTSP/")) {
            throw RtspProtocolException("Malformed request line")
        }
        val headers = LinkedHashMap<String, String>()
        var lastName: String? = null
        while (true) {
            val headerLine = readLine() ?: throw EOFException("Connection closed inside request headers")
            if (headerLine.isEmpty()) break
            if (headerLine[0] == ' ' || headerLine[0] == '\t') {
                // Obsolete line folding: a continuation of the previous header.
                val name = lastName ?: throw RtspProtocolException("Malformed header")
                headers[name] = headers[name] + " " + headerLine.trim()
                continue
            }
            if (headers.size >= MAX_HEADERS) throw RtspProtocolException("Too many headers")
            val colon = headerLine.indexOf(':')
            if (colon <= 0) throw RtspProtocolException("Malformed header")
            val name = headerLine.substring(0, colon).trim().lowercase(Locale.US)
            headers[name] = headerLine.substring(colon + 1).trim()
            lastName = name
        }
        val length = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw RtspProtocolException("Invalid Content-Length")
        } ?: 0
        if (length !in 0..MAX_BODY_BYTES) throw RtspProtocolException("Request body too large")
        val body = ByteArray(length)
        readFully(body)
        return RtspRequest(parts[0].uppercase(Locale.US), parts[1], parts[2], headers, body)
    }

    /** One CRLF- (or LF-) terminated line, or null on end of stream. */
    private fun readLine(firstByte: Int = -1): String? {
        line.reset()
        var b = if (firstByte >= 0) firstByte else input.read()
        while (b >= 0 && b != '\n'.code) {
            if (line.size() >= MAX_LINE_BYTES) throw RtspProtocolException("Line too long")
            line.write(b)
            b = input.read()
        }
        if (b < 0) return null
        val bytes = line.toByteArray()
        val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, length, StandardCharsets.ISO_8859_1)
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("Connection closed inside request body")
            offset += n
        }
    }

    private fun skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            if (input.read() < 0) throw EOFException("Connection closed inside interleaved frame")
            remaining--
        }
    }
}

/** The alternative this server accepted from a SETUP request's Transport header. */
internal data class RtspTransport(
    val tcp: Boolean,
    /** Interleaved RTP/RTCP channel pair requested by a TCP client (null: server picks). */
    val interleaved: Pair<Int, Int>?,
    /** Client RTP/RTCP ports for UDP. */
    val clientPorts: Pair<Int, Int>?
) {
    companion object {
        /**
         * First alternative this server can serve: unicast RTP/AVP over UDP (with client_port) or
         * interleaved over TCP. Multicast and RECORD transports are skipped; null if none is usable.
         */
        fun parse(header: String): RtspTransport? {
            for (spec in header.split(',')) {
                val params = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (params.isEmpty()) continue
                val tcp = when (params[0].uppercase(Locale.US)) {
                    "RTP/AVP", "RTP/AVP/UDP" -> false
                    "RTP/AVP/TCP" -> true
                    else -> continue
                }
                var multicast = false
                var playMode = true
                var interleaved: Pair<Int, Int>? = null
                var clientPorts: Pair<Int, Int>? = null
                for (param in params.drop(1)) {
                    val key = param.substringBefore('=').trim().lowercase(Locale.US)
                    val value = param.substringAfter('=', "").trim().trim('"')
                    when (key) {
                        "multicast" -> multicast = true
                        "interleaved" -> interleaved = parseRange(value, 0..255)
                        "client_port" -> clientPorts = parseRange(value, 1..65535)
                        "mode" -> playMode = value.equals("play", ignoreCase = true)
                    }
                }
                if (multicast || !playMode) continue
                if (tcp) return RtspTransport(true, interleaved, null)
                if (clientPorts != null) return RtspTransport(false, null, clientPorts)
            }
            return null
        }

        /** `a-b`, or a single `a` meaning `a-(a+1)`. */
        private fun parseRange(value: String, valid: IntRange): Pair<Int, Int>? {
            val first = value.substringBefore('-').trim().toIntOrNull() ?: return null
            val second = if (value.contains('-')) {
                value.substringAfter('-').trim().toIntOrNull() ?: return null
            } else {
                first + 1
            }
            return if (first in valid && second in valid) first to second else null
        }
    }
}

/** Credentials RTSP requests are checked against (the same ones as the HTTP server). */
internal sealed class RtspCredentials {
    /** Authentication is switched off in the settings. */
    object Disabled : RtspCredentials()

    /** Authentication is on but no valid username/password is stored: every request is refused. */
    object NotConfigured : RtspCredentials()

    data class Required(val username: String, val password: String) : RtspCredentials()
}

/** HTTP Digest authentication (RFC 2617, MD5) as used by RTSP clients. */
internal object RtspDigestAuth {
    const val REALM = "Android IP Camera"

    /** Parameters of a `Digest k=v, k="quoted, value"` header (scheme optional). Keys are lower-case. */
    fun parseParams(header: String): Map<String, String> {
        val text = header.trim().let {
            if (it.startsWith("Digest ", ignoreCase = true)) it.substring(7) else it
        }
        val params = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i] == ',' || text[i].isWhitespace())) i++
            val keyStart = i
            while (i < text.length && text[i] != '=' && text[i] != ',') i++
            val key = text.substring(keyStart, i).trim().lowercase(Locale.US)
            if (i >= text.length || text[i] != '=') continue
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            val value = StringBuilder()
            if (i < text.length && text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < text.length) i++
                    value.append(text[i])
                    i++
                }
                i++ // closing quote
            } else {
                while (i < text.length && text[i] != ',') value.append(text[i++])
            }
            if (key.isNotEmpty()) params[key] = value.toString().trim()
        }
        return params
    }

    /** Expected `response` value; [qop] (with [nc] and [cnonce]) only when the client used it. */
    fun response(
        username: String,
        password: String,
        realm: String,
        method: String,
        uri: String,
        nonce: String,
        qop: String? = null,
        nc: String? = null,
        cnonce: String? = null
    ): String {
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        return if (qop != null) {
            md5Hex("$ha1:$nonce:${nc ?: ""}:${cnonce ?: ""}:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }
    }

    fun md5Hex(text: String): String =
        hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8)))
}

/**
 * Stateless Digest nonces: issue time plus a random salt, signed with an HMAC so any connection
 * can verify a nonce without the server keeping a list of the ones it handed out.
 */
internal class RtspNonces(private val clockMs: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private val key = ByteArray(32).also { random.nextBytes(it) }

    fun issue(): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val body = String.format(Locale.US, "%016x", clockMs()) + hex(salt)
        return body + sign(body)
    }

    /** Milliseconds since this server issued [nonce], or null if it is malformed or forged. */
    fun ageMs(nonce: String): Long? {
        if (nonce.length != 64) return null
        val body = nonce.substring(0, 32)
        if (!MessageDigest.isEqual(sign(body).toByteArray(), nonce.substring(32).toByteArray())) return null
        val issued = body.substring(0, 16).toLongOrNull(16) ?: return null
        return clockMs() - issued
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(body.toByteArray(StandardCharsets.US_ASCII))).substring(0, 32)
    }
}

/** AAC-LC stream parameters, as announced in the SDP. */
internal data class AacFormat(val sampleRate: Int, val channels: Int) {
    /** ISO/IEC 14496-3 AudioSpecificConfig (AAC-LC) as the hex string SDP `config=` expects. */
    fun audioSpecificConfig(): String {
        val frequencyIndex = SAMPLE_RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 15
        val config = (AAC_LC shl 11) or (frequencyIndex shl 7) or (channels shl 3)
        return String.format(Locale.US, "%04x", config)
    }

    private companion object {
        const val AAC_LC = 2
        val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }
}

/** Session description returned by DESCRIBE. Video is track 0, audio (when offered) track 1. */
internal object RtspSdp {
    const val VIDEO_TRACK = 0
    const val AUDIO_TRACK = 1
    const val VIDEO_PAYLOAD_TYPE = 96
    const val AUDIO_PAYLOAD_TYPE = 97
    const val VIDEO_CLOCK_RATE = 90_000

    fun build(originAddress: String, sessionId: Long, sps: ByteArray?, pps: ByteArray?, audio: AacFormat?): String {
        val addressType = if (originAddress.contains(':')) "IP6" else "IP4"
        val fmtp = StringBuilder("packetization-mode=1")
        if (sps != null && sps.size >= 4) {
            // profile_idc, constraint flags and level_idc straight from the SPS.
            fmtp.append(";profile-level-id=").append(hex(sps.copyOfRange(1, 4)))
        }
        if (sps != null && pps != null) {
            fmtp.append(";sprop-parameter-sets=").append(RtspBase64.encode(sps)).append(',').append(RtspBase64.encode(pps))
        }
        val lines = mutableListOf(
            "v=0",
            "o=- $sessionId $sessionId IN $addressType $originAddress",
            "s=Android IP Camera",
            "c=IN $addressType ${if (addressType == "IP6") "::" else "0.0.0.0"}",
            "t=0 0",
            "a=tool:Android IP Camera",
            "a=range:npt=0-",
            "a=control:*",
            "m=video 0 RTP/AVP $VIDEO_PAYLOAD_TYPE",
            "a=rtpmap:$VIDEO_PAYLOAD_TYPE H264/$VIDEO_CLOCK_RATE",
            "a=fmtp:$VIDEO_PAYLOAD_TYPE $fmtp",
            "a=control:trackID=$VIDEO_TRACK"
        )
        if (audio != null) {
            lines += "m=audio 0 RTP/AVP $AUDIO_PAYLOAD_TYPE"
            lines += "a=rtpmap:$AUDIO_PAYLOAD_TYPE MPEG4-GENERIC/${audio.sampleRate}/${audio.channels}"
            lines += "a=fmtp:$AUDIO_PAYLOAD_TYPE streamtype=5;profile-level-id=15;mode=AAC-hbr;" +
                "config=${audio.audioSpecificConfig()};sizelength=13;indexlength=3;indexdeltalength=3"
            lines += "a=control:trackID=$AUDIO_TRACK"
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }
}

/** Base64 without android.util or java.util.Base64 (API 26+), so it also runs in JVM tests. */
internal object RtspBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < data.size) ALPHABET[((b1 and 0x0f) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }

    /** Decoded bytes, or null if [text] is not valid Base64. */
    fun decode(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.length % 4 != 0) return null
        val out = ByteArrayOutputStream(clean.length / 4 * 3)
        var i = 0
        while (i < clean.length) {
            val c = IntArray(4)
            for (j in 0 until 4) {
                val ch = clean[i + j]
                c[j] = if (ch == '=') -1 else ALPHABET.indexOf(ch).takeIf { it >= 0 } ?: return null
            }
            val last = i + 4 == clean.length
            if (c[0] < 0 || c[1] < 0 || (c[2] < 0 && c[3] >= 0) || ((c[2] < 0 || c[3] < 0) && !last)) return null
            out.write((c[0] shl 2) or (c[1] shr 4))
            if (c[2] >= 0) out.write(((c[1] and 0x0f) shl 4) or (c[2] shr 2))
            if (c[3] >= 0) out.write(((c[2] and 0x03) shl 6) or c[3])
            i += 4
        }
        return out.toByteArray()
    }
}

internal fun hex(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0f])
    }
    return out.toString()
}

package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter

/** A single resolved byte range; [endInclusive] follows the HTTP convention. */
internal data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

internal sealed class RangeRequest {
    /** No usable Range header: send the whole file. */
    data object Full : RangeRequest()

    data class Partial(val range: ByteRange) : RangeRequest()

    /** The range lies outside the file: answer 416. */
    data object Unsatisfiable : RangeRequest()
}

/**
 * Parses a single `Range: bytes=...` header (RFC 7233). Unsupported or
 * malformed forms fall back to the whole file; an out-of-bounds range is
 * reported as unsatisfiable so the caller can answer 416.
 */
internal fun parseRangeHeader(header: String?, size: Long): RangeRequest {
    val value = header?.trim().orEmpty()
    if (value.isEmpty() || !value.startsWith("bytes=", ignoreCase = true)) {
        return RangeRequest.Full
    }
    val spec = value.substring("bytes=".length).trim()
    // Multiple ranges are legal but the relay only ever asks for one.
    if (spec.isEmpty() || spec.contains(',')) return RangeRequest.Full
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeRequest.Full
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    if (startText.isEmpty()) {
        // Suffix form: bytes=-N, the last N bytes.
        val suffix = endText.toLongOrNull() ?: return RangeRequest.Full
        if (suffix <= 0) return RangeRequest.Unsatisfiable
        if (size <= 0) return RangeRequest.Unsatisfiable
        val start = maxOf(0L, size - suffix)
        return RangeRequest.Partial(ByteRange(start, size - 1))
    }

    val start = startText.toLongOrNull() ?: return RangeRequest.Full
    if (start < 0) return RangeRequest.Full
    if (start >= size) return RangeRequest.Unsatisfiable
    val end = if (endText.isEmpty()) {
        size - 1
    } else {
        endText.toLongOrNull() ?: return RangeRequest.Full
    }
    if (end < start) return RangeRequest.Full
    return RangeRequest.Partial(ByteRange(start, minOf(end, size - 1)))
}

/**
 * HTTP serving for the recording-file endpoints:
 *
 *  - `GET /files`                          folder browser (static shell: assets/files.html)
 *  - `GET /files.json?dir=<relative path>` listing of one folder as JSON
 *  - `GET|HEAD /files/<relative path>`     play MP4 inline, download other files
 *  - `DELETE /files/<relative path>`       delete a file, or a folder recursively
 *
 * File downloads honour a single `Range: bytes=...` header so the relay and
 * the browser's native video player can read an MP4 progressively instead of
 * downloading it in full.
 *
 * Paths are relative to the configured recordings root. Folder names appear as extra
 * slash-separated segments, e.g. `/files/2026/11/09/2026-11-09-093000.mp4`.
 *
 * Storage access (MediaStore vs legacy file I/O) lives in [RecordingsHelper];
 * this class only translates HTTP requests against it.
 */
class FileManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    /**
     * Handles /files requests. Returns false when [path] is not a files route,
     * so the caller can fall through to its other handlers.
     */
    fun handleRequest(
        httpMethod: String,
        path: String,
        uri: String,
        headers: List<String>,
        writer: PrintWriter,
        outputStream: OutputStream
    ): Boolean {
        if (path != "/files" && path != "/files.json" && !path.startsWith("/files/")) return false

        if (path == "/files") {
            if (httpMethod != "GET") {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nConnection: close\r\n\r\n")
                writer.flush()
                return true
            }
            serveIndex(writer, outputStream)
            return true
        }

        if (path == "/files.json") {
            if (httpMethod != "GET") {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nConnection: close\r\n\r\n")
                writer.flush()
                return true
            }
            val dir = queryParameter(uri, "dir")
            val normalized = RecordingsHelper.normalizeRelativePath(dir)
            if (normalized == null) {
                writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n")
                writer.print("""{"error":"invalid_path"}""")
                writer.flush()
                return true
            }
            serveFilesJson(writer, normalized)
            return true
        }

        serveFile(httpMethod, path, headers, writer, outputStream)
        return true
    }

    /** Case-insensitive header lookup over the raw request header lines. */
    private fun headerValue(headers: List<String>, name: String): String? {
        for (header in headers) {
            val separator = header.indexOf(':')
            if (separator <= 0) continue
            if (!header.substring(0, separator).trim().equals(name, ignoreCase = true)) continue
            return header.substring(separator + 1).trim()
        }
        return null
    }

    private fun queryParameter(uri: String, name: String): String? = try {
        Uri.parse(uri).getQueryParameter(name)
    } catch (_: Exception) {
        null
    }

    private fun serveIndex(writer: PrintWriter, outputStream: OutputStream) {
        val html = try {
            context.assets.open("files.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<!DOCTYPE html><html><body><h1>Recordings</h1><p>Error loading interface.</p></body></html>"
        }
        val body = html.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: text/html; charset=utf-8\r\n")
        writer.print("Content-Length: ${body.size}\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        // PrintWriter buffers; flush it before writing raw bytes or the body hits the wire first
        writer.flush()
        outputStream.write(body)
        outputStream.flush()
    }

    /** Serves one folder listing as JSON; assets/files.html renders it client-side. */
    private fun serveFilesJson(writer: PrintWriter, dir: String) {
        val entries = JSONArray().apply {
            RecordingsHelper.list(context, dir).forEach { entry ->
                put(JSONObject().apply {
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("lastModifiedMs", entry.lastModifiedMs)
                })
            }
        }
        val json = JSONObject().apply {
            put("folder", RecordingsHelper.folderLabel(context))
            put("path", dir)
            put("entries", entries)
        }
        writer.print("HTTP/1.1 200 OK\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(json.toString())
        writer.flush()
    }

    /**
     * Serves a single file: GET/HEAD plays an MP4 inline (with byte-range
     * support) and downloads other file types; DELETE removes a file — a folder
     * path is deleted recursively.
     */
    private fun serveFile(
        httpMethod: String,
        path: String,
        headers: List<String>,
        writer: PrintWriter,
        outputStream: OutputStream
    ) {
        val relativePath = try {
            // Uri.decode (not URLDecoder): plain percent-decoding, leaves "+" intact
            Uri.decode(path.removePrefix("/files/"))
        } catch (_: Exception) {
            null
        }
        val normalized = RecordingsHelper.normalizeRelativePath(relativePath)
        if (normalized.isNullOrEmpty()) {
            writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInvalid file name\r\n")
            writer.flush()
            return
        }
        val displayName = normalized.substringAfterLast('/')

        when (httpMethod) {
            "GET", "HEAD" -> {
                val recording = RecordingsHelper.open(context, normalized)
                if (recording == null) {
                    writer.print("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nNot Found\r\n")
                    writer.flush()
                    return
                }
                try {
                    val rangeRequest = parseRangeHeader(
                        headerValue(headers, "Range"),
                        recording.sizeBytes
                    )
                    if (rangeRequest is RangeRequest.Unsatisfiable) {
                        writer.print("HTTP/1.1 416 Range Not Satisfiable\r\n")
                        writer.print("Content-Range: bytes */${recording.sizeBytes}\r\n")
                        writer.print("Accept-Ranges: bytes\r\n")
                        writer.print("Content-Length: 0\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.flush()
                        return
                    }
                    val partial = rangeRequest as? RangeRequest.Partial
                    val start = partial?.range?.start ?: 0L
                    val length = partial?.range?.length ?: recording.sizeBytes
                    // Recordings play inline; other files stay a download. The
                    // listing page adds an explicit download link for saving.
                    val playsInline = normalized.endsWith(".mp4", ignoreCase = true)
                    val disposition = if (playsInline) "inline" else "attachment"
                    val contentType = if (playsInline) "video/mp4" else "application/octet-stream"

                    if (partial != null) {
                        writer.print("HTTP/1.1 206 Partial Content\r\n")
                        writer.print("Content-Range: bytes $start-${partial.range.endInclusive}/${recording.sizeBytes}\r\n")
                    } else {
                        writer.print("HTTP/1.1 200 OK\r\n")
                    }
                    writer.print("Content-Type: $contentType\r\n")
                    writer.print("X-Content-Type-Options: nosniff\r\n")
                    writer.print("Accept-Ranges: bytes\r\n")
                    writer.print("Content-Length: $length\r\n")
                    writer.print("Content-Disposition: $disposition; filename=\"${displayName.replace("\"", "")}\"\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    if (httpMethod == "HEAD") return

                    recording.stream.use { input ->
                        skipFully(input, start)
                        copyExactly(input, outputStream, length)
                    }
                    outputStream.flush()
                } catch (e: IOException) {
                    onLog("File download aborted ($normalized): ${e.message}")
                } finally {
                    try { recording.stream.close() } catch (_: Exception) {}
                }
            }

            "DELETE" -> {
                val result = RecordingsHelper.delete(context, normalized)
                val status = when (result) {
                    RecordingsHelper.DeleteResult.DELETED -> "200 OK"
                    RecordingsHelper.DeleteResult.NOT_FOUND -> "404 Not Found"
                    RecordingsHelper.DeleteResult.FAILED -> "500 Internal Server Error"
                }
                val json = when (result) {
                    RecordingsHelper.DeleteResult.DELETED -> """{"deleted":true}"""
                    RecordingsHelper.DeleteResult.NOT_FOUND -> """{"error":"not_found"}"""
                    RecordingsHelper.DeleteResult.FAILED -> """{"error":"delete_failed"}"""
                }
                onLog("DELETE /files/$normalized -> $status")
                writer.print("HTTP/1.1 $status\r\n")
                writer.print("Content-Type: application/json\r\nConnection: close\r\n\r\n")
                writer.print(json)
                writer.flush()
            }

            else -> {
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD, DELETE\r\nConnection: close\r\n\r\n")
                writer.flush()
            }
        }
    }

    /**
     * Skips to the start of a requested range. ContentResolver streams are not
     * always seekable, so fall back to reading and discarding when skip()
     * cannot advance.
     */
    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        if (remaining <= 0) return
        val discard = ByteArray(16 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (read == -1) throw IOException("Recording ended before the requested range")
            remaining -= read
        }
    }

    private fun copyExactly(input: InputStream, outputStream: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) throw IOException("Recording ended before the requested range")
            outputStream.write(buffer, 0, read)
            remaining -= read
        }
    }
}

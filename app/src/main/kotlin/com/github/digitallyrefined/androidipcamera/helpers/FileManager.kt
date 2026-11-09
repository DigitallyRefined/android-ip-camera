package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter

/**
 * HTTP serving for the recording-file endpoints:
 *
 *  - `GET /files`                          folder browser (static shell: assets/files.html)
 *  - `GET /files.json?dir=<relative path>` listing of one folder as JSON
 *  - `GET /files/<relative path>`          download (`Content-Disposition: attachment`)
 *  - `DELETE /files/<relative path>`       delete a file, or a folder recursively
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

        serveFile(httpMethod, path, writer, outputStream)
        return true
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
     * Serves a single file: GET downloads it (attachment, never inline playback),
     * DELETE removes it — a folder path is deleted recursively.
     */
    private fun serveFile(
        httpMethod: String,
        path: String,
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
            "GET" -> {
                val recording = RecordingsHelper.open(context, normalized)
                if (recording == null) {
                    writer.print("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nNot Found\r\n")
                    writer.flush()
                    return
                }
                try {
                    writer.print("HTTP/1.1 200 OK\r\n")
                    // octet-stream + attachment forces a download instead of browser video playback
                    writer.print("Content-Type: application/octet-stream\r\n")
                    writer.print("Content-Length: ${recording.sizeBytes}\r\n")
                    writer.print("Content-Disposition: attachment; filename=\"${displayName.replace("\"", "")}\"\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    recording.stream.use { input -> input.copyTo(outputStream) }
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
                writer.print("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, DELETE\r\nConnection: close\r\n\r\n")
                writer.flush()
            }
        }
    }
}

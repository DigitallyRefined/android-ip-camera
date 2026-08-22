package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter

/**
 * HTTP serving for the recording-file endpoints:
 *
 *  - `GET /files`               folder index page (static shell: assets/files.html, loads /files.json)
 *  - `GET /files.json`          folder listing as JSON
 *  - `GET /files/<filename>`    download (`Content-Disposition: attachment`)
 *  - `DELETE /files/<filename>` delete, JSON result
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
    fun handleRequest(httpMethod: String, path: String, writer: PrintWriter, outputStream: OutputStream): Boolean {
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
            serveFilesJson(writer)
            return true
        }

        serveFile(httpMethod, path, writer, outputStream)
        return true
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

    /** Serves the folder listing as JSON; assets/files.html renders it client-side. */
    private fun serveFilesJson(writer: PrintWriter) {
        val files = JSONArray().apply {
            RecordingsHelper.list(context).forEach { file ->
                put(JSONObject().apply {
                    put("name", file.name)
                    put("sizeBytes", file.sizeBytes)
                    put("lastModifiedMs", file.lastModifiedMs)
                })
            }
        }
        val json = JSONObject().apply {
            put("folder", RecordingsHelper.relativePath)
            put("files", files)
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
     * DELETE removes it from the folder.
     */
    private fun serveFile(
        httpMethod: String,
        path: String,
        writer: PrintWriter,
        outputStream: OutputStream
    ) {
        val fileName = try {
            // Uri.decode (not URLDecoder): plain percent-decoding, leaves "+" intact
            android.net.Uri.decode(path.removePrefix("/files/"))
        } catch (_: Exception) {
            null
        }
        if (fileName == null || !RecordingsHelper.isValidFileName(fileName)) {
            writer.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInvalid file name\r\n")
            writer.flush()
            return
        }

        when (httpMethod) {
            "GET" -> {
                val recording = RecordingsHelper.open(context, fileName)
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
                    writer.print("Content-Disposition: attachment; filename=\"${fileName.replace("\"", "")}\"\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    recording.stream.use { input -> input.copyTo(outputStream) }
                    outputStream.flush()
                } catch (e: IOException) {
                    onLog("File download aborted ($fileName): ${e.message}")
                } finally {
                    try { recording.stream.close() } catch (_: Exception) {}
                }
            }

            "DELETE" -> {
                val result = RecordingsHelper.delete(context, fileName)
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
                onLog("DELETE /files/$fileName -> $status")
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

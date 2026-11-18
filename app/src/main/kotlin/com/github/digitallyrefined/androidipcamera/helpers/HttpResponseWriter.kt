package com.github.digitallyrefined.androidipcamera.helpers

import java.io.OutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/**
 * Writes a complete HTTP response with an explicit Content-Length. A close-delimited body is
 * legal HTTP/1.1, but some clients (and local traffic filters in front of them) treat the
 * socket close as a connection reset, especially for larger HTML/JSON bodies. Framing every
 * finite response avoids that whole class of bug.
 *
 * [extraHeaders] must already be CRLF-terminated header lines, e.g. "Cache-Control: no-store\r\n".
 */
internal fun writeFixedResponse(
    writer: PrintWriter,
    outputStream: OutputStream,
    status: String,
    contentType: String?,
    body: ByteArray,
    extraHeaders: String = ""
) {
    writer.print("HTTP/1.1 $status\r\n")
    if (contentType != null) writer.print("Content-Type: $contentType\r\n")
    writer.print("Content-Length: ${body.size}\r\n")
    if (extraHeaders.isNotEmpty()) writer.print(extraHeaders)
    writer.print("Connection: close\r\n\r\n")
    writer.flush()
    outputStream.write(body)
    outputStream.flush()
}

internal fun writeFixedResponse(
    writer: PrintWriter,
    outputStream: OutputStream,
    status: String,
    contentType: String?,
    body: String,
    extraHeaders: String = ""
) = writeFixedResponse(
    writer,
    outputStream,
    status,
    contentType,
    body.toByteArray(StandardCharsets.UTF_8),
    extraHeaders
)

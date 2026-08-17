package io.mszymanski.orknux.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Stands in for `POST /api/plugins`, and takes the multipart body apart again.
 *
 * The point is that the body is parsed rather than merely recorded: a multipart written by
 * hand is easy to get subtly wrong — a missing CRLF, a boundary without its leading dashes —
 * in ways that a server would reject and a string comparison would not notice.
 */
internal class StubUploadServer(
    private val status: Int = 200,
    private val body: String = "",
    private val template: String = "",
    private val templateStatus: Int = 200,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    var requestCount: Int = 0
        private set

    /** Counted apart from uploads, so a test can say which endpoint was asked. */
    var templateRequests: Int = 0
        private set
    var lastContentType: String? = null
        private set
    var lastCookie: String? = null
        private set

    /** The filename declared in the part's Content-Disposition. */
    var lastFilename: String? = null
        private set

    /** The part's content, exactly as it arrived. */
    var lastContent: ByteArray? = null
        private set

    /** The part's own name, which the server reads its `@RequestParam` from. */
    var lastPartName: String? = null
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        // The template first: a context on the more specific path wins over "/api/plugins".
        server.createContext("/api/plugins/template") { exchange ->
            try {
                templateRequests++
                lastCookie = exchange.requestHeaders.getFirst("Cookie")
                val bytes = template.toByteArray(StandardCharsets.UTF_8)
                if (templateStatus != 200 || bytes.isEmpty()) {
                    exchange.sendResponseHeaders(templateStatus, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "text/javascript")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/api/plugins") { exchange ->
            try {
                requestCount++
                lastContentType = exchange.requestHeaders.getFirst("Content-Type")
                lastCookie = exchange.requestHeaders.getFirst("Cookie")
                parse(exchange.requestBody.readAllBytes(), lastContentType)

                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                if (bytes.isEmpty()) {
                    exchange.sendResponseHeaders(status, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
    }

    private fun parse(raw: ByteArray, contentType: String?) {
        lastPartName = null
        lastFilename = null
        lastContent = null

        val boundary = contentType?.substringAfter("boundary=", "")?.takeIf { it.isNotEmpty() } ?: return
        val text = raw.toString(StandardCharsets.ISO_8859_1)

        // --boundary CRLF headers CRLF CRLF content CRLF --boundary--
        val opening = "--$boundary\r\n"
        if (!text.startsWith(opening)) return
        val headerEnd = text.indexOf("\r\n\r\n", opening.length)
        if (headerEnd < 0) return

        val headers = text.substring(opening.length, headerEnd)
        lastPartName = headers.substringAfter("name=\"", "").substringBefore("\"").takeIf { it.isNotEmpty() }
        lastFilename = headers.substringAfter("filename=\"", "").substringBefore("\"").takeIf { it.isNotEmpty() }

        val closing = "\r\n--$boundary--"
        val contentStart = headerEnd + 4
        val contentEnd = text.lastIndexOf(closing)
        if (contentEnd < contentStart) return
        lastContent = raw.copyOfRange(contentStart, contentEnd)
    }

    override fun close() = server.stop(0)
}

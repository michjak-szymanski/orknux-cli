package io.mszymanski.orknux.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Stands in for the server's `/graphql`, answering whatever the test hands it. Its point
 * is that GraphQL says no in a 200 body, so the interesting cases are not status codes.
 */
internal class StubGraphQlServer(
    private val status: Int = 200,
    private val body: String = """{"data":{"workspace":{"id":"7","name":"backend","description":null}}}""",
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    var requestCount: Int = 0
        private set
    var lastPath: String? = null
        private set
    var lastBody: String? = null
        private set
    var lastCookie: String? = null
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            try {
                requestCount++
                lastPath = exchange.requestURI.path
                lastCookie = exchange.requestHeaders.getFirst("Cookie")
                lastBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                respond(exchange)
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}

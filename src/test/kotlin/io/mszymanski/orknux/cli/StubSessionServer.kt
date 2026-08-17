package io.mszymanski.orknux.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Stands in for orknux-server's `/api/session`, so the tests exercise the real HTTP
 * client and the real JSON rather than a mock of them.
 *
 * Binds a loopback port the OS picks, so tests can run alongside a real server on 8080.
 */
internal class StubSessionServer(
    private val status: Int = 200,
    private val body: String = """{"username":"alice","roles":["ROLE_USERS"],"admin":false}""",
    private val setCookie: String? = "JSESSIONID=SESSION-A1B2C3; Path=/; HttpOnly; SameSite=Lax",
    /** Anything else the sign-in sets, which a browser would send back too. */
    private val alsoSetCookie: String? = null,
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** What the last request carried, for asserting on the wire format. */
    var requestCount: Int = 0
        private set
    var lastMethod: String? = null
        private set
    var lastPath: String? = null
        private set
    var lastBody: String? = null
        private set
    var lastContentType: String? = null
        private set

    /** Null when the request carried no cookie, which `server use` relies on. */
    var lastCookieHeader: String? = null
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            requestCount++
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            lastContentType = exchange.requestHeaders.getFirst("Content-Type")
            lastCookieHeader = exchange.requestHeaders.getFirst("Cookie")
            lastBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)

            setCookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
            alsoSetCookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
    }

    override fun close() = server.stop(0)
}

private inline fun HttpExchange.use(block: () -> Unit) {
    try {
        block()
    } finally {
        close()
    }
}

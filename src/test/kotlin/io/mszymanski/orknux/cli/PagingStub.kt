// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Answers each request with the next canned page, so a test can pin down that the client
 * asks for all of them rather than settling for the first.
 */
internal class PagingStub(private val pages: List<String>) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** One entry per request received, in order. */
    val bodies = mutableListOf<String>()

    val requestCount: Int get() = bodies.size
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            try {
                bodies += exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                // One past the end answers empty, which is what a real server would do.
                val page = pages.getOrElse(bodies.size - 1) {
                    """{"data":{"workspaces":{"content":[],"page":0,"size":100,"totalElements":0,"totalPages":0}}}"""
                }
                val bytes = page.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)
}

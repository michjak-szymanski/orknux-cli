// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Stands in for both halves of a chat: `/graphql` for the session and its history, and
 * `/api/chats/{id}/stream` for the answers.
 *
 * The stream is written frame by frame and flushed, as the server does, so the client is
 * exercised the way it will actually be used rather than handed one complete body.
 */
internal class StubChatServer(
    private val graphQlBody: String,
    /** One list of frames per message sent, in order. A frame is `event` to `data`. */
    private val answers: List<List<Pair<String, String>>> = emptyList(),
    private val streamStatus: Int = 200,
    private val streamProblem: String = "",
) : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    /** Every GraphQL request body, in order. */
    val graphQlRequests = mutableListOf<String>()

    /** Every chat message sent, in order: the path it went to and the body. */
    val sent = mutableListOf<Pair<String, String>>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/graphql") { exchange ->
            try {
                graphQlRequests += exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                respond(exchange, 200, graphQlBody, "application/json")
            } finally {
                exchange.close()
            }
        }
        server.createContext("/api/chats") { exchange ->
            try {
                val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                sent += exchange.requestURI.path to body
                if (streamStatus != 200) {
                    respond(exchange, streamStatus, streamProblem, "application/problem+json")
                } else {
                    streamAnswer(exchange, answers.getOrElse(sent.size - 1) { emptyList() })
                }
            } finally {
                exchange.close()
            }
        }
        server.executor = null
        server.start()
    }

    private fun streamAnswer(exchange: HttpExchange, frames: List<Pair<String, String>>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            for ((event, data) in frames) {
                out.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}

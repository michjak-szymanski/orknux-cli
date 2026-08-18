// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The SSE parser, on the frames a stream can actually contain. */
class ChatStreamClientTest {

    @Test
    fun `reports every frame in order`() {
        stub(
            listOf(
                "chunk" to """{"text":"a"}""",
                "chunk" to """{"text":"b"}""",
                "done" to """{"millis":12.5}""",
            ),
        ).use { server ->
            val events = collect(server)

            assertEquals(
                listOf(ChatEvent.Text("a"), ChatEvent.Text("b"), ChatEvent.Finished(12.5)),
                events,
            )
        }
    }

    @Test
    fun `reads a chunk carrying newlines and quotes`() {
        stub(listOf("chunk" to """{"text":"line one\nline \"two\""}""")).use { server ->
            assertEquals(listOf(ChatEvent.Text("line one\nline \"two\"")), collect(server))
        }
    }

    @Test
    fun `reports a failure frame`() {
        stub(listOf("error" to """{"reason":"no credentials"}""")).use { server ->
            assertEquals(listOf(ChatEvent.Failed("no credentials")), collect(server))
        }
    }

    /** An event this client has never heard of is not a reason to abandon the answer. */
    @Test
    fun `skips an event it does not know`() {
        stub(
            listOf(
                "heartbeat" to """{"at":"now"}""",
                "chunk" to """{"text":"still here"}""",
            ),
        ).use { server ->
            assertEquals(listOf(ChatEvent.Text("still here")), collect(server))
        }
    }

    @Test
    fun `skips a frame it cannot read`() {
        stub(listOf("chunk" to "not json", "chunk" to """{"text":"fine"}""")).use { server ->
            assertEquals(listOf(ChatEvent.Text("fine")), collect(server))
        }
    }

    @Test
    fun `turns a 400 into a refusal carrying the server's detail`() {
        StubChatServer(
            graphQlBody = "{}",
            streamStatus = 400,
            streamProblem = """{"status":400,"detail":"There is nothing to send"}""",
        ).use { server ->
            val failure = assertFailsWith<OperationRefused> { collect(server) }
            assertEquals("There is nothing to send", failure.message)
        }
    }

    @Test
    fun `turns a 404 into a not-found`() {
        StubChatServer(
            graphQlBody = "{}",
            streamStatus = 404,
            streamProblem = """{"status":404,"detail":"No chat with id 5"}""",
        ).use { server ->
            assertFailsWith<NotFound> { collect(server) }
        }
    }

    @Test
    fun `falls back to the body when it is not a problem detail`() {
        StubChatServer(graphQlBody = "{}", streamStatus = 400, streamProblem = "plain words").use { server ->
            val failure = assertFailsWith<OperationRefused> { collect(server) }
            assertContains(failure.message!!, "plain words")
        }
    }

    @Test
    fun `turns a 401 into an expired session`() {
        StubChatServer(graphQlBody = "{}", streamStatus = 401, streamProblem = "").use { server ->
            assertFailsWith<SessionExpired> { collect(server) }
        }
    }

    @Test
    fun `names an unexpected status`() {
        StubChatServer(graphQlBody = "{}", streamStatus = 503, streamProblem = "").use { server ->
            val failure = assertFailsWith<ServerUnreachable> { collect(server) }
            assertContains(failure.message!!, "503")
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubChatServer(graphQlBody = "{}").use { it.baseUrl }

        assertFailsWith<ServerUnreachable> {
            ChatStreamClient(deadUrl, "JSESSIONID=ABC").send("5", "hi") { }
        }
    }

    private fun stub(frames: List<Pair<String, String>>) =
        StubChatServer(graphQlBody = "{}", answers = listOf(frames))

    private fun collect(server: StubChatServer): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        ChatStreamClient(server.baseUrl, "JSESSIONID=ABC").send("5", "hi") { events += it }
        return events
    }
}

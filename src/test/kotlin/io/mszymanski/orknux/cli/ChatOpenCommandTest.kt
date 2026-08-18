// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatOpenCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `prints the chat and its history, then leaves at the end of input`() {
        StubChatServer(graphQlBody = chat(withHistory = true)).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = emptyList())

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Chat 5  Planning  (gemini-2.5-flash)")
            assertContains(result.out, "you> what shall we do")
            assertContains(result.out, "gemini-2.5-flash> Something sensible")
            assertTrue(server.sent.isEmpty(), "nothing should be sent when nothing is typed")
        }
    }

    @Test
    fun `sends what was typed and prints the answer as it streams`() {
        StubChatServer(
            graphQlBody = chat(),
            answers = listOf(
                listOf(
                    "chunk" to """{"text":"Hello"}""",
                    "chunk" to """{"text":", world"}""",
                    "done" to """{"millis":1234.0}""",
                ),
            ),
        ).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi"))

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // The chunks are joined with nothing between them: they are one answer.
            assertContains(result.out, "gemini-2.5-flash> Hello, world")
            assertContains(result.out, "(1.2s)")

            assertEquals("/api/chats/5/stream", server.sent.single().first)
            assertEquals("""{"text":"hi","attachmentIds":[]}""", server.sent.single().second)
        }
    }

    @Test
    fun `keeps the chat open after a model fails to answer`() {
        StubChatServer(
            graphQlBody = chat(),
            answers = listOf(
                listOf("error" to """{"reason":"There are no credentials to call this provider with"}"""),
                listOf("chunk" to """{"text":"Working now"}""", "done" to """{"millis":80.0}"""),
            ),
        ).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("first", "second"))

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "There are no credentials to call this provider with")
            // A failed answer is a turn, not the end of the conversation.
            assertContains(result.out, "Working now")
            assertEquals(2, server.sent.size)
            assertContains(result.out, "(80ms)")
        }
    }

    @Test
    fun `leaves on the exit words without sending them`() {
        for (word in listOf("/exit", "/quit", "/q")) {
            StubChatServer(graphQlBody = chat()).use { server ->
                signedIn(server.baseUrl)

                val result = open(server, typed = listOf(word, "never sent"))

                assertEquals(ExitCode.OK, result.exitCode, "$word: ${result.err}")
                assertTrue(server.sent.isEmpty(), "$word should not be sent")
            }
        }
    }

    /**
     * `"" | orkx chat open 5` from PowerShell arrives as a lone byte order mark. `U+FEFF` is
     * not whitespace, so trimming leaves it looking like something to say — and it was sent,
     * putting an invisible message into a real chat. Stripped where input is read, so the loop
     * sees a blank line as blank.
     */
    @Test
    fun `does not send a line that is only a byte order mark`() {
        StubChatServer(graphQlBody = chat()).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("\uFEFF", "\uFEFF   ")) {
                // The production reader strips it; this stands in for that reader.
                val lines = listOf("\uFEFF", "\uFEFF   ").iterator()
                it.readLine = { if (lines.hasNext()) lines.next().withoutByteOrderMark() else null }
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertTrue(server.sent.isEmpty(), "a byte order mark is not a message")
        }
    }

    @Test
    fun `keeps a message that merely starts with a byte order mark`() {
        StubChatServer(
            graphQlBody = chat(),
            answers = listOf(listOf("chunk" to """{"text":"ok"}""", "done" to """{"millis":5.0}""")),
        ).use { server ->
            signedIn(server.baseUrl)

            open(server, typed = emptyList()) {
                val lines = listOf("\uFEFFhello").iterator()
                it.readLine = { if (lines.hasNext()) lines.next().withoutByteOrderMark() else null }
            }

            assertEquals("""{"text":"hello","attachmentIds":[]}""", server.sent.single().second)
        }
    }

    @Test
    fun `ignores blank lines`() {
        StubChatServer(
            graphQlBody = chat(),
            answers = listOf(listOf("chunk" to """{"text":"ok"}""", "done" to """{"millis":10.0}""")),
        ).use { server ->
            signedIn(server.baseUrl)

            open(server, typed = listOf("", "   ", "something"))

            assertEquals(1, server.sent.size)
            assertContains(server.sent.single().second, """"text":"something"""")
        }
    }

    /**
     * The server calls these bad requests: no model chosen, an unusable agent, chat switched
     * off. None of them is fixed by typing again, so the session ends.
     */
    @Test
    fun `ends the session on a refusal, showing what the server said`() {
        StubChatServer(
            graphQlBody = chat(),
            streamStatus = 400,
            streamProblem = """{"type":"about:blank","title":"Bad Request","status":400,""" +
                """"detail":"This chat has no model to answer with; choose one first"}""",
        ).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi", "still here?"))

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "This chat has no model to answer with; choose one first")
            assertEquals(1, server.sent.size, "should not keep trying after a refusal")
        }
    }

    @Test
    fun `reports a chat that went away mid-session`() {
        StubChatServer(
            graphQlBody = chat(),
            streamStatus = 404,
            streamProblem = """{"status":404,"detail":"No chat with id 5"}""",
        ).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi"))

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No chat with id 5")
        }
    }

    @Test
    fun `reports an expired session mid-session`() {
        StubChatServer(graphQlBody = chat(), streamStatus = 401, streamProblem = "").use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi"))

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    /** Chats are one person's; somebody else's reads as absent, and that is all we may say. */
    @Test
    fun `reports a chat it cannot open`() {
        StubChatServer(graphQlBody = """{"data":{"chatSession":null}}""").use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi"))

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No chat 5 at ${server.baseUrl} that alice can open.")
            assertTrue(server.sent.isEmpty())
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        StubChatServer(graphQlBody = chat()).use { server ->
            val result = open(server, typed = emptyList())

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "Not signed in. Run 'orkx login' first.")
        }
    }

    @Test
    fun `refuses a chat id that cannot be one`() {
        StubChatServer(graphQlBody = chat()).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = emptyList(), id = "planning")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'planning' is not a chat id; those are numbers.")
            assertTrue(server.graphQlRequests.isEmpty())
        }
    }

    @Test
    fun `names an agent as the one answering, in preference to the model`() {
        val withAgent = """{"data":{"chatSession":{"id":"5","workspaceId":"1","title":"Planning",""" +
            """"modelId":"9","modelName":"gemini-2.5-flash","agentId":"3","agentName":"Tester"},""" +
            """"chatMessages":[]}}"""
        StubChatServer(
            graphQlBody = withAgent,
            answers = listOf(listOf("chunk" to """{"text":"yes"}""", "done" to """{"millis":5.0}""")),
        ).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = listOf("hi"))

            assertContains(result.out, "(agent Tester)")
            assertContains(result.out, "Tester> yes")
        }
    }

    /** Both null means whatever answered has been deleted; sending will be refused. */
    @Test
    fun `says so when a chat has nothing to answer with`() {
        val orphaned = """{"data":{"chatSession":{"id":"5","workspaceId":"1","title":"Planning"},""" +
            """"chatMessages":[]}}"""
        StubChatServer(graphQlBody = orphaned).use { server ->
            signedIn(server.baseUrl)

            val result = open(server, typed = emptyList())

            assertContains(result.out, "(nothing to answer with)")
        }
    }

    @Test
    fun `prompts when someone is there and not when the input is piped`() {
        StubChatServer(
            graphQlBody = chat(),
            answers = listOf(listOf("chunk" to """{"text":"ok"}""", "done" to """{"millis":5.0}""")),
        ).use { server ->
            signedIn(server.baseUrl)

            val piped = open(server, typed = listOf("hi")) { it.interactive = false }
            assertFalse(piped.out.contains("Type /exit"), piped.out)
            // Nothing echoed the typed line, so the transcript prints it.
            assertContains(piped.out, "you> hi")

            val attended = open(server, typed = listOf("hi")) { it.interactive = true }
            assertContains(attended.out, "Type /exit, or press Ctrl+D, to leave.")
        }
    }

    @Test
    fun `asks for the session and then its messages`() {
        StubChatServer(graphQlBody = chat(withHistory = true)).use { server ->
            signedIn(server.baseUrl)

            open(server, typed = emptyList())

            assertEquals(2, server.graphQlRequests.size)
            assertContains(server.graphQlRequests[0], "chatSession(id: \$id)")
            assertContains(server.graphQlRequests[1], "chatMessages(id: \$id)")
        }
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubChatServer(
            graphQlBody = chat(withHistory = true),
            answers = listOf(
                listOf("chunk" to """{"text":"ok"}""", "done" to """{"millis":5.0}"""),
                listOf("chunk" to """{"text":"ok"}""", "done" to """{"millis":5.0}"""),
            ),
        ).use { server ->
            signedIn(server.baseUrl)

            val plain = open(server, typed = listOf("hi")) { it.styleOverride = Style(enabled = false) }
            val coloured = open(server, typed = listOf("hi")) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun open(
        server: StubChatServer,
        typed: List<String>,
        id: String = "5",
        configure: (ChatOpenCommand) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val open = command.subcommands.getValue("chat").subcommands.getValue("open")
            .getCommand<ChatOpenCommand>()
        open.store = SessionStore(configHome)
        open.clientFactory = { _, cookie -> GraphQlClient(server.baseUrl, cookie) }
        open.streamFactory = { _, cookie -> ChatStreamClient(server.baseUrl, cookie) }
        // Nothing is attached during a test; the prompt is asserted on explicitly instead.
        open.interactive = false
        val lines = typed.iterator()
        open.readLine = { if (lines.hasNext()) lines.next() else null }
        configure(open)

        val exitCode = command.execute("chat", "open", id)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        /**
         * One body for both queries. `ignoreUnknownKeys` means the session request reads only
         * `chatSession` from it and the history request only `chatMessages`.
         */
        fun chat(withHistory: Boolean = false): String {
            val history = if (withHistory) {
                """[{"role":"user","content":"what shall we do"},""" +
                    """{"role":"assistant","content":"Something sensible"}]"""
            } else {
                "[]"
            }
            return """{"data":{"chatSession":{"id":"5","workspaceId":"1","title":"Planning",""" +
                """"modelId":"9","modelName":"gemini-2.5-flash","agentId":null,"agentName":null,""" +
                """"lastMessageAt":"2026-08-17T13:22:11+02:00"},"chatMessages":$history}}"""
        }
    }
}

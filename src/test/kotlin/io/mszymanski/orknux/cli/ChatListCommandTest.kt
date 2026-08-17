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

class ChatListCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ------------------------------------------------------------------- list

    @Test
    fun `lists the chats in the order the server gave them`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            val lines = result.out.trimEnd().lines()
            assertEquals("   ID  TITLE        ANSWERED BY   LAST MESSAGE", lines[0])
            // Pinned first, marked, exactly as the server ordered them.
            assertTrue(lines[1].startsWith("*  5   System Test"), lines[1])
            assertTrue(lines[2].startsWith("   1   Planning"), lines[2])
            assertTrue(lines[3].startsWith("   3   Quiet one"), lines[3])
        }
    }

    @Test
    fun `names an agent as what answers, in preference to the model`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server.baseUrl)

            assertContains(result.out, "agent Tester")
            assertContains(result.out, "gemini")
        }
    }

    /** `lastMessageAt` is null until something is said, which is worth saying. */
    @Test
    fun `says when a chat has never been used`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server.baseUrl)

            assertContains(result.out, "never")
        }
    }

    @Test
    fun `asks for the caller's chats in the workspace`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            list(server.baseUrl)

            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertContains(server.lastBody!!, "chatSessions(workspaceId: \$workspaceId)")
            assertContains(server.lastBody!!, """"workspaceId":"1"""")
        }
    }

    @Test
    fun `lets --workspace look elsewhere`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            list(server.baseUrl, "--workspace", "9")

            assertContains(server.lastBody!!, """"workspaceId":"9"""")
            assertEquals("1", SessionStore(configHome).read()?.workspaceId)
        }
    }

    @Test
    fun `reports having no chats without failing`() {
        StubGraphQlServer(body = """{"data":{"chatSessions":[]}}""").use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No chats of alice's in workspace 1.")
        }
    }

    @Test
    fun `asks for a workspace when none has been chosen`() {
        StubGraphQlServer(body = sessions()).use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))

            val result = list(server.baseUrl)

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "No workspace chosen.")
            assertEquals(0, server.requestCount)
        }
    }

    // ----------------------------------------------------------------- search

    @Test
    fun `finds a chat by name, without looking inside anything`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "plan")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Planning")
            assertFalse(result.out.contains("System Test"), result.out)
            // One request: the deeper search is a different question, and was not asked.
            assertEquals(1, server.requestCount)
            assertFalse(result.out.contains("MATCHED"), result.out)
        }
    }

    @Test
    fun `matches a name whatever its case`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            assertContains(search(server.baseUrl, "PLANNING").out, "Planning")
            assertContains(search(server.baseUrl, "system").out, "System Test")
        }
    }

    @Test
    fun `looks inside what was said when asked, and says which found what`() {
        PagingStub(listOf(sessions(), """{"data":{"chatsMentioning":["3"]}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "plan", "--messages")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "MATCHED")
            // Planning by its name, the quiet one by what was said in it.
            val planning = result.out.lines().first { it.contains("Planning") }
            val quiet = result.out.lines().first { it.contains("Quiet one") }
            assertTrue(planning.trimEnd().endsWith("name"), planning)
            assertTrue(quiet.trimEnd().endsWith("said"), quiet)

            assertEquals(2, server.requestCount)
            assertContains(server.bodies[1], "chatsMentioning(workspaceId: \$workspaceId, text: \$text)")
            assertContains(server.bodies[1], """"text":"plan"""")
        }
    }

    @Test
    fun `says when one chat matched both ways`() {
        PagingStub(listOf(sessions(), """{"data":{"chatsMentioning":["1"]}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "plan", "--messages")

            val planning = result.out.lines().first { it.contains("Planning") }
            assertTrue(planning.trimEnd().endsWith("name, said"), planning)
        }
    }

    @Test
    fun `keeps the server's order when the two searches are combined`() {
        PagingStub(listOf(sessions(), """{"data":{"chatsMentioning":["5","3"]}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "plan", "--messages")

            val lines = result.out.trimEnd().lines().drop(1)
            // The pin marker comes before the id, so it is taken off first.
            assertEquals(
                listOf("5", "1", "3"),
                lines.map { it.trim().removePrefix("*").trim().substringBefore(' ') },
            )
        }
    }

    @Test
    fun `finding nothing is an answer, not a failure`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "nowhere")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "is called anything like 'nowhere'")
            // Points at the deeper search, since that is the obvious next thing to try.
            assertContains(result.err, "--messages")
            assertEquals("", result.out.trim())
        }
    }

    @Test
    fun `does not suggest looking inside when it already did`() {
        PagingStub(listOf(sessions(), """{"data":{"chatsMentioning":[]}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "nowhere", "--messages")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "nor said it")
            assertFalse(result.err.contains("Try --messages"), result.err)
        }
    }

    @Test
    fun `refuses an empty search`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val result = search(server.baseUrl, "   ")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "There is nothing to search for.")
            assertEquals(0, server.requestCount)
        }
    }

    // ----------------------------------------------------------------- shared

    @Test
    fun `both want a session`() {
        val result = list("http://localhost:1")
        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in.")

        val searched = search("http://localhost:1", "x")
        assertEquals(ExitCode.REJECTED, searched.exitCode)
        assertContains(searched.err, "Not signed in.")
    }

    @Test
    fun `both report a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        inWorkspace(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, list(deadUrl).exitCode)
        assertEquals(ExitCode.UNREACHABLE, search(deadUrl, "x").exitCode)
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubGraphQlServer(body = sessions()).use { server ->
            inWorkspace(server.baseUrl)

            val plain = list(server.baseUrl) { it.styleOverride = Style(enabled = false) }
            val coloured = list(server.baseUrl) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun inWorkspace(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun list(
        server: String,
        vararg args: String,
        configure: (ChatListCommand) -> Unit = {},
    ): Result = execute(server, listOf("chat", "list") + args) { command ->
        (command as? ChatListCommand)?.let(configure)
    }

    private fun search(server: String, text: String, vararg args: String): Result =
        execute(server, listOf("chat", "search", text) + args) { }

    private fun execute(server: String, args: List<String>, configure: (Any) -> Unit): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val chat = command.subcommands.getValue("chat")
        val factory: (String, String) -> GraphQlClient = { _, cookie -> GraphQlClient(server, cookie) }

        chat.subcommands.getValue("list").getCommand<ChatListCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }
        chat.subcommands.getValue("search").getCommand<ChatSearchCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }

        val exitCode = command.execute(*args.toTypedArray())
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        /** Pinned first, then most recent: the order the server promises. */
        fun sessions(): String = """{"data":{"chatSessions":[""" +
            """{"id":"5","workspaceId":"1","title":"System Test","pinned":true,"modelId":"1",""" +
            """"modelName":"gemini","agentId":null,"agentName":null,""" +
            """"lastMessageAt":"2026-08-17T13:22:11+02:00"},""" +
            """{"id":"1","workspaceId":"1","title":"Planning","pinned":false,"modelId":"1",""" +
            """"modelName":"gemini","agentId":"3","agentName":"Tester",""" +
            """"lastMessageAt":"2026-08-16T13:22:11+02:00"},""" +
            """{"id":"3","workspaceId":"1","title":"Quiet one","pinned":false,"modelId":"1",""" +
            """"modelName":"gemini","agentId":null,"agentName":null,"lastMessageAt":null}]}}"""
    }
}

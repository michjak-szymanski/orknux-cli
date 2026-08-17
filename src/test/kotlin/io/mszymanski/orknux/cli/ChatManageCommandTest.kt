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

class ChatManageCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ----------------------------------------------------------------- create

    @Test
    fun `starts a chat with a model as the recipient`() {
        PagingStub(listOf(MODEL_9, NO_AGENT, started(model = "gemini"))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create", "--recipient", "9", "--name", "Planning")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Created Chat 6  Planning  (gemini)")
            assertContains(result.out, "Open it with 'orkx chat open 6'.")

            // The model goes into startChat itself; no second call is needed.
            assertEquals(3, server.requestCount)
            assertContains(server.bodies[2], "mutation StartChat")
            assertContains(server.bodies[2], """"workspaceId":"1"""")
            assertContains(server.bodies[2], """"title":"Planning"""")
            assertContains(server.bodies[2], """"modelId":"9"""")
        }
    }

    /** `startChat` takes no agent, so an agent recipient is a second call. */
    @Test
    fun `starts a chat and hands it to an agent`() {
        PagingStub(listOf(NO_MODEL, AGENT_3, started(), handedToAgent())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create", "--recipient", "3", "--name", "Planning")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "(agent Tester)")

            assertEquals(4, server.requestCount)
            assertContains(server.bodies[2], "mutation StartChat")
            assertFalse(server.bodies[2].contains(""""modelId":"""), server.bodies[2])
            assertContains(server.bodies[3], "mutation ChooseAgent")
            assertContains(server.bodies[3], """"agentId":"3"""")
        }
    }

    @Test
    fun `leaves the name and the recipient to the server when neither is given`() {
        PagingStub(listOf(started(title = "New chat"))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // One request: nothing to resolve, so nothing is looked up.
            assertEquals(1, server.requestCount)
            // Asserted as JSON keys: the selection set names these fields too.
            assertFalse(server.bodies[0].contains(""""title":"""), server.bodies[0])
            assertFalse(server.bodies[0].contains(""""modelId":"""), server.bodies[0])
        }
    }

    /**
     * Models and agents are separate catalogues with separate id sequences, so the same number
     * is very likely to be both. Guessing would quietly hand the chat to the wrong one.
     */
    @Test
    fun `refuses an id that names both a model and an agent`() {
        PagingStub(listOf(MODEL_9, AGENT_9)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create", "--recipient", "9")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'9' is both a model and an agent: gemini and Tester.")
            assertContains(result.err, "model:9 or agent:9")
            assertEquals(2, server.requestCount, "nothing should have been created")
        }
    }

    @Test
    fun `takes a prefix as the answer to which one`() {
        PagingStub(listOf(AGENT_9, started(), handedToAgent())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create", "--recipient", "agent:9")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // Only the agent was asked about: the prefix already said which.
            assertContains(server.bodies[0], "query Agent")
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `reports a recipient that is neither`() {
        PagingStub(listOf(NO_MODEL, NO_AGENT)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "create", "--recipient", "404")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No model or agent with id '404' that alice can use.")
        }
    }

    @Test
    fun `asks for a workspace when none has been chosen`() {
        PagingStub(emptyList()).use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))

            val result = run(server, "chat", "create")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "No workspace chosen.")
            assertEquals(0, server.requestCount)
        }
    }

    // ----------------------------------------------------------------- delete

    @Test
    fun `deletes when told to, without asking`() {
        PagingStub(listOf(DELETED)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "delete", "5", "--yes")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Deleted chat 5.")
            assertContains(server.bodies[0], "mutation DeleteChat")
            assertContains(server.bodies[0], """"id":"5"""")
        }
    }

    @Test
    fun `asks first when somebody is there, and deletes on yes`() {
        PagingStub(listOf(DELETED)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "delete", "5") { command ->
                (command as? ChatDeleteCommand)?.let {
                    it.interactive = true
                    it.readLine = { "y" }
                }
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Delete chat 5 and everything said in it? [y/N]")
            assertContains(result.out, "Deleted chat 5.")
        }
    }

    @Test
    fun `deletes nothing on anything but yes`() {
        for (answer in listOf("", "n", "no", "maybe")) {
            PagingStub(listOf(DELETED)).use { server ->
                inWorkspace(server.baseUrl)

                val result = run(server, "chat", "delete", "5") { command ->
                    (command as? ChatDeleteCommand)?.let {
                        it.interactive = true
                        it.readLine = { answer }
                    }
                }

                assertEquals(ExitCode.OK, result.exitCode, "'$answer': ${result.err}")
                assertContains(result.out, "Chat 5 was not deleted.")
                assertEquals(0, server.requestCount, "'$answer' should have deleted nothing")
            }
        }
    }

    /** Nobody to ask, and no `--yes`: a script deleting a conversation should say it meant to. */
    @Test
    fun `refuses to delete unasked when nothing is attached`() {
        PagingStub(listOf(DELETED)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "delete", "5")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "Pass --yes to say you meant to.")
            assertEquals(0, server.requestCount)
        }
    }

    /** The server answers false rather than failing when the chat is not there. */
    @Test
    fun `reports a chat that was not there to delete`() {
        PagingStub(listOf("""{"data":{"deleteChat":false}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "delete", "999", "--yes")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No chat 999 at ${server.baseUrl} to delete.")
        }
    }

    // ------------------------------------------------------ config set-recipient

    @Test
    fun `sets a model as the recipient`() {
        PagingStub(listOf(MODEL_9, NO_AGENT, chosenModel())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-recipient", "--chat-id", "5", "--recipient", "9")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Chat 5  Planning  (gemini)")
            assertContains(server.bodies[2], "mutation ChooseModel")
            assertContains(server.bodies[2], """"modelId":"9"""")
        }
    }

    @Test
    fun `sets an agent as the recipient`() {
        PagingStub(listOf(NO_MODEL, AGENT_3, handedToAgent())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-recipient", "--chat-id", "5", "--recipient", "3")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "(agent Tester)")
            assertContains(server.bodies[2], "mutation ChooseAgent")
        }
    }

    /** The server's own words: not active, another workspace, no model of its own. */
    @Test
    fun `passes on an agent the server will not use`() {
        val refusal = """{"errors":[{"message":"Tester is not active"}]}"""
        PagingStub(listOf(NO_MODEL, AGENT_3, refusal)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-recipient", "--chat-id", "5", "--recipient", "3")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "Tester is not active")
        }
    }

    @Test
    fun `wants both a chat and a recipient`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val missingRecipient = run(server, "chat", "config", "set-recipient", "--chat-id", "5")
            assertEquals(ExitCode.USAGE, missingRecipient.exitCode)
            assertContains(missingRecipient.err, "--recipient")

            val missingChat = run(server, "chat", "config", "set-recipient", "--recipient", "9")
            assertEquals(ExitCode.USAGE, missingChat.exitCode)
            assertContains(missingChat.err, "--chat-id")
        }
    }

    // ----------------------------------------------------------- config set-name

    @Test
    fun `renames a chat`() {
        PagingStub(listOf(renamed())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-name", "--chat-id", "5", "--name", "Quarterly")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Chat 5  Quarterly")
            assertContains(server.bodies[0], "mutation RenameChat")
            assertContains(server.bodies[0], """"title":"Quarterly"""")
        }
    }

    @Test
    fun `refuses an empty name without asking the server`() {
        PagingStub(listOf(renamed())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-name", "--chat-id", "5", "--name", "   ")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "A chat needs a name.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `refuses a chat id that cannot be one`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "chat", "config", "set-name", "--chat-id", "latest", "--name", "x")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'latest' is not a chat id; those are numbers.")
        }
    }

    // ----------------------------------------------------------------- shared

    @Test
    fun `every one of them wants a session`() {
        PagingStub(emptyList()).use { server ->
            for (args in listOf(
                listOf("chat", "create"),
                listOf("chat", "delete", "5", "--yes"),
                listOf("chat", "config", "set-recipient", "--chat-id", "5", "--recipient", "9"),
                listOf("chat", "config", "set-name", "--chat-id", "5", "--name", "x"),
            )) {
                val result = run(server, *args.toTypedArray())

                assertEquals(ExitCode.REJECTED, result.exitCode, args.toString())
                assertContains(result.err, "Not signed in.", message = args.toString())
            }
        }
    }

    @Test
    fun `colour adds nothing but colour`() {
        PagingStub(listOf(renamed(), renamed())).use { server ->
            inWorkspace(server.baseUrl)

            val args = arrayOf("chat", "config", "set-name", "--chat-id", "5", "--name", "Quarterly")
            val plain = run(server, *args) { (it as? ChatSetNameCommand)?.styleOverride = Style(enabled = false) }
            val coloured = run(server, *args) { (it as? ChatSetNameCommand)?.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun inWorkspace(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    /**
     * Wires whichever of the four is being driven. They share the same fields, so one
     * configure block reaches all of them; the cast in a test names the one it means.
     */
    private fun run(
        server: PagingStub,
        vararg args: String,
        configure: (Any) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val chat = command.subcommands.getValue("chat")
        val factory: (String, String) -> GraphQlClient = { _, cookie -> GraphQlClient(server.baseUrl, cookie) }

        chat.subcommands.getValue("create").getCommand<ChatCreateCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }
        chat.subcommands.getValue("delete").getCommand<ChatDeleteCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            // Nothing is attached during a test; each test says what it wants.
            interactive = false
            configure(this)
        }
        val config = chat.subcommands.getValue("config")
        config.subcommands.getValue("set-recipient").getCommand<ChatSetRecipientCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }
        config.subcommands.getValue("set-name").getCommand<ChatSetNameCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val MODEL_9 = """{"data":{"model":{"id":"9","name":"gemini"}}}"""
        const val AGENT_9 = """{"data":{"agent":{"id":"9","name":"Tester"}}}"""
        const val AGENT_3 = """{"data":{"agent":{"id":"3","name":"Tester"}}}"""
        const val NO_MODEL = """{"data":{"model":null}}"""
        const val NO_AGENT = """{"data":{"agent":null}}"""
        const val DELETED = """{"data":{"deleteChat":true}}"""

        fun started(title: String = "Planning", model: String? = null): String =
            """{"data":{"startChat":{"id":"6","workspaceId":"1","title":"$title",""" +
                """"modelId":${if (model == null) "null" else "\"9\""},""" +
                """"modelName":${if (model == null) "null" else "\"$model\""},""" +
                """"agentId":null,"agentName":null}}}"""

        fun handedToAgent(): String =
            """{"data":{"chooseChatAgent":{"id":"6","workspaceId":"1","title":"Planning",""" +
                """"modelId":"9","modelName":"gemini","agentId":"3","agentName":"Tester"}}}"""

        fun chosenModel(): String =
            """{"data":{"chooseChatModel":{"id":"5","workspaceId":"1","title":"Planning",""" +
                """"modelId":"9","modelName":"gemini","agentId":null,"agentName":null}}}"""

        fun renamed(): String =
            """{"data":{"renameChat":{"id":"5","workspaceId":"1","title":"Quarterly",""" +
                """"modelId":"9","modelName":"gemini","agentId":null,"agentName":null}}}"""
    }
}

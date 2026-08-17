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

class VariableCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ------------------------------------------------------------------- list

    @Test
    fun `lists every variable, saying what a value holds and only whether a secret does`() {
        PagingStub(listOf(page(VALUE_ROW, SECRET_ROW, UNSET_ROW, total = 3))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            val lines = result.out.trimEnd().lines()
            assertEquals("CATALOG  NAME     TYPE    HOLDS    DESCRIPTION", lines[0])
            assertContains(lines[1], "billing  channel  value   #ops")
            // A secret says only that something is stored.
            assertContains(lines[2], "billing  apiKey   secret  set")
            assertContains(lines[3], "billing  unused   secret  not set")
        }
    }

    @Test
    fun `drops the catalog column when there is only one catalog in play`() {
        PagingStub(listOf(CATALOGS, page(VALUE_ROW, total = 1))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list", "--catalog", "billing")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertFalse(result.out.contains("CATALOG"), result.out)
            assertContains(server.bodies[1], """"catalogId":"7"""")
        }
    }

    /** Configuration, not a feed: a settings list that stopped at twenty would be a lie. */
    @Test
    fun `keeps asking until it has every page`() {
        val first = page(*Array(100) { VALUE_ROW }, total = 101)
        PagingStub(listOf(first, page(SECRET_ROW, total = 101))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(2, server.requestCount)
            assertContains(server.bodies[1], """"page":1""")
        }
    }

    @Test
    fun `reports an empty workspace and an empty catalog differently`() {
        PagingStub(listOf(page(total = 0))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No variables in workspace 1.")
        }

        PagingStub(listOf(CATALOGS, page(total = 0))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list", "-c", "billing")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "Nothing in billing.")
        }
    }

    @Test
    fun `reports a catalog that is not there`() {
        PagingStub(listOf(CATALOGS)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "list", "-c", "nowhere")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No catalog called 'nowhere'")
        }
    }

    // -------------------------------------------------------------------- set

    @Test
    fun `creates a variable that was not there`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE, created())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "s3cret")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Created billing/apiKey (secret).")
            assertContains(server.bodies[2], "mutation CreateVariable")
            assertContains(server.bodies[2], """"name":"apiKey"""")
            assertContains(server.bodies[2], """"value":"s3cret"""")
            // The catalog is named on the command line and sent as its id.
            assertContains(server.bodies[2], """"catalogId":"7"""")
        }
    }

    /** The server's own form offers STRING; NUMBER and BOOLEAN are set there. */
    @Test
    fun `creates it as a string`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE, created())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "x")

            assertContains(server.bodies[2], """"type":"STRING"""")
        }
    }

    /**
     * The user's `--type` is what the server calls a variable's *kind*: whether it comes back
     * with a listing or only when somebody asks. Its `type` is what the value holds.
     */
    @Test
    fun `sends --type as the server's kind`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE, created(kind = "VALUE"))).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "billing", "-n", "channel", "-v", "#ops", "-t", "value")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(server.bodies[2], """"kind":"VALUE"""")
            assertContains(result.out, "(value).")
        }
    }

    @Test
    fun `defaults a new one to a secret`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE, created())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "x")

            assertContains(server.bodies[2], """"kind":"SECRET"""")
        }
    }

    @Test
    fun `updates one that is already there`() {
        PagingStub(listOf(CATALOGS, EXISTING, updated())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "new")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Updated billing/apiKey")
            assertContains(server.bodies[2], "mutation UpdateVariable")
            assertContains(server.bodies[2], """"id":"3"""")
            assertContains(server.bodies[2], """"value":"new"""")
        }
    }

    /** Leaving `--type` out of an update leaves the variable as it is. */
    @Test
    fun `does not change the kind of an update it was not asked about`() {
        PagingStub(listOf(CATALOGS, EXISTING, updated())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "new")

            assertFalse(server.bodies[2].contains(""""kind":"""), server.bodies[2])
        }
    }

    @Test
    fun `changes the kind when asked`() {
        PagingStub(listOf(CATALOGS, EXISTING, updated())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "-v", "new", "--type", "value")

            assertContains(server.bodies[2], """"kind":"VALUE"""")
        }
    }

    /** A typo in a catalog name should not quietly become a second catalog. */
    @Test
    fun `will not invent a catalog, and says which there are`() {
        PagingStub(listOf(CATALOGS, CATALOGS)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "biling", "-n", "apiKey", "-v", "x")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No catalog called 'biling' in workspace 1.")
            assertContains(result.err, "There is: billing, deploy.")
        }
    }

    @Test
    fun `reads the value from standard input when asked`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE, created())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "billing", "-n", "apiKey", "--value-stdin") { command ->
                (command as? VariableSetCommand)?.readLine = { "from-stdin" }
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(server.bodies[2], """"value":"from-stdin"""")
        }
    }

    @Test
    fun `wants a value one way or the other`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val neither = run(server, "variable", "set", "-c", "billing", "-n", "apiKey")
            assertEquals(ExitCode.USAGE, neither.exitCode)
            assertContains(neither.err, "A value is needed")

            val both = run(server, "variable", "set", "-c", "b", "-n", "k", "-v", "x", "--value-stdin")
            assertEquals(ExitCode.USAGE, both.exitCode)
            assertContains(both.err, "not both")

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `refuses a type that is neither`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "set", "-c", "b", "-n", "k", "-v", "x", "-t", "hidden")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "hidden")
        }
    }

    // -------------------------------------------------------------------- get

    @Test
    fun `prints what a value holds, and nothing else`() {
        PagingStub(listOf(CATALOGS, EXISTING_VALUE)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "billing/channel")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // Exactly the value: KEY=$(orkx var get billing/channel) is the whole of it.
            assertEquals("#ops", result.out.trim())
            assertEquals("", result.err.trim())
            // A value came back with the listing, so nothing was revealed.
            assertEquals(2, server.requestCount)
        }
    }

    /** A secret only ever comes back through the reveal, and the server records the asking. */
    @Test
    fun `reveals a secret, and says that the asking was recorded`() {
        PagingStub(listOf(CATALOGS, EXISTING, revealed())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "billing/apiKey")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("s3cret", result.out.trim())
            assertContains(server.bodies[2], "mutation RevealVariable")
            assertContains(server.bodies[2], """"id":"3"""")
            // The note is on stderr, so it never lands in what the value was captured into.
            assertContains(result.err, "was recorded")
        }
    }

    @Test
    fun `takes the two-flag form as well`() {
        PagingStub(listOf(CATALOGS, EXISTING_VALUE)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "--catalog", "billing", "--name", "channel")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("#ops", result.out.trim())
        }
    }

    @Test
    fun `answers to var as well as variable`() {
        PagingStub(listOf(CATALOGS, EXISTING_VALUE)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "var", "get", "billing/channel")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("#ops", result.out.trim())
        }
    }

    @Test
    fun `will not be told the same thing twice`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "billing/channel", "--catalog", "billing")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "Name it once")
        }
    }

    @Test
    fun `wants to be told which variable`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            assertContains(run(server, "variable", "get").err, "Which variable?")
            assertContains(run(server, "variable", "get", "billing").err, "'billing' is not catalog/name.")
            assertContains(run(server, "variable", "get", "--catalog", "billing").err, "--name is needed")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `reports a catalog and a variable that are not there`() {
        PagingStub(listOf(CATALOGS)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "nowhere/apiKey")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No catalog called 'nowhere'")
        }

        PagingStub(listOf(CATALOGS, NO_VARIABLE)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "billing/nope")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No variable called 'nope' in billing.")
        }
    }

    /** `valueSet` is all a secret says about itself, and an empty one is worth saying so. */
    @Test
    fun `says when a variable holds nothing`() {
        val empty = """{"data":{"workspaceVariables":{"content":[{"id":"3","name":"apiKey",""" +
            """"catalogId":"7","catalogName":"billing","type":"STRING","kind":"SECRET",""" +
            """"value":null,"valueSet":false}]}}}"""
        PagingStub(listOf(CATALOGS, empty)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "get", "billing/apiKey")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "billing/apiKey holds nothing.")
            assertEquals("", result.out.trim())
            assertEquals(2, server.requestCount, "nothing to reveal")
        }
    }

    // ----------------------------------------------------------------- delete

    @Test
    fun `deletes a variable when told to`() {
        PagingStub(listOf(CATALOGS, EXISTING, DELETED)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "delete", "billing/apiKey", "--yes")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Deleted billing/apiKey.")
            assertContains(server.bodies[2], "mutation DeleteVariable")
            assertContains(server.bodies[2], """"id":"3"""")
        }
    }

    /** A value can be typed again; a secret is gone, so the question names which it is. */
    @Test
    fun `asks first, naming a secret as one`() {
        PagingStub(listOf(CATALOGS, EXISTING, DELETED)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "delete", "billing/apiKey") { command ->
                (command as? VariableDeleteCommand)?.let { it.interactive = true; it.readLine = { "y" } }
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Delete the secret billing/apiKey? [y/N]")
        }
    }

    @Test
    fun `deletes nothing on anything but yes`() {
        PagingStub(listOf(CATALOGS, EXISTING)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "delete", "billing/apiKey") { command ->
                (command as? VariableDeleteCommand)?.let { it.interactive = true; it.readLine = { "n" } }
            }

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.out, "billing/apiKey was left alone.")
            assertEquals(2, server.requestCount, "it should not have been deleted")
        }
    }

    @Test
    fun `refuses to delete unasked when nothing is attached`() {
        PagingStub(listOf(CATALOGS, EXISTING)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "delete", "billing/apiKey")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "Pass --yes to say you meant to.")
        }
    }

    @Test
    fun `takes the two-flag form, and reports what is not there`() {
        PagingStub(listOf(CATALOGS, NO_VARIABLE)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "delete", "--catalog", "billing", "--name", "nope", "-y")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No variable called 'nope' in billing.")
        }
    }

    // ----------------------------------------------------------------- shared


    @Test
    fun `both want a session`() {
        PagingStub(emptyList()).use { server ->
            assertContains(run(server, "variable", "get", "b/k").err, "Not signed in.")
            assertContains(run(server, "variable", "set", "-c", "b", "-n", "k", "-v", "x").err, "Not signed in.")
        }
    }

    @Test
    fun `both want a workspace`() {
        PagingStub(emptyList()).use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))

            assertContains(run(server, "variable", "get", "b/k").err, "No workspace chosen.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `both report a server that is not there`() {
        val deadUrl = PagingStub(emptyList()).use { it.baseUrl }
        inWorkspace(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, runAt(deadUrl, "variable", "get", "b/k").exitCode)
        assertEquals(ExitCode.UNREACHABLE, runAt(deadUrl, "variable", "set", "-c", "b", "-n", "k", "-v", "x").exitCode)
    }

    private fun inWorkspace(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun run(
        server: PagingStub,
        vararg args: String,
        configure: (Any) -> Unit = {},
    ): Result = runAt(server.baseUrl, *args, configure = configure)

    private fun runAt(
        server: String,
        vararg args: String,
        configure: (Any) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val variable = command.subcommands.getValue("variable")
        val factory: (String, String) -> GraphQlClient = { _, cookie -> GraphQlClient(server, cookie) }

        variable.subcommands.getValue("list").getCommand<VariableListCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }
        variable.subcommands.getValue("delete").getCommand<VariableDeleteCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            interactive = false
            configure(this)
        }
        variable.subcommands.getValue("set").getCommand<VariableSetCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }
        variable.subcommands.getValue("get").getCommand<VariableGetCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = factory
            configure(this)
        }

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val VALUE_ROW = """{"id":"4","name":"channel","catalogId":"7","catalogName":"billing",""" +
            """"type":"STRING","kind":"VALUE","value":"#ops","valueSet":true}"""

        const val SECRET_ROW = """{"id":"3","name":"apiKey","catalogId":"7","catalogName":"billing",""" +
            """"type":"STRING","kind":"SECRET","value":null,"valueSet":true}"""

        const val UNSET_ROW = """{"id":"5","name":"unused","catalogId":"7","catalogName":"billing",""" +
            """"type":"STRING","kind":"SECRET","value":null,"valueSet":false}"""

        fun page(vararg rows: String, total: Int): String =
            """{"data":{"workspaceVariables":{"content":[${rows.joinToString(",")}],""" +
                """"totalElements":$total}}}"""

        const val DELETED = """{"data":{"deleteVariable":true}}"""

        const val CATALOGS = """{"data":{"variableCatalogs":[{"id":"7","name":"billing"},""" +
            """{"id":"8","name":"deploy"}]}}"""

        const val NO_VARIABLE = """{"data":{"workspaceVariables":{"content":[]}}}"""

        const val EXISTING = """{"data":{"workspaceVariables":{"content":[{"id":"3","name":"apiKey",""" +
            """"catalogId":"7","catalogName":"billing","type":"STRING","kind":"SECRET",""" +
            """"value":null,"valueSet":true}]}}}"""

        const val EXISTING_VALUE = """{"data":{"workspaceVariables":{"content":[{"id":"4","name":"channel",""" +
            """"catalogId":"7","catalogName":"billing","type":"STRING","kind":"VALUE",""" +
            """"value":"#ops","valueSet":true}]}}}"""

        fun revealed(): String = """{"data":{"revealVariable":"s3cret"}}"""

        fun created(kind: String = "SECRET"): String =
            """{"data":{"createVariable":{"id":"3","name":"${if (kind == "VALUE") "channel" else "apiKey"}",""" +
                """"catalogId":"7","catalogName":"billing","type":"STRING","kind":"$kind",""" +
                """"value":null,"valueSet":true}}}"""

        fun updated(): String =
            """{"data":{"updateVariable":{"id":"3","name":"apiKey","catalogId":"7",""" +
                """"catalogName":"billing","type":"STRING","kind":"SECRET","value":null,"valueSet":true}}}"""
    }
}

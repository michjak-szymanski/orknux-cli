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

class VariableCatalogCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ----------------------------------------------------------------- create

    @Test
    fun `creates a catalog`() {
        PagingStub(listOf("""{"data":{"createVariableCatalog":{"id":"9","name":"billing"}}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "create", "--name", "billing")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Created catalog billing.")
            assertContains(server.bodies[0], "mutation CreateVariableCatalog")
            assertContains(server.bodies[0], """"workspaceId":"1"""")
            assertContains(server.bodies[0], """"name":"billing"""")
        }
    }

    /** The server owns the uniqueness rule and words the refusal. */
    @Test
    fun `passes on a name already in use`() {
        PagingStub(listOf("""{"errors":[{"message":"There is already a catalog called billing"}]}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "create", "--name", "billing")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "There is already a catalog called billing")
        }
    }

    @Test
    fun `refuses an empty name`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "create", "--name", "   ")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "A catalog needs a name.")
            assertEquals(0, server.requestCount)
        }
    }

    // ----------------------------------------------------------------- rename

    @Test
    fun `renames by name, having looked the id up`() {
        val renamed = """{"data":{"renameVariableCatalog":{"id":"7","name":"invoicing"}}}"""
        PagingStub(listOf(CATALOGS, renamed)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(
                server, "variable", "catalog", "rename", "--name", "billing", "--new-name", "invoicing",
            )

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Renamed billing to invoicing.")
            assertContains(server.bodies[0], "variableCatalogs")
            assertContains(server.bodies[1], "mutation RenameVariableCatalog")
            assertContains(server.bodies[1], """"id":"7"""")
            assertContains(server.bodies[1], """"name":"invoicing"""")
        }
    }

    @Test
    fun `reports a catalog that is not there`() {
        PagingStub(listOf(CATALOGS)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "rename", "-n", "nowhere", "--new-name", "x")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No catalog called 'nowhere' in workspace 1.")
            assertEquals(1, server.requestCount, "nothing should have been renamed")
        }
    }

    @Test
    fun `wants both names`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "rename", "--name", "billing")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "--new-name")
        }
    }

    // ----------------------------------------------------------------- delete

    @Test
    fun `deletes an empty catalog`() {
        PagingStub(listOf(CATALOGS, """{"data":{"deleteVariableCatalog":true}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "delete", "--name", "billing")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Deleted catalog billing.")
            assertContains(server.bodies[1], "mutation DeleteVariableCatalog")
            assertContains(server.bodies[1], """"id":"7"""")
        }
    }

    /**
     * No prompt, and none needed: the server removes only an empty catalog and refuses one that
     * still holds anything, so this cannot lose a variable. Its refusal is the whole guard.
     */
    @Test
    fun `passes on the refusal to delete one that still holds something`() {
        val refusal = """{"errors":[{"message":"billing still holds 3 variables"}]}"""
        PagingStub(listOf(CATALOGS, refusal)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "delete", "--name", "billing")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "billing still holds 3 variables")
        }
    }

    @Test
    fun `does not ask before deleting, because there is nothing to lose`() {
        PagingStub(listOf(CATALOGS, """{"data":{"deleteVariableCatalog":true}}""")).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "variable", "catalog", "delete", "--name", "billing")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertFalse(result.out.contains("[y/N]"), result.out)
        }
    }

    // ----------------------------------------------------------------- shared

    @Test
    fun `all of them want a session`() {
        PagingStub(emptyList()).use { server ->
            for (args in listOf(
                listOf("variable", "catalog", "create", "-n", "x"),
                listOf("variable", "catalog", "rename", "-n", "x", "--new-name", "y"),
                listOf("variable", "catalog", "delete", "-n", "x"),
            )) {
                val result = run(server, *args.toTypedArray())

                assertEquals(ExitCode.REJECTED, result.exitCode, args.toString())
                assertContains(result.err, "Not signed in.", message = args.toString())
            }
        }
    }

    @Test
    fun `colour adds nothing but colour`() {
        val created = """{"data":{"createVariableCatalog":{"id":"9","name":"billing"}}}"""
        PagingStub(listOf(created, created)).use { server ->
            inWorkspace(server.baseUrl)

            val args = arrayOf("variable", "catalog", "create", "--name", "billing")
            val plain = run(server, *args) { (it as? VariableCatalogCreateCommand)?.styleOverride = Style(false) }
            val coloured = run(server, *args) { (it as? VariableCatalogCreateCommand)?.styleOverride = Style(true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun inWorkspace(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

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
        val catalog = command.subcommands.getValue("variable").subcommands.getValue("catalog")
        val factory: (String, String) -> GraphQlClient = { _, cookie -> GraphQlClient(server.baseUrl, cookie) }

        catalog.subcommands.getValue("create").getCommand<VariableCatalogCreateCommand>().apply {
            store = SessionStore(configHome); clientFactory = factory; configure(this)
        }
        catalog.subcommands.getValue("rename").getCommand<VariableCatalogRenameCommand>().apply {
            store = SessionStore(configHome); clientFactory = factory; configure(this)
        }
        catalog.subcommands.getValue("delete").getCommand<VariableCatalogDeleteCommand>().apply {
            store = SessionStore(configHome); clientFactory = factory; configure(this)
        }

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val CATALOGS = """{"data":{"variableCatalogs":[{"id":"7","name":"billing"},""" +
            """{"id":"8","name":"deploy"}]}}"""
    }
}

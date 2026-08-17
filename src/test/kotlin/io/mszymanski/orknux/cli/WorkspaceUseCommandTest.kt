package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceUseCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `remembers the workspace the server confirms`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "7")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Now working in backend (id 7).")

            val stored = SessionStore(configHome).read()
            assertEquals("7", stored?.workspaceId)
            assertEquals("backend", stored?.workspaceName)
            // The session itself is untouched by choosing a workspace.
            assertEquals("JSESSIONID=ABC", stored?.cookie)
            assertEquals("alice", stored?.username)
        }
    }

    @Test
    fun `asks the server, with the session cookie and the id as a variable`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            use(server.baseUrl, "7")

            assertEquals(GRAPHQL_PATH, server.lastPath)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            // The id travels as a variable, never spliced into the document.
            assertContains(server.lastBody!!, "query Workspace(\$id: ID!)")
            assertContains(server.lastBody!!, "workspace(id: \$id)")
            assertContains(server.lastBody!!, """"variables":{"id":"7"}""")
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        val result = use("http://localhost:1", "7")

        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in. Run 'orkx login' first.")
    }

    @Test
    fun `reports an expired session as one to replace`() {
        StubGraphQlServer(status = 401, body = "").use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "7")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
            assertContains(result.err, "orkx login")
        }
    }

    /**
     * The resolver is `findByIdOrNull(id)?.takeIf(access::canSee)`, so a workspace that is
     * not there and one the caller may not see are the same answer. The message may not
     * claim to know which.
     */
    @Test
    fun `does not claim to know why a workspace came back empty`() {
        StubGraphQlServer(body = """{"data":{"workspace":null}}""").use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "404")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No workspace 404 at ${server.baseUrl} that alice can see.")
            assertNull(SessionStore(configHome).read()?.workspaceId)
        }
    }

    @Test
    fun `passes on what the server refused`() {
        val forbidden = """{"errors":[{"message":"You do not have access to workspace \"backend\""}]}"""
        StubGraphQlServer(body = forbidden).use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "7")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "You do not have access to workspace \"backend\"")
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        signedIn(deadUrl)

        val result = use(deadUrl, "7")

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
    }

    /**
     * The server's resolver binds ID! to a Long, so a word for an id gets an INTERNAL_ERROR
     * and a correlation number. Caught here, before it is asked.
     */
    @Test
    fun `refuses an id that cannot be one`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "backend")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'backend' is not a workspace id; those are numbers.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `refuses an empty id`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            val result = use(server.baseUrl, "   ")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "cannot be empty")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `replaces a workspace already chosen`() {
        StubGraphQlServer().use { server ->
            SessionStore(configHome).write(
                StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "1", "frontend"),
            )

            val result = use(server.baseUrl, "7")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("7", SessionStore(configHome).read()?.workspaceId)
            assertEquals("backend", SessionStore(configHome).read()?.workspaceName)
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun use(server: String, id: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val use = command.subcommands.getValue("workspace").subcommands.getValue("use")
            .getCommand<WorkspaceUseCommand>()
        use.store = SessionStore(configHome)
        // The stub is a real server on a real port; only the base URL is substituted.
        use.clientFactory = { _, cookie -> GraphQlClient(server, cookie) }

        val exitCode = command.execute("workspace", "use", id)
        return Result(exitCode, out.toString(), err.toString())
    }
}

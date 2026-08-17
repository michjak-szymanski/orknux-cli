package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ------------------------------------------------------------- server use

    /** A 401 from `/api/session` is a working orknux-server that has not been signed in to. */
    @Test
    fun `points at a server that is there but does not know us`() {
        StubSessionServer(status = 401, body = "", setCookie = null).use { server ->
            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Now talking to ${server.baseUrl}.")
            assertContains(result.out, "Run 'orkx login' to sign in.")
            assertEquals(server.baseUrl, SessionStore(configHome).read()?.server)
            assertNull(SessionStore(configHome).read()?.cookie)
        }
    }

    @Test
    fun `says who we already are when the stored cookie still works`() {
        StubSessionServer(body = """{"username":"alice","roles":["ROLE_USERS"],"admin":false}""").use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))

            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Signed in as alice.")
            assertEquals("JSESSIONID=ABC", SessionStore(configHome).read()?.cookie)
        }
    }

    /**
     * A JSESSIONID belongs to the server that issued it. Carrying one to another installation
     * would send a credential somewhere it means nothing, so moving drops it.
     */
    @Test
    fun `drops the session when moving to a different server`() {
        StubSessionServer(status = 401, body = "", setCookie = null).use { server ->
            SessionStore(configHome).write(
                StoredSession("http://elsewhere:9999", "alice", "JSESSIONID=ABC", "1", "foo"),
            )

            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "The session for http://elsewhere:9999 was dropped.")
            val stored = SessionStore(configHome).read()
            assertEquals(server.baseUrl, stored?.server)
            assertNull(stored?.cookie)
            assertNull(stored?.username)
            // The workspace belonged to the old server too.
            assertNull(stored?.workspaceId)
        }
    }

    @Test
    fun `keeps the workspace when pointed at the same server again`() {
        StubSessionServer(body = """{"username":"alice","roles":[],"admin":false}""").use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "1", "foo"))

            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("1", SessionStore(configHome).read()?.workspaceId)
            assertFalse(result.out.contains("was dropped"), result.out)
        }
    }

    @Test
    fun `presents no cookie to a server that did not issue it`() {
        StubSessionServer(status = 401, body = "", setCookie = null).use { server ->
            SessionStore(configHome).write(StoredSession("http://elsewhere:9999", "alice", "JSESSIONID=SECRET"))

            run(server.baseUrl, "server", "use", server.baseUrl)

            assertNull(server.lastCookieHeader, "the old cookie must not travel to a new server")
        }
    }

    @Test
    fun `refuses something that answers with the wrong status`() {
        StubSessionServer(status = 418, body = "I am a teapot", setCookie = null).use { server ->
            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.UNREACHABLE, result.exitCode)
            assertContains(result.err, "it is not orknux-server")
            assertContains(result.err, "418")
            assertNull(SessionStore(configHome).read(), "nothing should be stored")
        }
    }

    /**
     * The commonest way to get this wrong is a port belonging to some other web application,
     * which answers 200 with a page. That must read as the wrong address, not as a parser
     * error with a fragment of somebody's HTML in it.
     */
    @Test
    fun `refuses a web page served with a 200`() {
        StubSessionServer(body = "<!doctype html><html lang=\"en\">", setCookie = null).use { server ->
            val result = run(server.baseUrl, "server", "use", server.baseUrl)

            assertEquals(ExitCode.UNREACHABLE, result.exitCode)
            assertContains(result.err, "it is not orknux-server")
            assertContains(result.err, "something that is not a session")
            assertFalse(result.err.contains("doctype"), "no HTML in the message: ${result.err}")
            assertNull(SessionStore(configHome).read())
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubSessionServer().use { it.baseUrl }

        val result = run(deadUrl, "server", "use", deadUrl)

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
        assertNull(SessionStore(configHome).read())
    }

    @Test
    fun `refuses a URL that is not one`() {
        val result = run("http://localhost:1", "server", "use", "localhost:8080")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "needs an http:// or https:// scheme")
    }

    // ------------------------------------------------------------ server info

    @Test
    fun `tells you where you are and who you are`() {
        StubSessionServer(
            body = """{"username":"alice","roles":["ROLE_ADMINS","ROLE_USERS"],"admin":true,""" +
                """"email":"alice@orknux.io"}""",
        ).use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "1", "foo"))

            val result = info(server.baseUrl, settings = SETTINGS)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Server       ${server.baseUrl}")
            assertContains(result.out, "Signed in    alice <alice@orknux.io> (administrator)")
            assertContains(result.out, "Roles        ROLE_ADMINS, ROLE_USERS")
            assertContains(result.out, "Workspace    foo (id 1)")
            assertContains(result.out, "Chat         on")
            assertContains(result.out, "Attachments  on, up to 25 MB each")
            assertContains(result.out, "Session      ${SessionStore(configHome).file}")
        }
    }

    @Test
    fun `says plainly when nobody is signed in`() {
        StubSessionServer(status = 401, body = "", setCookie = null).use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl))

            val result = info(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "no - run 'orkx login'")
            assertContains(result.out, "none - run 'orkx workspace use <id>'")
        }
    }

    /** The question is usually asked *because* nothing is answering, so the facts still print. */
    @Test
    fun `still reports what is stored when the server is unreachable`() {
        val deadUrl = StubSessionServer().use { it.baseUrl }
        SessionStore(configHome).write(StoredSession(deadUrl, "alice", "JSESSIONID=ABC", "1", "foo"))

        val result = info(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.out, "Server")
        assertContains(result.out, deadUrl)
        assertContains(result.out, "foo (id 1)")
        assertContains(result.err, "The server could not be reached.")
    }

    @Test
    fun `asks for a server when none has been chosen`() {
        val result = info("http://localhost:1")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "No server chosen. Run 'orkx server use <url>'")
    }

    @Test
    fun `does not fail over settings it could not read`() {
        StubSessionServer(body = """{"username":"alice","roles":[],"admin":false}""").use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))

            val result = info(server.baseUrl, settings = """{"errors":[{"message":"no"}]}""")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "could not be read")
            assertContains(result.out, "alice")
        }
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubSessionServer(body = """{"username":"alice","roles":["ROLE_USERS"],"admin":false}""").use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "1", "foo"))

            val plain = info(server.baseUrl, settings = SETTINGS) { it.styleOverride = Style(enabled = false) }
            val coloured = info(server.baseUrl, settings = SETTINGS) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private data class Result(val exitCode: Int, val out: String, val err: String)

    /** Drives `server use`, whose only outbound call is the session probe. */
    private fun run(probeUrl: String, vararg args: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        command.subcommands.getValue("server").subcommands.getValue("use")
            .getCommand<ServerUseCommand>().apply {
                store = SessionStore(configHome)
                clientFactory = { SessionClient(probeUrl) }
            }
        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private fun info(
        probeUrl: String,
        settings: String = SETTINGS,
        configure: (ServerInfoCommand) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val info = command.subcommands.getValue("server").subcommands.getValue("info")
            .getCommand<ServerInfoCommand>()
        info.store = SessionStore(configHome)
        info.clientFactory = { SessionClient(probeUrl) }
        // A second stub, because the settings come over GraphQL rather than from the probe.
        val graphql = StubGraphQlServer(body = settings)
        info.graphQlFactory = { _, cookie -> GraphQlClient(graphql.baseUrl, cookie) }
        configure(info)

        return graphql.use {
            val exitCode = command.execute("server", "info")
            Result(exitCode, out.toString(), err.toString())
        }
    }

    private companion object {
        const val SETTINGS = """{"data":{"installationSettings":{"chatEnabled":true,""" +
            """"attachmentsEnabled":true,"attachmentMaxFileSizeMb":25}}}"""
    }
}

// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `signs in and stores the session`() {
        StubSessionServer(
            body = """{"username":"alice","roles":["ROLE_ADMINS","ROLE_USERS"],"admin":true,"email":"alice@orknux.io"}""",
        ).use { server ->
            val result = login("--server", server.baseUrl, "--username", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Signed in to ${server.baseUrl} as alice <alice@orknux.io> (administrator).")

            // The wire format the server's LoginRequest expects.
            assertEquals("POST", server.lastMethod)
            assertEquals(SESSION_PATH, server.lastPath)
            assertEquals("application/json", server.lastContentType)
            assertEquals("""{"username":"alice","password":"hunter2"}""", server.lastBody)

            val stored = SessionStore(configHome).read()
            assertEquals("JSESSIONID=SESSION-A1B2C3", stored?.cookie)
            assertEquals("alice", stored?.username)
            assertEquals(server.baseUrl, stored?.server)
        }
    }

    /**
     * The name was `JSESSIONID` until Spring Session arrived on the server and made it
     * `SESSION`, which broke every command in this CLI. Whatever the sign-in sets is kept.
     */
    @Test
    fun `keeps the session cookie whatever it is called`() {
        StubSessionServer(setCookie = "SESSION=ZTY2ZmE5Nzc; Path=/; HttpOnly; SameSite=Lax").use { server ->
            val result = login("--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("SESSION=ZTY2ZmE5Nzc", SessionStore(configHome).read()?.cookie)
        }
    }

    /** More than one, joined the way a Cookie header carries them — a CSRF token is coming. */
    @Test
    fun `keeps every cookie the sign-in set`() {
        StubSessionServer(
            setCookie = "SESSION=abc; Path=/; HttpOnly",
            alsoSetCookie = "XSRF-TOKEN=def; Path=/",
        ).use { server ->
            val result = login("--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("SESSION=abc; XSRF-TOKEN=def", SessionStore(configHome).read()?.cookie)
        }
    }

    @Test
    fun `ignores a cookie with nothing in it`() {
        StubSessionServer(setCookie = "SESSION=; Path=/", alsoSetCookie = "REAL=abc; Path=/").use { server ->
            val result = login("--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals("REAL=abc", SessionStore(configHome).read()?.cookie)
        }
    }

    @Test
    fun `reports a rejected password without storing anything`() {
        StubSessionServer(status = 401, body = """{"error":"Unauthorized"}""", setCookie = null).use { server ->
            val result = login("--server", server.baseUrl, "--username", "alice", "--password=wrong")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "Invalid username or password.")
            assertFalse(Files.exists(SessionStore(configHome).file))
        }
    }

    @Test
    fun `reports a server that is not there`() {
        // A port nothing is listening on: bind one, then let it go.
        val deadUrl = StubSessionServer().use { it.baseUrl }

        val result = login("--server", deadUrl, "--username", "alice", "--password=hunter2")

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
    }

    @Test
    fun `refuses a URL that is not one`() {
        val result = login("--server", "localhost:8080", "--username", "alice", "--password=hunter2")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "needs an http:// or https:// scheme")
    }

    @Test
    fun `treats a 200 without a session cookie as unusable`() {
        StubSessionServer(setCookie = null).use { server ->
            val result = login("--server", server.baseUrl, "--username", "alice", "--password=hunter2")

            assertEquals(ExitCode.UNREACHABLE, result.exitCode)
            assertContains(result.err, "set no cookie to sign in with")
        }
    }

    @Test
    fun `reads the password from standard input`() {
        StubSessionServer().use { server ->
            val result = login("--server", server.baseUrl, "--username", "alice", "--password-stdin") {
                it.console = FakeConsole(piped = "piped-secret")
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(server.lastBody!!, """"password":"piped-secret"""")
        }
    }

    @Test
    fun `refuses --password together with --password-stdin`() {
        val result = login("--server", "http://localhost:1", "-u", "alice", "--password=x", "--password-stdin")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "not both")
    }

    @Test
    fun `prompts for what was not passed`() {
        StubSessionServer().use { server ->
            val console = FakeConsole(line = "bob", password = "typed-secret")
            val result = login("--server", server.baseUrl) { it.console = console }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(listOf("Username: ", "Password: "), console.prompts)
            assertContains(server.lastBody!!, """"username":"bob"""")
            assertContains(server.lastBody!!, """"password":"typed-secret"""")
        }
    }

    @Test
    fun `falls back to the stored server when none is given`() {
        StubSessionServer().use { server ->
            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=stale"))

            val result = login("--username", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(1, server.requestCount)
            assertEquals("JSESSIONID=SESSION-A1B2C3", SessionStore(configHome).read()?.cookie)
        }
    }

    @Test
    fun `prefers the environment over the stored server`() {
        StubSessionServer().use { server ->
            SessionStore(configHome).write(StoredSession("http://elsewhere:9999", "alice", "JSESSIONID=stale"))

            val result = login("--username", "alice", "--password=hunter2") {
                it.env = { name -> server.baseUrl.takeIf { _ -> name == "ORKNUX_SERVER_URL" } }
            }

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(server.baseUrl, SessionStore(configHome).read()?.server)
        }
    }

    @Test
    fun `says so when the directory grants no workspace`() {
        StubSessionServer(body = """{"username":"nobody","roles":[],"admin":false}""").use { server ->
            val result = login("--server", server.baseUrl, "-u", "nobody", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "grant no workspace")
        }
    }

    @Test
    fun `signing in again keeps the workspace you were working in`() {
        StubSessionServer().use { server ->
            SessionStore(configHome).write(
                StoredSession(server.baseUrl, "alice", "JSESSIONID=stale", "7", "backend"),
            )

            val result = login("--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Still working in backend (id 7).")
            val stored = SessionStore(configHome).read()
            assertEquals("7", stored?.workspaceId)
            // …on a new cookie, which is the point of signing in again.
            assertEquals("JSESSIONID=SESSION-A1B2C3", stored?.cookie)
        }
    }

    @Test
    fun `does not hand one user's workspace to another`() {
        StubSessionServer(body = """{"username":"bob","roles":["ROLE_USERS"],"admin":false}""").use { server ->
            SessionStore(configHome).write(
                StoredSession(server.baseUrl, "alice", "JSESSIONID=stale", "7", "backend"),
            )

            val result = login("--server", server.baseUrl, "-u", "bob", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertNull(SessionStore(configHome).read()?.workspaceId)
            assertFalse(result.out.contains("Still working in"), result.out)
        }
    }

    @Test
    fun `does not carry a workspace to a different server`() {
        StubSessionServer().use { server ->
            SessionStore(configHome).write(
                StoredSession("http://elsewhere:9999", "alice", "JSESSIONID=stale", "7", "backend"),
            )

            val result = login("--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertNull(SessionStore(configHome).read()?.workspaceId)
        }
    }

    /**
     * The command clears the array it was handed once the request is out. Picocli scrubs
     * its own copy of an interactive value as well, with NUL characters, so either state
     * counts: what this pins down is that no readable password outlives the call.
     */
    @Test
    fun `clears the password once it has been sent`() {
        StubSessionServer().use { server ->
            val command = commandLine()
            val login = command.subcommands.getValue("login").getCommand<LoginCommand>()
            login.store = SessionStore(configHome)
            login.env = { null }
            command.execute("login", "--server", server.baseUrl, "-u", "alice", "--password=hunter2")

            val left = login.password
            assertTrue(
                left == null || left.all { it == ' ' || it.code == 0 },
                "the password array still holds ${left?.map(Char::code)}",
            )
        }
    }

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun commandLine(): CommandLine = orkxCommandLine()

    private fun login(vararg args: String, configure: (LoginCommand) -> Unit = {}): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = commandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val login = command.subcommands.getValue("login").getCommand<LoginCommand>()
        login.store = SessionStore(configHome)
        // Never let the machine running the tests decide the server.
        login.env = { null }
        login.console = FakeConsole()
        configure(login)

        val exitCode = command.execute("login", *args)
        return Result(exitCode, out.toString(), err.toString())
    }
}

/** A console nobody is sitting at. Records the prompts so the test can assert on them. */
internal class FakeConsole(
    private val line: String? = null,
    private val password: String? = null,
    private val piped: String? = null,
) : Console {

    val prompts = mutableListOf<String>()

    override fun readLine(prompt: String): String? {
        prompts += prompt
        return line
    }

    override fun readPassword(prompt: String): CharArray? {
        prompts += prompt
        return password?.toCharArray()
    }

    override fun readPipedLine(): String? = piped
}

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

class AdminMonitoringCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `shows a component, what it says, and what it depends on`() {
        StubGraphQlServer(body = healthy()).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "HEALTHY  orknux-server 1.0.0")
            assertContains(result.out, "API, sign-in, connections and workflow runs")
            assertContains(result.out, "Answering")
            // Dependencies read as up or down, not as true or false. The column is two wide
            // here because nothing is down; the degraded case widens it to four.
            assertContains(result.out, "up  Database   Answering")
            assertContains(result.out, "up  Directory  Answering")
            assertFalse(result.out.contains("true"), result.out)
        }
    }

    @Test
    fun `asks the server to check, and asks for what the monitoring page shows`() {
        StubGraphQlServer(body = healthy()).use { server ->
            signedIn(server.baseUrl)

            monitoring(server.baseUrl)

            assertEquals(GRAPHQL_PATH, server.lastPath)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertContains(server.lastBody!!, "query Components { components")
            assertContains(server.lastBody!!, "dependencies { name description reachable detail url }")
        }
    }

    /** A degraded installation is the case worth having: it answers, and names what is wrong. */
    @Test
    fun `reports what could not be reached, and says so in the exit code`() {
        StubGraphQlServer(body = degraded()).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.DEGRADED, result.exitCode)
            assertContains(result.out, "DEGRADED")
            assertContains(result.out, "Cannot reach temporal")
            assertContains(result.out, "down  Temporal")
            assertContains(result.out, "Connection refused")
        }
    }

    @Test
    fun `shows a dependency's own interface when it has one`() {
        StubGraphQlServer(body = degraded()).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertContains(result.out, "http://localhost:8233")
        }
    }

    @Test
    fun `keeps the columns aligned across dependencies`() {
        StubGraphQlServer(body = degraded()).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            val rows = result.out.lines().filter { it.contains("Database") || it.contains("Temporal") }
            assertEquals(2, rows.size, result.out)
            // The name column starts at the same place on both, whatever up/down did to it.
            assertEquals(
                rows.map { it.indexOf(if (it.contains("Database")) "Database" else "Temporal") }.distinct().size,
                1,
                rows.toString(),
            )
        }
    }

    /** The server owns the rule; being refused in its own words is the whole answer. */
    @Test
    fun `passes on the refusal when the caller is not an administrator`() {
        val refusal = """{"errors":[{"message":"This action requires the administrator role"}]}"""
        StubGraphQlServer(body = refusal).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "This action requires the administrator role")
        }
    }

    @Test
    fun `says when the server reported nothing`() {
        StubGraphQlServer(body = """{"data":{"components":[]}}""").use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "reported no components.")
        }
    }

    @Test
    fun `separates one component from the next`() {
        val two = """{"data":{"components":[""" +
            """{"name":"orknux-server","description":"","status":"HEALTHY","version":null,""" +
            """"detail":"Answering","lastCheckedAt":"","dependencies":[]},""" +
            """{"name":"orknux-worker","description":"","status":"HEALTHY","version":null,""" +
            """"detail":"Answering","lastCheckedAt":"","dependencies":[]}]}}"""
        StubGraphQlServer(body = two).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // Asserted on lines rather than on "\n\n": println writes \r\n on Windows.
            val lines = result.out.lines()
            val first = lines.indexOfFirst { it.contains("orknux-server") }
            val second = lines.indexOfFirst { it.contains("orknux-worker") }
            assertTrue(first in 0 until second, result.out)
            assertTrue(lines.subList(first, second).any { it.isBlank() }, result.out)
        }
    }

    /** `orknux.version` defaults to the literal "unknown", which is not worth printing. */
    @Test
    fun `leaves out a version the server does not know`() {
        val unknown = """{"data":{"components":[{"name":"orknux-server","description":"","status":"HEALTHY",""" +
            """"version":"unknown","detail":"Answering","lastCheckedAt":"","dependencies":[]}]}}"""
        StubGraphQlServer(body = unknown).use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertFalse(result.out.contains("unknown"), result.out)
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        val result = monitoring("http://localhost:1")

        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in. Run 'orkx login' first.")
    }

    @Test
    fun `reports an expired session`() {
        StubGraphQlServer(status = 401, body = "").use { server ->
            signedIn(server.baseUrl)

            val result = monitoring(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        signedIn(deadUrl)

        val result = monitoring(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubGraphQlServer(body = degraded()).use { server ->
            signedIn(server.baseUrl)

            val plain = monitoring(server.baseUrl) { it.styleOverride = Style(enabled = false) }
            val coloured = monitoring(server.baseUrl) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun monitoring(
        server: String,
        configure: (AdminMonitoringCommand) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val monitoring = command.subcommands.getValue("admin").subcommands.getValue("monitoring")
            .getCommand<AdminMonitoringCommand>()
        monitoring.store = SessionStore(configHome)
        monitoring.clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
        configure(monitoring)

        val exitCode = command.execute("admin", "monitoring")
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        fun healthy(): String = """{"data":{"components":[{"name":"orknux-server",""" +
            """"description":"API, sign-in, connections and workflow runs","status":"HEALTHY",""" +
            """"version":"1.0.0","detail":"Answering","lastCheckedAt":"2026-08-17T13:22:11+02:00",""" +
            """"dependencies":[""" +
            """{"name":"Database","description":"Postgres","reachable":true,"detail":"Answering","url":null},""" +
            """{"name":"Directory","description":"LDAP","reachable":true,"detail":"Answering","url":null}]}]}}"""

        fun degraded(): String = """{"data":{"components":[{"name":"orknux-server",""" +
            """"description":"API","status":"DEGRADED","version":"1.0.0",""" +
            """"detail":"Cannot reach temporal","lastCheckedAt":"2026-08-17T13:22:11+02:00",""" +
            """"dependencies":[""" +
            """{"name":"Database","description":"Postgres","reachable":true,"detail":"Answering","url":null},""" +
            """{"name":"Temporal","description":"Workflow engine","reachable":false,""" +
            """"detail":"Connection refused","url":"http://localhost:8233"}]}]}}"""
    }
}

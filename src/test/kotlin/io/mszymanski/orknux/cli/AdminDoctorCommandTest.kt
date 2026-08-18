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

class AdminDoctorCommandTest {

    @TempDir
    lateinit var configHome: Path

    /** The bug this command exists for: everything reachable, and the installation broken. */
    @Test
    fun `shows a failure the monitoring page cannot see`() {
        StubGraphQlServer(body = checks(FAILING_KEY, SCHEMA, TEMPORAL)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            val lines = result.out.trimEnd().lines()
            assertEquals("FAIL  Secret key  not set - credential writes will fail", lines[0])
            assertEquals("ok    Database    schema at v60", lines[1])
            assertEquals("ok    Temporal    connected", lines[2])
        }
    }

    /** A check nobody reads the result of is not much of a check. */
    @Test
    fun `exits 6 when something failed`() {
        StubGraphQlServer(body = checks(FAILING_KEY, SCHEMA)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.DEGRADED, result.exitCode)
            assertContains(result.out, "1 failed, of 2 checks.")
        }
    }

    @Test
    fun `exits 0 when everything passed`() {
        StubGraphQlServer(body = checks(SCHEMA, TEMPORAL)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // Nothing to summarise when there is nothing wrong.
            assertFalse(result.out.contains("checks."), result.out)
        }
    }

    /** A warning works, it is just probably not what was meant — so it is said, not failed on. */
    @Test
    fun `reports a warning without failing`() {
        StubGraphQlServer(body = checks(WARNING, SCHEMA)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "WARN  Cookie")
            assertContains(result.out, "1 to look at, of 2 checks.")
        }
    }

    @Test
    fun `counts both when there are both`() {
        StubGraphQlServer(body = checks(FAILING_KEY, WARNING, SCHEMA)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.DEGRADED, result.exitCode)
            assertContains(result.out, "1 failed, 1 to look at, of 3 checks.")
        }
    }

    @Test
    fun `asks the server for its own verdicts`() {
        StubGraphQlServer(body = checks(SCHEMA)).use { server ->
            signedIn(server.baseUrl)

            doctor(server.baseUrl)

            assertEquals(GRAPHQL_PATH, server.lastPath)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertContains(server.lastBody!!, "query Doctor { doctor { name verdict detail } }")
        }
    }

    /** A verdict this CLI has not heard of is printed as it came, like every other status. */
    @Test
    fun `prints a verdict it does not know`() {
        val odd = """{"name":"Something","verdict":"UNKNOWN","detail":"who can say"}"""
        StubGraphQlServer(body = checks(odd)).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "UNKNOWN  Something  who can say")
        }
    }

    @Test
    fun `says when the server ran no checks`() {
        StubGraphQlServer(body = """{"data":{"doctor":[]}}""").use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "ran no checks.")
        }
    }

    @Test
    fun `passes on the refusal when the caller is not an administrator`() {
        val refusal = """{"errors":[{"message":"This action requires the administrator role"}]}"""
        StubGraphQlServer(body = refusal).use { server ->
            signedIn(server.baseUrl)

            val result = doctor(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "This action requires the administrator role")
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        val result = doctor("http://localhost:1")

        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in. Run 'orkx login' first.")
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        signedIn(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, doctor(deadUrl).exitCode)
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubGraphQlServer(body = checks(FAILING_KEY, WARNING, SCHEMA)).use { server ->
            signedIn(server.baseUrl)

            val plain = doctor(server.baseUrl) { it.styleOverride = Style(enabled = false) }
            val coloured = doctor(server.baseUrl) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun doctor(
        server: String,
        configure: (AdminDoctorCommand) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        command.subcommands.getValue("admin").subcommands.getValue("doctor")
            .getCommand<AdminDoctorCommand>().apply {
                store = SessionStore(configHome)
                clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
                configure(this)
            }

        val exitCode = command.execute("admin", "doctor")
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val FAILING_KEY =
            """{"name":"Secret key","verdict":"FAIL","detail":"not set - credential writes will fail"}"""
        const val SCHEMA = """{"name":"Database","verdict":"OK","detail":"schema at v60"}"""
        const val TEMPORAL = """{"name":"Temporal","verdict":"OK","detail":"connected"}"""
        const val WARNING =
            """{"name":"Cookie","verdict":"WARN","detail":"same-site is lax; strict is safer here"}"""

        fun checks(vararg entries: String): String =
            """{"data":{"doctor":[${entries.joinToString(",")}]}}"""
    }
}

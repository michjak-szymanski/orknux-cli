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

class WorkspaceListCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `lists what the server returns and marks the one in use`() {
        StubGraphQlServer(body = page(listOf(WS_7, WS_1), total = 2)).use { server ->
            SessionStore(configHome).write(
                StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "7", "backend"),
            )

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // trimEnd only: the leading indent is part of what is being asserted.
            val lines = result.out.trimEnd().lines()
            assertEquals("   ID  NAME      DESCRIPTION", lines[0])
            assertEquals("*  7   backend   The backend team's space", lines[1])
            assertEquals("   1   frontend", lines[2])
        }
    }

    @Test
    fun `marks nothing when no workspace has been chosen`() {
        StubGraphQlServer(body = page(listOf(WS_7), total = 1)).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertTrue(result.out.lines().none { it.startsWith("*") }, result.out)
        }
    }

    @Test
    fun `asks with the session cookie`() {
        StubGraphQlServer(body = page(listOf(WS_7), total = 1)).use { server ->
            signedIn(server.baseUrl)

            list(server.baseUrl)

            assertEquals(GRAPHQL_PATH, server.lastPath)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertContains(server.lastBody!!, "query Workspaces(\$page: Int, \$size: Int)")
            assertContains(server.lastBody!!, "totalElements")
        }
    }

    /** An empty list is a fact about someone's directory groups, so it is not a failure. */
    @Test
    fun `reports having nothing to show without failing`() {
        StubGraphQlServer(body = page(emptyList(), total = 0)).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No workspaces that alice can see at ${server.baseUrl}.")
            assertEquals("", result.out.trim())
        }
    }

    /** A server default page of 20 must not become the CLI's idea of "all of them". */
    @Test
    fun `keeps asking until it has every page`() {
        PagingStub(
            listOf(
                page((1..100).map { Workspace(it.toString(), "ws$it") }, total = 101),
                page(listOf(Workspace("101", "last")), total = 101),
            ),
        ).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(2, server.requestCount)
            assertContains(result.out, "last")
            assertContains(server.bodies[1], """"page":1""")
        }
    }

    @Test
    fun `stops if a page adds nothing, whatever the count claims`() {
        PagingStub(listOf(page(listOf(WS_7), total = 9999), page(emptyList(), total = 9999))).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `says when the workspace in use is no longer listed`() {
        StubGraphQlServer(body = page(listOf(WS_1), total = 1)).use { server ->
            SessionStore(configHome).write(
                StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC", "7", "backend"),
            )

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "The workspace in use (id 7) is not in this list any more.")
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        val result = list("http://localhost:1")

        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in. Run 'orkx login' first.")
    }

    @Test
    fun `reports an expired session`() {
        StubGraphQlServer(status = 401, body = "").use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        signedIn(deadUrl)

        val result = list(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
    }

    /**
     * A correlation id says the server broke, not what broke. Pointing at the command that can
     * answer turns a dead end into a next step — without claiming to know the cause.
     */
    @Test
    fun `points at doctor when the server only gives a correlation id`() {
        val opaque = """{"errors":[{"message":"INTERNAL_ERROR for 43d0fe06-58e9-2227-09cb"}]}"""
        StubGraphQlServer(body = opaque).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "INTERNAL_ERROR for 43d0fe06-58e9-2227-09cb")
            assertContains(result.err, "'orkx admin doctor' may say why")
        }
    }

    /** Anything the server worded for a person is passed on as it is. */
    @Test
    fun `adds nothing to a message that already says something`() {
        val plain = """{"errors":[{"message":"You do not have access to workspace \"backend\""}]}"""
        StubGraphQlServer(body = plain).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertContains(result.err, "You do not have access to workspace")
            assertFalse(result.err.contains("admin doctor"), result.err)
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun list(server: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val list = command.subcommands.getValue("workspace").subcommands.getValue("list")
            .getCommand<WorkspaceListCommand>()
        list.store = SessionStore(configHome)
        list.clientFactory = { _, cookie -> GraphQlClient(server, cookie) }

        val exitCode = command.execute("workspace", "list")
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        val WS_7 = Workspace("7", "backend", "The backend team's space")
        val WS_1 = Workspace("1", "frontend")

        fun page(content: List<Workspace>, total: Int): String {
            val rows = content.joinToString(",") { workspace ->
                val description = workspace.description?.let { "\"$it\"" } ?: "null"
                """{"id":"${workspace.id}","name":"${workspace.name}","description":$description}"""
            }
            return """{"data":{"workspaces":{"content":[$rows],"page":0,"size":100,""" +
                """"totalElements":$total,"totalPages":2}}}"""
        }
    }
}

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

class WorkflowRunCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ------------------------------------------------------------------- list

    @Test
    fun `lists the workflows with where each last got to`() {
        PagingStub(listOf(RICH)).use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            val lines = result.out.trimEnd().lines()
            assertContains(lines[0], "ID  NAME")
            assertContains(lines[0], "LAST RUN")
            assertContains(lines[1], "3   nightly-sync")
            assertContains(lines[1], "COMPLETED")
            // Never run, and nothing scheduled: said rather than left blank.
            assertContains(lines[2], "never run")
        }
    }

    /**
     * Only the definition's id is shown. Two numbers in one table, one of which starts nothing,
     * is how the wrong one gets used.
     */
    @Test
    fun `shows the id that run takes, and not the other one`() {
        PagingStub(listOf(RICH)).use { server ->
            inWorkspace(server.baseUrl)

            val row = list(server).out.lines().first { it.contains("nightly-sync") }

            // The id column itself, not the row: a timestamp reads "13:22:11" and would
            // make a search for the assignment id find one that is not there.
            assertEquals("3", row.trim().substringBefore(' '))
        }
    }

    @Test
    fun `marks a disabled workflow`() {
        PagingStub(listOf(RICH)).use { server ->
            inWorkspace(server.baseUrl)

            assertContains(list(server).out, "disabled")
        }
    }

    @Test
    fun `reports a workspace with no workflows`() {
        val empty = EMPTY
        PagingStub(listOf(empty)).use { server ->
            inWorkspace(server.baseUrl)

            val result = list(server)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No workflows in workspace 1.")
        }
    }

    @Test
    fun `list wants a session`() {
        PagingStub(emptyList()).use { server ->
            assertContains(list(server).err, "Not signed in.")
        }
    }

    // -------------------------------------------------------------------- run

    @Test
    fun `runs a workflow named by its name`() {
        PagingStub(listOf(WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "nightly-sync")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Started run 15 of nightly-sync.")
            assertContains(result.out, "Status   RUNNING")
            assertContains(result.out, "Trigger  MANUAL")
            assertContains(result.out, "Follow it with 'orkx execution get 15'.")
        }
    }

    /**
     * The listing shows two ids per workflow and only one of them starts anything: `id` is the
     * assignment to the workspace, `workflowId` the definition. Sending the wrong one runs the
     * wrong workflow, or nothing.
     */
    @Test
    fun `always sends the definition's id, whichever was named`() {
        for (named in listOf("nightly-sync", "3", "11")) {
            PagingStub(listOf(WORKFLOWS, started())).use { server ->
                inWorkspace(server.baseUrl)

                val result = run(server, "workflow", "run", named)

                assertEquals(ExitCode.OK, result.exitCode, "$named: ${result.err}")
                assertContains(server.bodies[1], """"workflowId":"3"""", message = named)
                assertFalse(server.bodies[1].contains(""""workflowId":"11""""), named)
            }
        }
    }

    /** One number can be one workflow's definition and another's assignment. */
    @Test
    fun `refuses a number that names two different workflows`() {
        val colliding = """{"data":{"workspaceWorkflows":{"content":[""" +
            """{"id":"11","workflowId":"3","name":"nightly-sync","description":null,"enabled":true},""" +
            """{"id":"12","workflowId":"11","name":"weekly-report","description":null,"enabled":true}],""" +
            """"totalElements":2}}}"""
        PagingStub(listOf(colliding)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "11")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'11' matches 2 workflows: nightly-sync (id 3), weekly-report (id 11).")
            assertEquals(1, server.requestCount, "nothing should have been started")
        }
    }

    @Test
    fun `matches a name whatever its case`() {
        PagingStub(listOf(WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            assertEquals(ExitCode.OK, run(server, "workflow", "run", "NIGHTLY-SYNC").exitCode)
        }
    }

    @Test
    fun `reports one that is not there, and says what is`() {
        PagingStub(listOf(WORKFLOWS)).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "nightly-snyc")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No workflow called 'nightly-snyc' in workspace 1.")
            assertContains(result.err, "There is: nightly-sync, weekly-report.")
        }
    }

    @Test
    fun `hands the input to the server as it was given`() {
        PagingStub(listOf(WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "workflow", "run", "nightly-sync", "--input", """{"since":"yesterday"}""")

            assertContains(server.bodies[1], """"input":"{\"since\":\"yesterday\"}"""")
        }
    }

    @Test
    fun `sends no input when none was given`() {
        PagingStub(listOf(WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "workflow", "run", "nightly-sync")

            assertFalse(server.bodies[1].contains(""""input":"""), server.bodies[1])
        }
    }

    @Test
    fun `reads the input from standard input when asked`() {
        PagingStub(listOf(WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            run(server, "workflow", "run", "nightly-sync", "--input-stdin") { command ->
                (command as? WorkflowRunCommand)?.readLine = { """{"piped":true}""" }
            }

            assertContains(server.bodies[1], """"input":"{\"piped\":true}"""")
        }
    }

    @Test
    fun `will not be given the input twice`() {
        PagingStub(emptyList()).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "x", "--input", "{}", "--input-stdin")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "not both")
            assertEquals(0, server.requestCount)
        }
    }

    /** Running a disabled workflow by hand is how one is tested; the server allows it. */
    @Test
    fun `says when the workflow it ran is disabled`() {
        val disabled = """{"data":{"workspaceWorkflows":{"content":[""" +
            """{"id":"13","workflowId":"5","name":"paused","description":null,"enabled":false}],""" +
            """"totalElements":1}}}"""
        PagingStub(listOf(disabled, started())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "paused")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.err, "paused is disabled; running it by hand anyway.")
            assertEquals(2, server.requestCount, "it should still have run")
        }
    }

    @Test
    fun `keeps asking until it has every workflow`() {
        val first = """{"data":{"workspaceWorkflows":{"content":[""" +
            (1..100).joinToString(",") {
                """{"id":"$it","workflowId":"${it + 500}","name":"w$it","description":null,"enabled":true}"""
            } + """],"totalElements":101}}}"""
        PagingStub(listOf(first, WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            val result = run(server, "workflow", "run", "nightly-sync")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(server.bodies[1], """"page":1""")
        }
    }

    @Test
    fun `wants a workspace and a session`() {
        PagingStub(emptyList()).use { server ->
            assertContains(run(server, "workflow", "run", "x").err, "Not signed in.")

            SessionStore(configHome).write(StoredSession(server.baseUrl, "alice", "JSESSIONID=ABC"))
            assertContains(run(server, "workflow", "run", "x").err, "No workspace chosen.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = PagingStub(emptyList()).use { it.baseUrl }
        inWorkspace(deadUrl)

        assertEquals(ExitCode.UNREACHABLE, runAt(deadUrl, "workflow", "run", "x").exitCode)
    }

    @Test
    fun `colour adds nothing but colour`() {
        PagingStub(listOf(WORKFLOWS, started(), WORKFLOWS, started())).use { server ->
            inWorkspace(server.baseUrl)

            val plain = run(server, "workflow", "run", "nightly-sync") {
                (it as? WorkflowRunCommand)?.styleOverride = Style(enabled = false)
            }
            val coloured = run(server, "workflow", "run", "nightly-sync") {
                (it as? WorkflowRunCommand)?.styleOverride = Style(enabled = true)
            }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun inWorkspace(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun list(server: PagingStub): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        command.subcommands.getValue("workflow").subcommands.getValue("list")
            .getCommand<WorkflowListCommand>().apply {
                store = SessionStore(configHome)
                clientFactory = { _, cookie -> GraphQlClient(server.baseUrl, cookie) }
            }
        val exitCode = command.execute("workflow", "list")
        return Result(exitCode, out.toString(), err.toString())
    }

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
        command.subcommands.getValue("workflow").subcommands.getValue("run")
            .getCommand<WorkflowRunCommand>().apply {
                store = SessionStore(configHome)
                clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
                configure(this)
            }

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        /** One that has run, one that never has, one disabled. */
        const val RICH = """{"data":{"workspaceWorkflows":{"content":[""" +
            """{"id":"11","workflowId":"3","name":"nightly-sync","description":"Every night.",""" +
            """"enabled":true,"lastRun":{"executionId":"15","status":"COMPLETED",""" +
            """"startedAt":"2026-08-17T13:22:11+02:00"},"nextRun":"2026-08-18T02:00:00+02:00"},""" +
            """{"id":"12","workflowId":"4","name":"weekly-report","description":null,""" +
            """"enabled":true,"lastRun":null,"nextRun":null},""" +
            """{"id":"13","workflowId":"5","name":"paused","description":null,""" +
            """"enabled":false,"lastRun":null,"nextRun":null}],"totalElements":3}}}"""

        const val EMPTY = """{"data":{"workspaceWorkflows":{"content":[],"totalElements":0}}}"""

        /** `id` is the assignment, `workflowId` the definition; nightly-sync has 11 and 3. */
        const val WORKFLOWS = """{"data":{"workspaceWorkflows":{"content":[""" +
            """{"id":"11","workflowId":"3","name":"nightly-sync","description":null,"enabled":true},""" +
            """{"id":"12","workflowId":"4","name":"weekly-report","description":null,"enabled":true}],""" +
            """"totalElements":2}}}"""

        fun started(): String = """{"data":{"startExecution":{"id":"15","workspaceId":"1",""" +
            """"workflowId":"3","workflowName":"nightly-sync","status":"RUNNING","trigger":"MANUAL",""" +
            """"startedAt":"2026-08-17T13:22:11+02:00","temporalUrl":null}}}"""
    }
}

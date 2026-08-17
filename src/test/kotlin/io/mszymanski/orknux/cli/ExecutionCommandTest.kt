package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExecutionCommandTest {

    @TempDir
    lateinit var configHome: Path

    // ---------------------------------------------------------------- list

    @Test
    fun `lists a workspace's runs`() {
        StubGraphQlServer(body = executions(RUN_42, RUN_41, total = 2)).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "list")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            val lines = result.out.trimEnd().lines()
            assertEquals("ID  WORKFLOW      STATUS     TRIGGER   STARTED              DURATION", lines[0])
            // Composed rather than written out: the timestamp renders in the local zone, and
            // this test is about the columns, not about which zone the tests run in.
            val newer = formatTimestamp("2026-08-17T13:22:11+02:00")
            val older = formatTimestamp("2026-08-16T13:22:11+02:00")
            assertEquals("42  nightly-sync  FAILED     SCHEDULE  $newer  1m 2s", lines[1])
            assertEquals("41  nightly-sync  COMPLETED  MANUAL    $older  3s", lines[2])
        }
    }

    @Test
    fun `takes the workspace from the stored choice`() {
        StubGraphQlServer(body = executions(RUN_42, total = 1)).use { server ->
            inWorkspace(server.baseUrl, "3")

            run(server.baseUrl, "execution", "list")

            assertContains(server.lastBody!!, """"workspaceId":"3"""")
        }
    }

    @Test
    fun `lets --workspace look elsewhere without changing the stored choice`() {
        StubGraphQlServer(body = executions(RUN_42, total = 1)).use { server ->
            inWorkspace(server.baseUrl, "3")

            val result = run(server.baseUrl, "execution", "list", "--workspace", "9")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(server.lastBody!!, """"workspaceId":"9"""")
            assertEquals("3", SessionStore(configHome).read()?.workspaceId)
        }
    }

    @Test
    fun `asks for a workspace when none has been chosen`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            val result = run(server.baseUrl, "execution", "list")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "No workspace chosen. Run 'orkx workspace use <id>', or pass --workspace.")
            assertEquals(0, server.requestCount)
        }
    }

    /** A cap that goes unmentioned reads as the whole truth. */
    @Test
    fun `says when it is showing only some of the runs`() {
        StubGraphQlServer(body = executions(RUN_42, total = 137)).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "list")

            assertContains(result.out, "Showing 1 of 137. Ask for more with --limit.")
        }
    }

    @Test
    fun `says nothing about a cap when there is none`() {
        StubGraphQlServer(body = executions(RUN_42, total = 1)).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "list")

            assertFalse(result.out.contains("Showing"), result.out)
        }
    }

    @Test
    fun `passes the limit to the server`() {
        StubGraphQlServer(body = executions(RUN_42, total = 1)).use { server ->
            inWorkspace(server.baseUrl, "1")

            run(server.baseUrl, "execution", "list", "--limit", "5")

            assertContains(server.lastBody!!, """"size":5""")
        }
    }

    @Test
    fun `refuses a limit below one`() {
        StubGraphQlServer().use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "list", "-n", "0")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "--limit must be at least 1.")
        }
    }

    @Test
    fun `reports an empty workspace without failing`() {
        StubGraphQlServer(body = executions(total = 0)).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "list")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No runs yet in workspace 1.")
        }
    }

    // ---------------------------------------------------------------- get

    @Test
    fun `shows one run, its steps and what failed`() {
        StubGraphQlServer(body = DETAIL).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "get", "42")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Run 42")
            // The labels align to the widest of them, which is "Workspace".
            assertContains(result.out, "Workflow   nightly-sync (id 3)")
            assertContains(result.out, "Status     FAILED")
            assertContains(result.out, "Duration   1m 2s")
            assertContains(result.out, "Temporal   http://localhost:8233/runs/42")
            assertContains(result.out, "STATUS     KIND     NAME              DURATION")
            assertContains(result.out, "Post to Slack failed:")
            assertContains(result.out, "    Connection refused")
        }
    }

    @Test
    fun `reports a run that is not there`() {
        StubGraphQlServer(body = """{"data":{"execution":null}}""").use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "get", "999")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No run 999 at ${server.baseUrl}.")
        }
    }

    /** Unlike a workspace, a run the caller may not see is refused rather than hidden. */
    @Test
    fun `passes on a refusal for someone else's run`() {
        val forbidden = """{"errors":[{"message":"You do not have access to workspace \"backend\""}]}"""
        StubGraphQlServer(body = forbidden).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "get", "42")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "You do not have access to workspace \"backend\"")
        }
    }

    @Test
    fun `refuses a run id that cannot be one`() {
        StubGraphQlServer().use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "get", "latest")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'latest' is not a run id; those are numbers.")
            assertEquals(0, server.requestCount)
        }
    }

    // ---------------------------------------------------------------- restart

    @Test
    fun `restarts a run and names the one it started`() {
        val started = """{"data":{"rerunExecution":{"id":"43","workspaceId":"1","workflowId":"3",""" +
            """"workflowName":"nightly-sync","status":"RUNNING","trigger":"MANUAL",""" +
            """"startedAt":"2026-08-17T14:00:00+02:00","temporalUrl":"http://localhost:8233/runs/43"}}}"""
        StubGraphQlServer(body = started).use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "restart", "42")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Started run 43, a restart of 42.")
            assertContains(result.out, "Status    RUNNING")
            // Recorded as MANUAL whatever fired the original, and worth showing as such.
            assertContains(result.out, "Trigger   MANUAL")
            assertContains(result.out, "Follow it with 'orkx execution get 43'.")
            assertContains(server.lastBody!!, "mutation Rerun(\$id: ID!)")
            assertContains(server.lastBody!!, """"variables":{"id":"42"}""")
        }
    }

    /** The mutation returns a non-null type, so a missing run is an error and not a null. */
    @Test
    fun `reports a refusal to restart`() {
        StubGraphQlServer(body = """{"errors":[{"message":"No execution 999"}]}""").use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "restart", "999")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "No execution 999")
        }
    }

    @Test
    fun `does not start anything on a bad id`() {
        StubGraphQlServer().use { server ->
            inWorkspace(server.baseUrl, "1")

            val result = run(server.baseUrl, "execution", "restart", "all")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertEquals(0, server.requestCount)
        }
    }

    // ---------------------------------------------------------------- shared

    @Test
    fun `every execution command wants a session`() {
        for (args in listOf(
            listOf("execution", "list", "-w", "1"),
            listOf("execution", "get", "42"),
            listOf("execution", "restart", "42"),
        )) {
            val result = run("http://localhost:1", *args.toTypedArray())

            assertEquals(ExitCode.REJECTED, result.exitCode, args.toString())
            assertContains(result.err, "Not signed in. Run 'orkx login' first.", message = args.toString())
        }
    }

    @Test
    fun `every execution command reports an expired session`() {
        StubGraphQlServer(status = 401, body = "").use { server ->
            inWorkspace(server.baseUrl, "1")
            for (args in listOf(
                listOf("execution", "list"),
                listOf("execution", "get", "42"),
                listOf("execution", "restart", "42"),
            )) {
                val result = run(server.baseUrl, *args.toTypedArray())

                assertEquals(ExitCode.REJECTED, result.exitCode, args.toString())
                assertContains(result.err, "has expired", message = args.toString())
            }
        }
    }

    @Test
    fun `every execution command reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        inWorkspace(deadUrl, "1")

        for (args in listOf(
            listOf("execution", "list"),
            listOf("execution", "get", "42"),
            listOf("execution", "restart", "42"),
        )) {
            val result = run(deadUrl, *args.toTypedArray())

            assertEquals(ExitCode.UNREACHABLE, result.exitCode, args.toString())
        }
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC"))

    private fun inWorkspace(server: String, workspaceId: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", workspaceId, "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun run(server: String, vararg args: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val group = command.subcommands.getValue("execution")
        group.subcommands.getValue("list").getCommand<ExecutionListCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
        }
        group.subcommands.getValue("get").getCommand<ExecutionGetCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
        }
        group.subcommands.getValue("restart").getCommand<ExecutionRestartCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
        }

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val RUN_42 = """{"id":"42","workflowId":"3","workflowName":"nightly-sync","status":"FAILED",""" +
            """"trigger":"SCHEDULE","startedAt":"2026-08-17T13:22:11+02:00",""" +
            """"finishedAt":"2026-08-17T13:23:13+02:00","durationSeconds":62,"stoppedReason":null}"""

        const val RUN_41 = """{"id":"41","workflowId":"3","workflowName":"nightly-sync","status":"COMPLETED",""" +
            """"trigger":"MANUAL","startedAt":"2026-08-16T13:22:11+02:00",""" +
            """"finishedAt":"2026-08-16T13:22:14+02:00","durationSeconds":3,"stoppedReason":null}"""

        const val DETAIL = """{"data":{"execution":{"id":"42","workspaceId":"1","workflowId":"3",""" +
            """"workflowName":"nightly-sync","status":"FAILED","trigger":"SCHEDULE",""" +
            """"startedAt":"2026-08-17T13:22:11+02:00","finishedAt":"2026-08-17T13:23:13+02:00",""" +
            """"durationSeconds":62,"stoppedAtNodeKey":null,"stoppedReason":null,""" +
            """"error":"Connection refused","temporalUrl":"http://localhost:8233/runs/42","steps":[""" +
            """{"key":"n1","kind":"TRIGGER","name":"Webhook received","status":"COMPLETED",""" +
            """"startedAt":"2026-08-17T13:22:11+02:00","finishedAt":"2026-08-17T13:22:11+02:00",""" +
            """"durationSeconds":0,"error":null},""" +
            """{"key":"n2","kind":"ACTION","name":"Post to Slack","status":"FAILED",""" +
            """"startedAt":"2026-08-17T13:22:11+02:00","finishedAt":"2026-08-17T13:23:13+02:00",""" +
            """"durationSeconds":62,"error":"Connection refused"}]}}}"""

        fun executions(vararg runs: String, total: Int): String =
            """{"data":{"workspaceExecutions":{"content":[${runs.joinToString(",")}],""" +
                """"page":0,"size":20,"totalElements":$total,"totalPages":1}}}"""
    }
}

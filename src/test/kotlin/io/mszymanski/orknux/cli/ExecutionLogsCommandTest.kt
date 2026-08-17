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

class ExecutionLogsCommandTest {

    @TempDir
    lateinit var configHome: Path

    @Test
    fun `shows the log, oldest first, with the step that wrote each line`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            val lines = result.out.trim().lines()
            assertContains(lines[0], "Run 42")
            assertContains(lines[0], "nightly-sync")
            assertContains(lines[0], "FAILED")
            // The node column carries the step's name, not the key the server sends.
            assertContains(result.out, "INFO     run")
            assertContains(result.out, "Post to Slack")
            assertFalse(result.out.contains("n2  "), "the raw node key should not be shown: ${result.out}")
        }
    }

    @Test
    fun `keeps the columns aligned and lets the message run on`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42")

            val entries = result.out.trim().lines().drop(2).filter { it.isNotBlank() }
            // Every line starts with a timestamp of the same width, so the levels line up.
            val levelColumns = entries.map { it.indexOf(it.trimStart().take(1)) }
            assertEquals(1, levelColumns.distinct().size, entries.toString())
        }
    }

    /** A stack trace in one log line has to stay readable beside single-line entries. */
    @Test
    fun `indents the rest of a message under the first line`() {
        StubGraphQlServer(body = MULTILINE).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42")

            val lines = result.out.trim().lines()
            val first = lines.first { it.contains("Something broke") }
            val messageColumn = first.indexOf("Something broke")

            // Indented to where the message began, carrying no columns of its own — and the
            // message's own indentation is left alone, which is what makes a trace legible.
            assertEquals(" ".repeat(messageColumn) + "    at some.Frame.method", lines[lines.indexOf(first) + 1])
            assertEquals(" ".repeat(messageColumn) + "    at another.Frame.method", lines[lines.indexOf(first) + 2])
        }
    }

    @Test
    fun `names the run and asks the server for its log`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            logs(server.baseUrl, "42")

            assertEquals(GRAPHQL_PATH, server.lastPath)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertContains(server.lastBody!!, "query ExecutionLogs(\$id: ID!)")
            assertContains(server.lastBody!!, "logs { id nodeKey at level message }")
            assertContains(server.lastBody!!, """"variables":{"id":"42"}""")
        }
    }

    @Test
    fun `filters to one step by name or key`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val byName = logs(server.baseUrl, "42", "--step", "slack")
            assertEquals(ExitCode.OK, byName.exitCode, byName.err)
            assertContains(byName.out, "could not send")
            assertFalse(byName.out.contains("Run started"), byName.out)

            val byKey = logs(server.baseUrl, "42", "--step", "n2")
            assertContains(byKey.out, "could not send")
        }
    }

    @Test
    fun `says when a filter matched nothing`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42", "--step", "nowhere")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "Nothing in run 42's log came from a step matching 'nowhere'.")
        }
    }

    @Test
    fun `says when a run logged nothing at all`() {
        val quiet = """{"data":{"execution":{"id":"42","workflowName":"nightly-sync","status":"COMPLETED",""" +
            """"steps":[],"logs":[]}}}"""
        StubGraphQlServer(body = quiet).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42")

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "Run 42 logged nothing.")
        }
    }

    @Test
    fun `reports a run that is not there`() {
        StubGraphQlServer(body = """{"data":{"execution":null}}""").use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "999")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No run 999 at ${server.baseUrl}.")
        }
    }

    @Test
    fun `refuses a run id that cannot be one`() {
        StubGraphQlServer().use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "tail")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'tail' is not a run id; those are numbers.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `says to sign in when there is no session`() {
        val result = logs("http://localhost:1", "42")

        assertEquals(ExitCode.REJECTED, result.exitCode)
        assertContains(result.err, "Not signed in. Run 'orkx login' first.")
    }

    @Test
    fun `reports an expired session`() {
        StubGraphQlServer(status = 401, body = "").use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    @Test
    fun `reports a server that is not there`() {
        val deadUrl = StubGraphQlServer().use { it.baseUrl }
        signedIn(deadUrl)

        val result = logs(deadUrl, "42")

        assertEquals(ExitCode.UNREACHABLE, result.exitCode)
        assertContains(result.err, "Cannot reach the server at $deadUrl")
    }

    // ------------------------------------------------------------- styling

    @Test
    fun `colours the log when styling is on`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val plain = logs(server.baseUrl, "42")
            val coloured = logs(server.baseUrl, "42") { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length, "colour should add codes")
            // …and add nothing else: the same text, once the codes are taken out.
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    @Test
    fun `--color never leaves the output plain`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42", "--color", "never")

            assertEquals(result.out, stripAnsi(result.out))
        }
    }

    /** Lower case too, because the root command allows it. */
    @Test
    fun `--color always styles even with nothing attached`() {
        StubGraphQlServer(body = LOG).use { server ->
            signedIn(server.baseUrl)

            val result = logs(server.baseUrl, "42", "--color", "always")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertTrue(result.out != stripAnsi(result.out), "expected colour codes in the output")
        }
    }

    @Test
    fun `--color rejects a word that is not one of the three`() {
        val result = logs("http://localhost:1", "42", "--color", "sometimes")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "sometimes")
    }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun logs(
        server: String,
        vararg args: String,
        configure: (ExecutionLogsCommand) -> Unit = {},
    ): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        val logs = command.subcommands.getValue("execution").subcommands.getValue("logs")
            .getCommand<ExecutionLogsCommand>()
        logs.store = SessionStore(configHome)
        logs.clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
        configure(logs)

        val exitCode = command.execute("execution", "logs", *args)
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        const val LOG = """{"data":{"execution":{"id":"42","workflowName":"nightly-sync","status":"FAILED",""" +
            """"steps":[{"key":"n1","name":"Webhook received"},{"key":"n2","name":"Post to Slack"}],""" +
            """"logs":[""" +
            """{"id":"1","nodeKey":null,"at":"2026-08-17T13:22:11+02:00","level":"INFO","message":"Run started"},""" +
            """{"id":"2","nodeKey":"n1","at":"2026-08-17T13:22:12+02:00","level":"SUCCESS",""" +
            """"message":"Received 3 items"},""" +
            """{"id":"3","nodeKey":"n2","at":"2026-08-17T13:23:13+02:00","level":"ERROR",""" +
            """"message":"could not send: channel_not_found"}]}}}"""

        const val MULTILINE = """{"data":{"execution":{"id":"42","workflowName":"nightly-sync","status":"FAILED",""" +
            """"steps":[{"key":"n2","name":"Post to Slack"}],""" +
            """"logs":[{"id":"1","nodeKey":"n2","at":"2026-08-17T13:23:13+02:00","level":"ERROR",""" +
            """"message":"Something broke\n    at some.Frame.method\n    at another.Frame.method"}]}}}"""
    }
}

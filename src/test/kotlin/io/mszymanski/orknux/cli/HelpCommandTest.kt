package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpCommandTest {

    @Test
    fun `prints the version above the help`() {
        val result = help()

        assertEquals(ExitCode.OK, result.exitCode, result.err)
        val lines = result.out.trimEnd().lines()
        assertTrue(lines.first().startsWith("orkx "), lines.first())
        assertContains(result.out, "Command line client for orknux-server.")
        assertContains(result.out, "Commands:")
    }

    /** One source for it, so `orkx help` and `orkx --version` cannot disagree. */
    @Test
    fun `says the same version as --version`() {
        val version = run("--version").out.trim()

        assertContains(help().out, version)
    }

    @Test
    fun `lists every command`() {
        val out = help().out

        for (command in listOf(
            "server", "login", "workspace", "execution", "chat", "variable", "plugin", "admin", "help",
        )) {
            assertContains(out, command)
        }
    }

    @Test
    fun `shows one command's help when named`() {
        val result = help("chat")

        assertEquals(ExitCode.OK, result.exitCode, result.err)
        assertContains(result.out, "Talk to a workspace's models.")
        // The subcommand's own, not the root's.
        assertContains(result.out, "Open an interactive chat session.")
        assertTrue(result.out.trimEnd().lines().first().startsWith("orkx "))
    }

    @Test
    fun `answers for an alias too`() {
        assertContains(help("var").out, "Read and write a workspace's variables.")
    }

    @Test
    fun `says so for a command that does not exist`() {
        val result = help("teleport")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "There is no 'teleport' command. 'orkx help' lists them.")
    }

    @Test
    fun `--color never leaves the help plain`() {
        val result = help("--color", "never")

        assertEquals(result.out, stripAnsi(result.out))
    }

    @Test
    fun `--color always colours it`() {
        val plain = help("--color", "never").out
        val coloured = help("--color", "always").out

        assertTrue(coloured.length > plain.length, "colour should add codes")
    }

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun help(vararg args: String): Result = run("help", *args)

    private fun run(vararg args: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))

        val exitCode = command.execute(*args)
        return Result(exitCode, out.toString(), err.toString())
    }
}

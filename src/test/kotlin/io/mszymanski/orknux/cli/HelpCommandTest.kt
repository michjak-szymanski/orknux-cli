// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The licence's section 7(b) term requires this attribution to be preserved where the
     * program prints it, so a change that drops it is a change that empties the term.
     */
    @Test
    fun `--version carries the attribution the licence protects`() {
        val out = run("--version").out

        assertContains(out, "Copyright (C)")
        assertContains(out, "Michał Szymański")
        assertContains(out, "AGPL-3.0-or-later")
        assertContains(out, "NOTICE")
        assertContains(out, "https://github.com/michjak-szymanski/orknux-cli")
    }

    /** `orkx --version | head -1` stays a version string for anything that reads it that way. */
    @Test
    fun `--version keeps the version on a line of its own`() {
        val first = run("--version").out.trimEnd().lines().first()

        assertTrue(first.startsWith("orkx "), first)
        assertFalse(first.contains("Copyright"), first)
    }

    /** Printed where it is read, not only where it is filed: help shows it too. */
    @Test
    fun `help carries the attribution as well`() {
        assertContains(help().out, "AGPL-3.0-or-later")
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

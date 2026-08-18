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

class CompletionCommandTest {

    @Test
    fun `writes a bash script that knows the commands`() {
        val result = completion("bash")

        assertEquals(ExitCode.OK, result.exitCode, result.err)
        assertContains(result.out, "complete -F")
        assertContains(result.out, "orkx")
        for (command in listOf("server", "workspace", "workflow", "execution", "chat", "variable", "plugin")) {
            assertContains(result.out, command)
        }
    }

    /** zsh reads the same script, after `bashcompinit`. One script, not two to keep in step. */
    @Test
    fun `writes the same script for zsh`() {
        assertEquals(completion("bash").out, completion("zsh").out)
    }

    @Test
    fun `writes a powershell script that registers a completer`() {
        val result = completion("powershell")

        assertEquals(ExitCode.OK, result.exitCode, result.err)
        assertContains(result.out, "Register-ArgumentCompleter -Native -CommandName orkx")
        assertContains(result.out, "System.Management.Automation.CompletionResult")
    }

    /**
     * Generated from the live command tree, so a command that exists is a command that
     * completes — and one added later needs nothing done here.
     */
    @Test
    fun `powershell knows every command and every nested one`() {
        val script = completion("powershell").out

        assertContains(script, "'orkx' = @(")
        assertContains(script, "'orkx chat' = @(")
        assertContains(script, "'orkx variable catalog' = @(")
        assertContains(script, "'orkx admin' = @(")
        // The leaf's own options, which is what makes completing past the verb worth anything.
        assertContains(script, "'orkx execution list' = @(")
        assertContains(script, "--limit")
        assertContains(script, "--workspace")
    }

    @Test
    fun `powershell offers an alias as readily as the name`() {
        val script = completion("powershell").out

        assertContains(script, "'var'")
        assertContains(script, "'orkx var' = @(")
    }

    /** Short names would double the list without adding a thing anybody is looking for. */
    @Test
    fun `powershell offers long option names only`() {
        val line = completion("powershell").out.lines().first { it.startsWith("        'orkx' = @(") }

        assertContains(line, "--color")
        assertFalse(line.contains("'-h'"), line)
        assertFalse(line.contains("'-V'"), line)
    }

    @Test
    fun `wants to be told which shell`() {
        val result = completion()

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertTrue(result.err.isNotEmpty())
    }

    @Test
    fun `refuses a shell it cannot write for`() {
        val result = completion("fish")

        assertEquals(ExitCode.USAGE, result.exitCode)
        assertContains(result.err, "fish")
    }

    /** Case is not the point: somebody typing PowerShell means powershell. */
    @Test
    fun `takes the shell however it is spelled`() {
        assertEquals(ExitCode.OK, completion("PowerShell").exitCode)
        assertEquals(ExitCode.OK, completion("BASH").exitCode)
    }

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun completion(vararg args: String): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))

        val exitCode = command.execute("completion", *args)
        return Result(exitCode, out.toString(), err.toString())
    }
}

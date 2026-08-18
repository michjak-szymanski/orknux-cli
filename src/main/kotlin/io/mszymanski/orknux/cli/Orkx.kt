// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IVersionProvider
import picocli.CommandLine.Help
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Parameters
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.ScopeType
import picocli.CommandLine.Spec
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The `orkx` command itself, which does nothing but dispatch. Every subcommand is
 * a `Callable<Int>` returning its own exit code, so a script can tell a rejected
 * password from an unreachable server — see [ExitCode].
 */
@Command(
    name = "orkx",
    mixinStandardHelpOptions = true,
    versionProvider = OrkxVersion::class,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        ServerCommand::class,
        LoginCommand::class,
        WorkspaceCommand::class,
        WorkflowCommand::class,
        ExecutionCommand::class,
        ChatCommand::class,
        VariableCommand::class,
        PluginCommand::class,
        AdminCommand::class,
        CompletionCommand::class,
        HelpCommand::class,
    ],
    description = ["Command line client for orknux-server."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class Orkx : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    /**
     * Inherited by every subcommand, so `orkx --color never execution list` and
     * `orkx execution list --color never` both work. `auto` asks picocli, which already
     * honours `NO_COLOR` and knows whether anything is attached to the output.
     */
    @Option(
        names = ["--color"],
        paramLabel = "WHEN",
        scope = ScopeType.INHERIT,
        description = ["Colour the output: auto, always or never. Defaults to auto."],
    )
    var color: ColorWhen = ColorWhen.AUTO

    override fun run() {
        // Bare `orkx` is a usage error, not a no-op: show the commands and exit 2.
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * `orkx help` — the same help as `--help`, with the version above it.
 *
 * Worth having as a command of its own because it is what people type: `orkx help` before
 * `orkx --help`, and `orkx help chat` before `orkx chat --help`. The version goes at the top
 * because the first thing anybody wants alongside help is which build they are asking.
 */
@Command(
    name = "help",
    description = ["Show this help, and which version this is."],
)
class HelpCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "COMMAND",
        description = ["Show help for this command instead."],
    )
    var command: String? = null

    override fun run() {
        val root = spec.root().commandLine()
        val out = spec.commandLine().out

        // Straight from the version provider, so this cannot drift from --version.
        spec.root().version().forEach(out::println)
        out.println()

        val wanted = command?.trim()?.takeIf { it.isNotEmpty() }
        val target = if (wanted == null) root else {
            root.subcommands[wanted] ?: throw ParameterException(
                spec.commandLine(),
                "There is no '$wanted' command. 'orkx help' lists them.",
            )
        }
        // Picocli colours its own usage by its own reckoning, which is not this CLI's: told
        // explicitly, so --color decides the help exactly as it decides everything else, and
        // `auto` uses the terminal test in styleFor rather than picocli's guess from TERM.
        val ansi = if (styleFor(spec).enabled) Help.Ansi.ON else Help.Ansi.OFF
        target.usage(out, Help.ColorScheme.Builder(target.colorScheme).ansi(ansi).build())
    }
}

/** Exit codes the subcommands return. Documented in the README, so keep them stable. */
object ExitCode {
    const val OK = 0

    /**
     * The server understood the request and refused it: a wrong password, a session it no
     * longer has, or an operation it would not perform. All of them are answered by
     * signing in again or asking for access — not by retrying.
     */
    const val REJECTED = 1

    /** Bad arguments. Picocli's own value; here so it reads alongside the others. */
    const val USAGE = 2

    /** Nothing answered at that address, or it answered something unusable. */
    const val UNREACHABLE = 3

    /** The exchange worked; something on this machine did not. */
    const val SOFTWARE = 4

    /** No such thing there — or none the caller may see, which the server does not distinguish. */
    const val NOT_FOUND = 5

    /**
     * Asked and answered, and the answer is that something is unwell. Only `admin monitoring`
     * uses it: a check that has to be read to find out whether it passed is not much of a
     * check.
     */
    const val DEGRADED = 6
}

/**
 * What `orkx --version` prints, and what `orkx help` prints above the help.
 *
 * All of it is filtered into the jar by Maven rather than hardcoded here, so neither the
 * version nor the name can drift from the pom. The lines below the version are the attribution
 * the licence's section 7(b) term requires to be preserved — a term protecting notices the
 * program never printed would protect nothing.
 */
class OrkxVersion : IVersionProvider {
    override fun getVersion(): Array<String> {
        val properties = Properties()
        OrkxVersion::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { resource ->
            // As UTF-8, and by a Reader: Properties.load(InputStream) decodes ISO-8859-1,
            // which would leave the ł of the name it carries as two wrong characters.
            resource.reader(Charsets.UTF_8).use(properties::load)
        }
        // The version keeps the first line to itself, so `orkx --version | head -1` is still
        // a version string for anything reading it that way.
        return listOfNotNull(
            "orkx ${properties.getProperty("version") ?: "unknown"}",
            properties.getProperty("copyright"),
            properties.getProperty("licence"),
            properties.getProperty("source"),
        ).toTypedArray()
    }

    private companion object {
        const val VERSION_RESOURCE = "/orkx-version.properties"
    }
}

/** The whole command tree, wired but not run. Tests execute this; [main] adds the exit. */
internal fun orkxCommandLine(): CommandLine = CommandLine(Orkx())
    .setCaseInsensitiveEnumValuesAllowed(true)

fun main(args: Array<String>) {
    exitProcess(orkxCommandLine().execute(*args))
}

// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Callable

@Serializable
data class PluginsData(val plugins: List<Plugin> = emptyList())

@Serializable
data class UnloadPluginData(val unloadPlugin: Boolean = false)

/**
 * The `plugin` group: what this installation has been taught to do.
 *
 * A group of its own rather than a corner of `admin`, because writing a plugin is most of the
 * work and only the last step of it is administration. The server still requires the
 * administrator role for every one of these, and is what enforces it.
 */
@Command(
    name = "plugin",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        PluginListCommand::class,
        PluginGenerateCommand::class,
        PluginLoadCommand::class,
        PluginUnloadCommand::class,
    ],
    description = ["Write, load and unload plugins. Administrators only."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class PluginCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/** Kilobytes, because a plugin is a script and 2 MB is as large as one may be. */
internal fun formatBytes(bytes: Double): String = when {
    bytes < 1024 -> "${bytes.toLong()} B"
    bytes < 1024 * 1024 -> "${(bytes / 1024).toLong()} KB"
    else -> String.format("%.1f MB", bytes / (1024 * 1024))
}

/**
 * `orkx plugin list` — what is loaded, and what each one brings.
 *
 * The functions are the point of a plugin, so they are listed under it rather than counted.
 * They are shown as the plugin declared them and as they are called now they are registered:
 * a plugin's `isTeammate` is reached as `teammates_isTeammate`.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List the plugins loaded into this installation."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class PluginListCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val plugins = try {
            clientFactory(session.server, session.cookie)
                .query(PLUGINS_QUERY, JsonObject(emptyMap()), PluginsData.serializer())
                .plugins
        } catch (e: SessionExpired) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: OperationRefused) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        if (plugins.isEmpty()) {
            err.println("No plugins are loaded at ${session.server}.")
            return ExitCode.OK
        }

        plugins.forEachIndexed { index, plugin ->
            if (index > 0) out.println()
            print(out, style, plugin)
        }
        return ExitCode.OK
    }

    private fun print(out: PrintWriter, style: Style, plugin: Plugin) {
        out.println("${style.name(plugin.id)}  ${style.name(plugin.key)}  ${style.faint(plugin.filename)}")
        val facts = listOfNotNull(
            "API version ${plugin.apiVersion}",
            formatBytes(plugin.sizeBytes),
            plugin.uploadedAt.takeIf { it.isNotBlank() }?.let { "loaded ${formatTimestamp(it)}" },
            plugin.uploadedBy.takeIf { it.isNotBlank() }?.let { "by $it" },
        )
        out.println("  ${style.faint(facts.joinToString("  "))}")

        if (plugin.declaredFunctions.isEmpty()) {
            out.println("  ${style.faint("declares no functions")}")
            return
        }
        val rows = plugin.declaredFunctions.map { function ->
            listOf(
                // What it is actually called once registered.
                "${plugin.key}_${function.name}${function.signature}",
                style.faint(function.description.orEmpty()),
            )
        }
        renderTable(listOf("", ""), rows).drop(1).forEach { out.println("  $it") }
    }

    private companion object {
        const val PLUGINS_QUERY =
            "query Plugins { plugins { id key name filename sizeBytes apiVersion uploadedAt uploadedBy " +
                "declaredFunctions { name description signature } } }"
    }
}

/**
 * `orkx plugin generate` — a plugin to start from, written by the server.
 *
 * Written by the server on purpose: the API version in it is the one that installation runs
 * and the value types are the ones it has, so a template can never describe a contract
 * different from the one that will judge it. That is also why this needs a server to talk to
 * rather than printing something `orkx` carries around.
 *
 * Goes to standard output unless a file is named, so it composes — pipe it, redirect it, read
 * it. With `--output` it writes a file, and will not write over one that is already there:
 * somebody's work-in-progress plugin is not this command's to replace.
 */
@Command(
    name = "generate",
    mixinStandardHelpOptions = true,
    description = ["Write a starter plugin, as this server defines one."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class PluginGenerateCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-o", "--output"],
        paramLabel = "PATH",
        description = ["Write it here instead of to standard output."],
    )
    var output: String? = null

    @Option(names = ["--force"], description = ["Write over the file if it is already there."])
    var force: Boolean = false

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> PluginClient = { url, cookie -> PluginClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val destination = output?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                Path.of(it)
            } catch (_: InvalidPathException) {
                throw ParameterException(spec.commandLine(), "'$it' is not a path.")
            }
        }
        // Checked before asking the server, so a refusal to overwrite costs nothing.
        if (destination != null && Files.exists(destination) && !force) {
            throw ParameterException(
                spec.commandLine(),
                "There is already a file at $destination. Pass --force to write over it.",
            )
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val template = try {
            clientFactory(session.server, session.cookie).template()
        } catch (e: SessionExpired) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: OperationRefused) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        if (destination == null) {
            // Printed as it came: this is a file somebody is about to edit.
            out.print(template)
            out.flush()
            return ExitCode.OK
        }

        return try {
            Files.writeString(destination, template)
            // To stderr, so that the file's path does not end up in whatever reads stdout.
            err.println("Wrote ${style.name(destination.toString())}.")
            err.println(style.faint("Load it with 'orkx plugin load --file $destination'."))
            ExitCode.OK
        } catch (e: IOException) {
            err.println("Could not write $destination: ${e.message}")
            ExitCode.SOFTWARE
        }
    }
}

/**
 * `orkx plugin load --file <path>` — sends a plugin to the installation.
 *
 * Loading a key that is already there replaces it and keeps the row's id, which is how a
 * plugin is iterated on: upload it again. The output says which of the two happened.
 *
 * Only the file being there is checked here. Its size, its extension, whether it is UTF-8 and
 * whether it holds up its contract are the server's to judge, and it answers each with a
 * sentence written for the person who chose the file.
 */
@Command(
    name = "load",
    mixinStandardHelpOptions = true,
    description = ["Load a plugin, or replace one already loaded."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class PluginLoadCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-f", "--file"],
        paramLabel = "PATH",
        required = true,
        description = ["The plugin to load: a .js or .mjs file."],
    )
    var file: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var uploadFactory: (String, String) -> PluginClient = { url, cookie ->
        PluginClient(url, cookie)
    }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val path = try {
            Path.of(file.trim())
        } catch (_: InvalidPathException) {
            throw ParameterException(spec.commandLine(), "'$file' is not a path.")
        }
        if (!Files.isRegularFile(path)) {
            throw ParameterException(spec.commandLine(), "There is no file at $path.")
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val loaded = try {
            uploadFactory(session.server, session.cookie).load(path)
        } catch (e: SessionExpired) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: OperationRefused) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        val plugin = loaded.plugin
        val what = if (loaded.replaced) "Replaced" else "Loaded"
        out.println("$what ${style.name(plugin.key)} as plugin ${style.name(plugin.id)}, API version ${plugin.apiVersion}.")

        if (loaded.provides.isEmpty()) {
            out.println(style.faint("It provides no functions."))
        } else {
            out.println("It provides:")
            loaded.provides.forEach { out.println("  $it") }
        }
        return ExitCode.OK
    }
}

/**
 * `orkx plugin unload <id>` — takes a plugin out of the installation.
 *
 * Asks first: the source is held by the server, so unloading it is gone unless whoever loaded
 * it still has the file. The server refuses outright when something is still calling one of
 * its functions, which is the case that would actually break a workflow.
 */
@Command(
    name = "unload",
    mixinStandardHelpOptions = true,
    description = ["Take a plugin out of the installation."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class PluginUnloadCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The plugin to unload."])
    var id: String = ""

    @Option(names = ["-y", "--yes"], description = ["Do not ask first."])
    var yes: Boolean = false

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null
    internal var interactive: Boolean = attachedToTerminal()
    internal var readLine: () -> String? = { readStandardInputLine() }

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = serverIdOrNull(id) ?: throw ParameterException(
            spec.commandLine(),
            "'${id.trim()}' is not a plugin id; those are numbers. 'orkx plugin list' has them.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        if (!yes) {
            if (!interactive) {
                err.println("Unloading a plugin takes its source with it. Pass --yes to say you meant to.")
                return ExitCode.USAGE
            }
            out.print("Unload plugin $wanted from this installation? [y/N] ")
            out.flush()
            val answer = readLine()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") {
                out.println("Plugin $wanted was left alone.")
                return ExitCode.OK
            }
        }

        return try {
            val unloaded = clientFactory(session.server, session.cookie).query(
                UNLOAD_PLUGIN,
                buildJsonObject { put("id", wanted) },
                UnloadPluginData.serializer(),
            ).unloadPlugin

            if (unloaded) {
                out.println("Unloaded plugin ${style.name(wanted)}.")
                ExitCode.OK
            } else {
                // Not the path a missing plugin takes — see below — but the mutation is
                // declared to return a boolean, so a false is answered rather than ignored.
                err.println("No plugin $wanted at ${session.server} to unload.")
                ExitCode.NOT_FOUND
            }
        } catch (e: SessionExpired) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // Two different things arrive here, and both are the server's to word: something
            // still calling its functions, and a plugin that is not there at all. Unlike
            // `deleteChat`, which answers false, `unloadPlugin` throws for the second — so a
            // missing plugin is a refusal here and not a NOT_FOUND. Telling them apart would
            // mean matching on the server's sentences, which is worse than the inconsistency.
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private companion object {
        const val UNLOAD_PLUGIN = "mutation UnloadPlugin(\$id: ID!) { unloadPlugin(id: \$id) }"
    }
}

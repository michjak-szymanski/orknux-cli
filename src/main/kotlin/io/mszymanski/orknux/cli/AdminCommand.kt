// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.PrintWriter
import java.util.concurrent.Callable

/**
 * The `admin` group: the installation rather than a workspace.
 *
 * Everything here needs the administrator role, and the server is what enforces that. These
 * commands do not check first — asking and being refused is one round trip and one truth,
 * where checking a role locally is a second copy of the rule to get wrong.
 */
@Command(
    name = "admin",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [AdminDoctorCommand::class, AdminMonitoringCommand::class],
    description = ["Look at the installation. Administrators only."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class AdminCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/** What one configuration check concluded. */
@Serializable
data class DoctorCheck(
    val name: String,
    val verdict: String = "",
    val detail: String = "",
)

@Serializable
data class DoctorData(val doctor: List<DoctorCheck> = emptyList())

/**
 * `orkx admin doctor` — whether this installation is configured correctly, which is a
 * different question from whether it can reach things.
 *
 * `admin monitoring` asks the second: the database answers, the directory answers, Temporal
 * answers. That can be entirely green while the installation is broken — a secret key that was
 * never set is checked on first use, so the server starts, monitoring is happy, and every
 * credential write fails hours later with a stack trace. This asks the first question.
 *
 * Verdicts are the server's. `FAIL` and `WARN` are shouted and `ok` is not, because a list of
 * checks is read by looking for what is wrong with it.
 */
@Command(
    name = "doctor",
    mixinStandardHelpOptions = true,
    description = ["Check whether this installation is configured correctly."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class AdminDoctorCommand : Callable<Int> {

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

        val checks = try {
            clientFactory(session.server, session.cookie)
                .query(DOCTOR_QUERY, JsonObject(emptyMap()), DoctorData.serializer())
                .doctor
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

        if (checks.isEmpty()) {
            err.println("The server at ${session.server} ran no checks.")
            return ExitCode.OK
        }

        val rows = checks.map { check ->
            listOf(style.status(verdict(check.verdict)), check.name, check.detail)
        }
        renderTable(listOf("", "", ""), rows).drop(1).forEach(out::println)

        val failed = checks.count { it.verdict.equals("FAIL", ignoreCase = true) }
        val warned = checks.count { it.verdict.equals("WARN", ignoreCase = true) }
        if (failed > 0 || warned > 0) {
            out.println()
            out.println(
                style.faint(
                    listOfNotNull(
                        "$failed failed".takeIf { failed > 0 },
                        "$warned to look at".takeIf { warned > 0 },
                    ).joinToString(", ") + ", of ${checks.size} checks.",
                ),
            )
        }

        // A check that has to be read to find out whether it passed is not much of a check.
        // A warning is not a failure: it works, it is just probably not what was meant.
        return if (failed > 0) ExitCode.DEGRADED else ExitCode.OK
    }

    /** Shouted when it is wrong, quiet when it is not, as the verdicts read best that way. */
    private fun verdict(raw: String): String = when {
        raw.equals("OK", ignoreCase = true) -> "ok"
        else -> raw.uppercase()
    }

    private companion object {
        const val DOCTOR_QUERY = "query Doctor { doctor { name verdict detail } }"
    }
}

/** Something a component needs to be up. `url` is its own interface, where it has one. */
@Serializable
data class Dependency(
    val name: String,
    val description: String = "",
    val reachable: Boolean = false,
    val detail: String = "",
    val url: String? = null,
)

/**
 * One of the platform's services, and what it says about itself. Status is a string rather
 * than an enum for the same reason a run's is: the server may learn another one, and a word
 * printed uncoloured is still the word.
 */
@Serializable
data class Component(
    val name: String,
    val description: String = "",
    val status: String = "",
    val version: String? = null,
    val detail: String = "",
    val lastCheckedAt: String = "",
    val dependencies: List<Dependency> = emptyList(),
)

@Serializable
data class ComponentsData(val components: List<Component> = emptyList())

/**
 * `orkx admin monitoring` — what the UI's monitoring page shows: each of the platform's
 * services, and what it last said about itself.
 *
 * Asking performs the checks. The server runs a `SELECT 1` at the database, lists the
 * directory, and probes every service that can say whether it is up — so this reports what is
 * true now, and takes as long as those checks take.
 *
 * A degraded server still answers: the component reports `DEGRADED` and names what it could
 * not reach, which is the useful case rather than an error to hide.
 */
@Command(
    name = "monitoring",
    mixinStandardHelpOptions = true,
    description = ["Show system health and component status."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class AdminMonitoringCommand : Callable<Int> {

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

        val components = try {
            clientFactory(session.server, session.cookie)
                .query(COMPONENTS_QUERY, JsonObject(emptyMap()), ComponentsData.serializer())
                .components
        } catch (e: SessionExpired) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // "This action requires the administrator role" arrives here, in the server's words.
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        if (components.isEmpty()) {
            err.println("The server at ${session.server} reported no components.")
            return ExitCode.OK
        }

        components.forEachIndexed { index, component ->
            if (index > 0) out.println()
            print(out, style, component)
        }

        // Worth an exit code: this is a monitoring command, and something asking it wants to
        // know without reading the words.
        return if (components.any { it.status.uppercase() != "HEALTHY" }) ExitCode.DEGRADED else ExitCode.OK
    }

    private fun print(out: PrintWriter, style: Style, component: Component) {
        val version = component.version?.takeIf { it.isNotBlank() && it != "unknown" }
        out.println(
            buildString {
                append(style.status(component.status.ifEmpty { "UNKNOWN" }))
                append("  ")
                append(style.name(component.name))
                version?.let { append(" ").append(style.faint(it)) }
            },
        )
        if (component.description.isNotBlank()) out.println("  ${style.faint(component.description)}")
        if (component.detail.isNotBlank()) out.println("  ${component.detail}")
        if (component.lastCheckedAt.isNotBlank()) {
            out.println("  ${style.faint("checked ${formatTimestamp(component.lastCheckedAt)}")}")
        }

        if (component.dependencies.isEmpty()) return
        out.println()
        // Stated as up or down rather than as a boolean a reader has to interpret.
        val rows = component.dependencies.map { dependency ->
            listOf(
                if (dependency.reachable) style.good("up") else style.bad("down"),
                dependency.name,
                dependency.detail,
                dependency.url?.let(style::faint) ?: "",
            )
        }
        renderTable(HEADINGS, rows)
            // Four unlabelled columns: the heading line is empty, so it is dropped rather
            // than printed as a blank row.
            .drop(1)
            .forEach { out.println("  $it") }
    }

    private companion object {
        val HEADINGS = listOf("", "", "", "")

        const val COMPONENTS_QUERY =
            "query Components { components " +
                "{ name description status version detail lastCheckedAt " +
                "dependencies { name description reachable detail url } } }"
    }
}

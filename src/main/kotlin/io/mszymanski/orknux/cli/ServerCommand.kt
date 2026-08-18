// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.Callable

/** The `server` group: which installation everything else talks to. */
@Command(
    name = "server",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [ServerUseCommand::class, ServerInfoCommand::class],
    description = ["Choose which orknux-server to talk to."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ServerCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * `orkx server use <url>` — points everything at an installation, before there is any
 * question of signing in.
 *
 * The address is checked rather than merely written down: `GET /api/session` answers 401 on a
 * working orknux-server that does not know us, which is enough to tell a live installation
 * from a typo, a stopped server, or somebody else's web site on that port.
 *
 * Moving to a different server drops the stored session. A `JSESSIONID` belongs to the server
 * that issued it, so keeping it would mean sending one installation's cookie to another — no
 * use to anybody, and not something to do quietly with a credential.
 */
@Command(
    name = "use",
    mixinStandardHelpOptions = true,
    description = ["Point orkx at an orknux-server."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ServerUseCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        paramLabel = "URL",
        description = ["Base URL of the server, such as http://localhost:8080."],
    )
    var url: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String) -> SessionClient = { SessionClient(it) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val target = try {
            normalizeBaseUrl(url)
        } catch (e: IllegalArgumentException) {
            throw ParameterException(spec.commandLine(), e.message ?: "Unusable server URL.")
        }

        val previous = store.read()
        val samePlace = previous?.server == target
        // The cookie is only worth presenting to the server that issued it.
        val cookie = previous?.cookie?.takeIf { samePlace }

        val probe = try {
            clientFactory(target).probe(cookie)
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        if (probe is Probe.NotOrknux) {
            err.println("Something is answering at $target, but it is not orknux-server: it ${probe.reason}.")
            return ExitCode.UNREACHABLE
        }

        val kept = previous?.takeIf { samePlace }
        val session = StoredSession(
            server = target,
            username = kept?.username,
            cookie = kept?.cookie,
            // The workspace belongs to the server, so it goes with the session.
            workspaceId = kept?.workspaceId,
            workspaceName = kept?.workspaceName,
        )
        try {
            store.write(session)
        } catch (e: IOException) {
            err.println("Could not write ${store.file}: ${e.message}")
            return ExitCode.SOFTWARE
        }

        out.println("Now talking to ${style.name(target)}.")
        when (probe) {
            is Probe.SignedIn -> out.println("Signed in as ${style.name(probe.user.username)}.")
            Probe.Unauthenticated -> {
                if (previous != null && !samePlace && previous.cookie != null) {
                    out.println(style.faint("The session for ${previous.server} was dropped."))
                }
                out.println("Run 'orkx login' to sign in.")
            }
            // Already returned above.
            is Probe.NotOrknux -> Unit
        }
        return ExitCode.OK
    }
}

/** The switches that belong to the installation rather than to a workspace. */
@Serializable
data class InstallationSettings(
    val chatEnabled: Boolean = false,
    val attachmentsEnabled: Boolean = false,
    val attachmentMaxFileSizeMb: Int = 0,
)

@Serializable
data class InstallationSettingsData(val installationSettings: InstallationSettings)

/**
 * `orkx server info` — where orkx is pointed, who the server says we are, and what that
 * installation has switched on.
 *
 * Reports what is stored even when the server cannot be reached, because "which server did I
 * leave this pointed at" is exactly the question asked when nothing is answering. The exit
 * code still says the truth about reachability.
 */
@Command(
    name = "info",
    mixinStandardHelpOptions = true,
    description = ["Show which server orkx is talking to, and as whom."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ServerInfoCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String) -> SessionClient = { SessionClient(it) }
    internal var graphQlFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val stored = store.read() ?: throw ParameterException(
            spec.commandLine(),
            "No server chosen. Run 'orkx server use <url>', or 'orkx login' to do both.",
        )

        val facts = mutableListOf<Pair<String, String>>()
        facts += "Server" to style.name(stored.server)

        var code = ExitCode.OK
        val probe = try {
            clientFactory(stored.server).probe(stored.cookie)
        } catch (e: ServerUnreachable) {
            facts += "Reachable" to style.bad("no - ${e.message}")
            code = ExitCode.UNREACHABLE
            null
        }

        when (probe) {
            is Probe.SignedIn -> {
                val who = buildString {
                    append(style.name(probe.user.username))
                    probe.user.email?.let { append(" ").append(style.faint("<$it>")) }
                    if (probe.user.admin) append(" (administrator)")
                }
                facts += "Signed in" to who
                if (probe.user.roles.isNotEmpty()) {
                    facts += "Roles" to probe.user.roles.joinToString(", ")
                }
            }
            Probe.Unauthenticated -> {
                // Reachable, and does not know us: either never signed in, or the session went.
                facts += "Signed in" to style.faint("no - run 'orkx login'")
            }
            is Probe.NotOrknux -> {
                facts += "Reachable" to style.bad("not orknux-server: it ${probe.reason}")
                code = ExitCode.UNREACHABLE
            }
            null -> Unit
        }

        facts += "Workspace" to when (val id = stored.workspaceId) {
            null -> style.faint("none - run 'orkx workspace use <id>'")
            else -> "${stored.workspaceName ?: id} ${style.faint("(id $id)")}"
        }

        // Only worth asking once we know a request will be accepted.
        if (probe is Probe.SignedIn) {
            val cookie = stored.cookie
            if (cookie != null) {
                try {
                    val settings = graphQlFactory(stored.server, cookie).query(
                        SETTINGS_QUERY,
                        JsonObject(emptyMap()),
                        InstallationSettingsData.serializer(),
                    ).installationSettings
                    facts += "Chat" to onOff(style, settings.chatEnabled)
                    facts += "Attachments" to when {
                        settings.attachmentsEnabled ->
                            "${onOff(style, true)}, up to ${settings.attachmentMaxFileSizeMb} MB each"
                        else -> onOff(style, false)
                    }
                } catch (e: Exception) {
                    // Nothing here is worth failing the command over; the rest is still true.
                    facts += "Settings" to style.faint("could not be read: ${e.message}")
                }
            }
        }

        facts += "Session" to style.faint(store.file.toString())

        val width = facts.maxOf { it.first.length }
        facts.forEach { (label, value) -> out.println("${style.faint(label.padEnd(width))}  $value") }

        if (code != ExitCode.OK) err.println("The server could not be reached.")
        return code
    }

    private fun onOff(style: Style, enabled: Boolean): String =
        if (enabled) style.good("on") else style.faint("off")

    private companion object {
        const val SETTINGS_QUERY =
            "query Installation { installationSettings " +
                "{ chatEnabled attachmentsEnabled attachmentMaxFileSizeMb } }"
    }
}

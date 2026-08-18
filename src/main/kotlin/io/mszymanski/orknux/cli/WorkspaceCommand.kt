// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.Callable

/** The `workspace` group. Dispatches, like the root command. */
@Command(
    name = "workspace",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [WorkspaceListCommand::class, WorkspaceUseCommand::class],
    description = ["Choose which workspace the other commands work in."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkspaceCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * The workspace a command works in: the one `orkx workspace use` chose, or `--workspace` for a
 * single command. One place, so every workspace-scoped command behaves alike and says the same
 * thing when neither is available.
 */
internal fun resolveWorkspaceId(spec: CommandSpec, session: ActiveSession, override: String?): String {
    val chosen = override ?: session.workspaceId ?: throw ParameterException(
        spec.commandLine(),
        "No workspace chosen. Run 'orkx workspace use <id>', or pass --workspace.",
    )
    return serverIdOrNull(chosen) ?: throw ParameterException(
        spec.commandLine(),
        "'$chosen' is not a workspace id; those are numbers. 'orkx workspace list' has them.",
    )
}

/** The fields worth naming a workspace by. Not the whole type — the rest is nothing to a CLI. */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class WorkspaceData(val workspace: Workspace? = null)

/** The server's page wrapper. `totalElements` is what tells us whether to ask for more. */
@Serializable
data class WorkspacePage(
    val content: List<Workspace> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
data class WorkspacesData(val workspaces: WorkspacePage)

/**
 * `orkx workspace list` — the workspaces this user may see, and which one is in use.
 *
 * The list is already filtered by the server: a workspace names the directory group whose
 * members may see it, and an administrator sees all of them. So an empty list is a real
 * answer about someone's group membership, not a failure.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List the workspaces you can see."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkspaceListCommand : Callable<Int> {

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

        val workspaces = try {
            fetchAll(clientFactory(session.server, session.cookie))
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

        if (workspaces.isEmpty()) {
            // Not an error: nobody's groups granting them a workspace is a state to report.
            err.println("No workspaces that ${session.username} can see at ${session.server}.")
            return ExitCode.OK
        }

        printTable(out, style, workspaces, session.workspaceId)
        return ExitCode.OK
    }

    /**
     * Every page, not the first twenty. A `list` that quietly stops at the server's default
     * page size is a list that lies, and there is no plausible number of workspaces that
     * makes paging worth exposing here.
     */
    private fun fetchAll(client: GraphQlClient): List<Workspace> {
        val collected = mutableListOf<Workspace>()
        var page = 0
        while (true) {
            val result = client.query(
                WORKSPACES_QUERY,
                buildJsonObject { put("page", page); put("size", PAGE_SIZE) },
                WorkspacesData.serializer(),
            ).workspaces
            collected += result.content
            // Stop on a page that added nothing, so a disagreeing count cannot spin forever.
            if (result.content.isEmpty() || collected.size >= result.totalElements) return collected
            page++
        }
    }

    private fun printTable(out: PrintWriter, style: Style, workspaces: List<Workspace>, current: String?) {
        // The asterisk is a column with no heading, so it lines up like any other.
        val rows = workspaces.map { workspace ->
            val inUse = workspace.id == current
            listOf(
                if (inUse) style.marker("*") else "",
                workspace.id,
                if (inUse) style.name(workspace.name) else workspace.name,
                style.faint(workspace.description ?: ""),
            )
        }
        renderTable(listOf("", "ID", "NAME", "DESCRIPTION").map(style::heading), rows).forEach(out::println)

        if (current != null && workspaces.none { it.id == current }) {
            // Chosen once, and no longer visible: a group changed, or the workspace went.
            out.println()
            out.println(style.bad("The workspace in use (id $current) is not in this list any more."))
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val WORKSPACES_QUERY =
            "query Workspaces(\$page: Int, \$size: Int) { workspaces(page: \$page, size: \$size) " +
                "{ content { id name description } page size totalElements totalPages } }"
    }
}

/**
 * `orkx workspace use <id>` — remembers which workspace to work in.
 *
 * The server holds no notion of a current workspace; every workspace-scoped operation
 * takes an explicit id, so the choice is the client's to keep. The UI does the same thing
 * in local storage.
 *
 * The id is checked against the server rather than merely written down, so a typo is
 * caught here instead of by whatever runs next. That check is also the access check: the
 * server answers with the workspace only if the caller's directory groups grant it.
 */
@Command(
    name = "use",
    mixinStandardHelpOptions = true,
    description = ["Remember a workspace as the one to work in."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkspaceUseCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        paramLabel = "ID",
        description = ["The workspace's id, as the server knows it."],
    )
    var id: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = id.trim()
        if (wanted.isEmpty()) {
            throw ParameterException(spec.commandLine(), "The workspace id cannot be empty.")
        }
        serverIdOrNull(wanted) ?: throw ParameterException(
            spec.commandLine(),
            "'$wanted' is not a workspace id; those are numbers. 'orkx workspace list' has them.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val workspace = try {
            clientFactory(session.server, session.cookie)
                .query(WORKSPACE_QUERY, buildJsonObject { put("id", wanted) }, WorkspaceData.serializer())
                .workspace
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

        if (workspace == null) {
            // The server cannot tell these apart either: a workspace nobody may see and a
            // workspace that is not there both come back as null, so neither may be claimed.
            err.println("No workspace $wanted at ${session.server} that ${session.username} can see.")
            err.println("'orkx workspace list' shows the ones there are.")
            return ExitCode.NOT_FOUND
        }

        try {
            store.write(
                StoredSession(
                    server = session.server,
                    username = session.username,
                    cookie = session.cookie,
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                ),
            )
        } catch (e: IOException) {
            err.println("Could not write ${store.file}: ${e.message}")
            return ExitCode.SOFTWARE
        }

        out.println("Now working in ${style.name(workspace.name)} (id ${workspace.id}).")
        return ExitCode.OK
    }

    private companion object {
        /** Only what a confirmation needs; asking for less is a smaller thing to keep in step. */
        const val WORKSPACE_QUERY = "query Workspace(\$id: ID!) { workspace(id: \$id) { id name description } }"
    }
}

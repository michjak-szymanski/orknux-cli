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
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

@Serializable
data class CreateCatalogData(val createVariableCatalog: VariableCatalog)

@Serializable
data class RenameCatalogData(val renameVariableCatalog: VariableCatalog)

@Serializable
data class DeleteCatalogData(val deleteVariableCatalog: Boolean = false)

/** The `variable catalog` group: the folders a workspace's variables live in. */
@Command(
    name = "catalog",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        VariableCatalogCreateCommand::class,
        VariableCatalogRenameCommand::class,
        VariableCatalogDeleteCommand::class,
    ],
    description = ["Make and manage the folders variables live in."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableCatalogCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/** `orkx variable catalog create --name <name>` — a new folder for variables. */
@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description = ["Create a catalog."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableCatalogCreateCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["-n", "--name"], paramLabel = "NAME", required = true, description = ["What to call it."])
    var name: String = ""

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to make it in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = name.trim()
        if (wanted.isEmpty()) throw ParameterException(spec.commandLine(), "A catalog needs a name.")

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)

        return try {
            val created = clientFactory(session.server, session.cookie).query(
                CREATE_CATALOG,
                buildJsonObject { put("workspaceId", workspaceId); put("name", wanted) },
                CreateCatalogData.serializer(),
            ).createVariableCatalog

            out.println("Created catalog ${style.name(created.name)}.")
            ExitCode.OK
        } catch (e: SessionExpired) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // The server refuses a name already in use, in its own words.
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private companion object {
        const val CREATE_CATALOG =
            "mutation CreateVariableCatalog(\$workspaceId: ID!, \$name: String!) " +
                "{ createVariableCatalog(workspaceId: \$workspaceId, name: \$name) { id name } }"
    }
}

/** `orkx variable catalog rename --name <name> --new-name <new>` — the same folder, another name. */
@Command(
    name = "rename",
    mixinStandardHelpOptions = true,
    description = ["Rename a catalog."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableCatalogRenameCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["-n", "--name"], paramLabel = "NAME", required = true, description = ["The catalog to rename."])
    var name: String = ""

    @Option(
        names = ["--new-name"],
        paramLabel = "NAME",
        required = true,
        description = ["What to call it instead."],
    )
    var newName: String = ""

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to look in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val from = name.trim()
        val to = newName.trim()
        if (from.isEmpty() || to.isEmpty()) {
            throw ParameterException(spec.commandLine(), "A catalog needs a name, and a new one.")
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)

        return try {
            val catalog = Variables(client, workspaceId).catalog(from) ?: run {
                err.println("No catalog called '$from' in workspace $workspaceId.")
                return ExitCode.NOT_FOUND
            }

            val renamed = client.query(
                RENAME_CATALOG,
                buildJsonObject { put("id", catalog.id); put("name", to) },
                RenameCatalogData.serializer(),
            ).renameVariableCatalog

            out.println("Renamed ${style.faint(from)} to ${style.name(renamed.name)}.")
            ExitCode.OK
        } catch (e: SessionExpired) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private companion object {
        const val RENAME_CATALOG =
            "mutation RenameVariableCatalog(\$id: ID!, \$name: String!) " +
                "{ renameVariableCatalog(id: \$id, name: \$name) { id name } }"
    }
}

/**
 * `orkx variable catalog delete --name <name>` — removes an empty folder.
 *
 * No confirmation, and deliberately: the server removes only an empty catalog and refuses one
 * that still holds anything, so there is no losing a variable this way. What is left to lose is
 * an empty folder, which is remade by typing the name again.
 */
@Command(
    name = "delete",
    mixinStandardHelpOptions = true,
    description = ["Delete an empty catalog."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableCatalogDeleteCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["-n", "--name"], paramLabel = "NAME", required = true, description = ["The catalog to delete."])
    var name: String = ""

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to look in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = name.trim()
        if (wanted.isEmpty()) throw ParameterException(spec.commandLine(), "Which catalog?")

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)

        return try {
            val catalog = Variables(client, workspaceId).catalog(wanted) ?: run {
                err.println("No catalog called '$wanted' in workspace $workspaceId.")
                return ExitCode.NOT_FOUND
            }

            val deleted = client.query(
                DELETE_CATALOG,
                buildJsonObject { put("id", catalog.id) },
                DeleteCatalogData.serializer(),
            ).deleteVariableCatalog

            if (deleted) {
                out.println("Deleted catalog ${style.name(catalog.name)}.")
                ExitCode.OK
            } else {
                // It was there a moment ago, when its id was looked up.
                err.println("The server no longer has a catalog ${catalog.id}.")
                ExitCode.NOT_FOUND
            }
        } catch (e: SessionExpired) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // "…still holds 3 variables" arrives here, which is the whole guard.
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private companion object {
        const val DELETE_CATALOG =
            "mutation DeleteVariableCatalog(\$id: ID!) { deleteVariableCatalog(id: \$id) }"
    }
}

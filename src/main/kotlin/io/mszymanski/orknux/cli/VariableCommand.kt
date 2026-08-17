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
import java.io.PrintWriter
import java.util.concurrent.Callable

/**
 * The `variable` group: the named values a workspace keeps and hands to its functions.
 *
 * `var` is the same command, because this is one people will type often.
 */
@Command(
    name = "variable",
    aliases = ["var"],
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        VariableListCommand::class,
        VariableGetCommand::class,
        VariableSetCommand::class,
        VariableDeleteCommand::class,
        VariableCatalogCommand::class,
    ],
    description = ["Read and write a workspace's variables."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/** A folder of variables. */
@Serializable
data class VariableCatalog(val id: String, val name: String)

@Serializable
data class VariableCatalogsData(val variableCatalogs: List<VariableCatalog> = emptyList())

/**
 * A variable as the server describes it. `value` holds what a `VALUE` contains and is always
 * null for a `SECRET` — a secret only ever comes back through `revealVariable`, which records
 * that somebody asked.
 */
@Serializable
data class Variable(
    val id: String,
    val name: String,
    val catalogId: String = "",
    val catalogName: String = "",
    val description: String? = null,
    val type: String = "STRING",
    val kind: String = "SECRET",
    val value: String? = null,
    val valueSet: Boolean = false,
)

@Serializable
data class VariablePage(val content: List<Variable> = emptyList(), val totalElements: Int = 0)

@Serializable
data class VariablesPageData(val workspaceVariables: VariablePage)

@Serializable
data class VariablesData(val workspaceVariables: VariablePage)

@Serializable
data class CreateVariableData(val createVariable: Variable)

@Serializable
data class UpdateVariableData(val updateVariable: Variable)

@Serializable
data class RevealVariableData(val revealVariable: String? = null)

/**
 * `--type` in the words this CLI was asked for.
 *
 * The server calls this a variable's *kind*, and keeps `type` for what it holds — STRING,
 * NUMBER or BOOLEAN. Both are encrypted at rest; what differs is whether the value comes back
 * with a listing or only when somebody asks for it.
 */
enum class VariableVisibility {
    /** Read with the list: a channel name, a threshold, a URL. */
    VALUE,

    /** Shown only on request, and the request is recorded. */
    SECRET,
}

/** What both commands need to find their way to one variable. */
internal class Variables(private val client: GraphQlClient, private val workspaceId: String) {

    fun catalog(name: String): VariableCatalog? = catalogs().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun catalogs(): List<VariableCatalog> = client.query(
        CATALOGS_QUERY,
        buildJsonObject { put("workspaceId", workspaceId) },
        VariableCatalogsData.serializer(),
    ).variableCatalogs

    /**
     * One variable by name within a catalog. `search` is the server's filter and matches
     * loosely, so the exact name is picked out of what it returns rather than trusted from it.
     */
    fun byName(catalogId: String, name: String): Variable? = client.query(
        VARIABLES_QUERY,
        buildJsonObject {
            put("workspaceId", workspaceId)
            put("catalogId", catalogId)
            put("search", name)
        },
        VariablesData.serializer(),
    ).workspaceVariables.content.firstOrNull { it.name == name }

    /** Every page of them: a settings list that stops at the server's default page is a lie. */
    fun all(catalogId: String?): List<Variable> {
        val collected = mutableListOf<Variable>()
        var page = 0
        while (true) {
            val result = client.query(
                ALL_VARIABLES_QUERY,
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    catalogId?.let { put("catalogId", it) }
                    put("page", page)
                },
                VariablesPageData.serializer(),
            ).workspaceVariables
            collected += result.content
            if (result.content.isEmpty() || collected.size >= result.totalElements) return collected
            page++
        }
    }

    private companion object {
        const val CATALOGS_QUERY =
            "query VariableCatalogs(\$workspaceId: ID!) " +
                "{ variableCatalogs(workspaceId: \$workspaceId) { id name } }"

        const val VARIABLES_QUERY =
            "query WorkspaceVariables(\$workspaceId: ID!, \$catalogId: ID, \$search: String) " +
                "{ workspaceVariables(workspaceId: \$workspaceId, catalogId: \$catalogId, " +
                "search: \$search, page: 0, size: 100) " +
                "{ content { $VARIABLE_FIELDS } } }"

        const val ALL_VARIABLES_QUERY =
            "query WorkspaceVariables(\$workspaceId: ID!, \$catalogId: ID, \$page: Int) " +
                "{ workspaceVariables(workspaceId: \$workspaceId, catalogId: \$catalogId, " +
                "page: \$page, size: 100) " +
                "{ content { $VARIABLE_FIELDS } totalElements } }"
    }
}

internal const val VARIABLE_FIELDS = "id name catalogId catalogName description type kind value valueSet"

/**
 * `orkx variable list` — the workspace's variables, or one catalog's.
 *
 * Every page of them: variables are configuration rather than a feed, and a list of settings
 * that stopped at twenty would be a list that lies about what is configured.
 *
 * What a `VALUE` holds is shown, because that is what a value is for. A `SECRET` says only
 * whether anything is stored — `orkx variable get` is how one is read, and the server records
 * that it was.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List the workspace's variables."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableListCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-c", "--catalog"],
        paramLabel = "NAME",
        description = ["Only this catalog's. Every catalog's when left out."],
    )
    var catalog: String? = null

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

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)
        val variables = Variables(client, workspaceId)

        return try {
            val only = catalog?.trim()?.takeIf { it.isNotEmpty() }
            val folder = only?.let {
                variables.catalog(it) ?: run {
                    err.println("No catalog called '$it' in workspace $workspaceId.")
                    return ExitCode.NOT_FOUND
                }
            }

            val found = variables.all(folder?.id)
            if (found.isEmpty()) {
                err.println(
                    when (folder) {
                        null -> "No variables in workspace $workspaceId."
                        else -> "Nothing in ${folder.name}."
                    },
                )
                return ExitCode.OK
            }

            print(out, style, found, showCatalog = folder == null)
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

    private fun print(out: PrintWriter, style: Style, found: List<Variable>, showCatalog: Boolean) {
        val headings = mutableListOf<String>()
        if (showCatalog) headings += "CATALOG"
        headings += listOf("NAME", "TYPE", "HOLDS", "DESCRIPTION")

        val rows = found.map { variable ->
            val row = mutableListOf<String>()
            if (showCatalog) row += variable.catalogName
            row += variable.name
            row += variable.kind.lowercase()
            row += holds(style, variable)
            row += style.faint(variable.description.orEmpty())
            row
        }
        renderTable(headings.map(style::heading), rows).forEach(out::println)
    }

    /** A value shows what it holds; a secret says only whether it holds anything. */
    private fun holds(style: Style, variable: Variable): String = when {
        !variable.valueSet -> style.faint("not set")
        variable.value != null -> variable.value
        else -> style.faint("set")
    }
}

/**
 * `orkx variable set` — writes a variable, whether or not it was there before.
 *
 * The server has no upsert: creating and updating are separate mutations, so this looks for
 * the name in the catalog and does whichever applies. What it did is what it prints.
 *
 * A new variable is created as a STRING, which is what the server's own form defaults to
 * offering; an existing one keeps whatever it holds, since the update leaves the type alone.
 *
 * The catalog must already exist. Making one on a name that does not match is how a typo
 * becomes a second catalog nobody meant to have, so the ones there are get listed instead.
 */
@Command(
    name = "set",
    mixinStandardHelpOptions = true,
    description = ["Set a variable's value, creating it if it is not there."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableSetCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["-n", "--name"], paramLabel = "NAME", required = true, description = ["What it is called."])
    var name: String = ""

    @Option(
        names = ["-v", "--value"],
        paramLabel = "VALUE",
        description = [
            "What it should hold. On a shared machine this is in your shell history and in " +
                "the process list; --value-stdin keeps it out of both.",
        ],
    )
    var value: String? = null

    @Option(names = ["--value-stdin"], description = ["Read the value from standard input."])
    var valueStdin: Boolean = false

    @Option(
        names = ["-t", "--type"],
        paramLabel = "TYPE",
        description = [
            "value, read with the list; or secret, shown only when asked and the asking " +
                "recorded. Defaults to secret for a new variable, and leaves an existing " +
                "one as it is.",
        ],
    )
    var type: VariableVisibility? = null

    @Option(names = ["-c", "--catalog"], paramLabel = "NAME", required = true, description = ["Which catalog holds it."])
    var catalog: String = ""

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to write in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null
    internal var readLine: () -> String? = { readStandardInputLine() }

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = name.trim()
        if (wanted.isEmpty()) throw ParameterException(spec.commandLine(), "A variable needs a name.")
        val holds = resolveValue()

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)
        val variables = Variables(client, workspaceId)

        return try {
            val folder = variables.catalog(catalog.trim())
            if (folder == null) {
                err.println("No catalog called '${catalog.trim()}' in workspace $workspaceId.")
                val existing = variables.catalogs()
                if (existing.isEmpty()) {
                    err.println("That workspace has no catalogs yet.")
                } else {
                    err.println("There is: ${existing.joinToString(", ") { it.name }}.")
                }
                return ExitCode.NOT_FOUND
            }

            val existing = variables.byName(folder.id, wanted)
            val saved = if (existing == null) create(client, workspaceId, folder, wanted, holds) else {
                update(client, existing, holds)
            }
            val what = if (existing == null) "Created" else "Updated"
            out.println("$what ${style.name("${saved.catalogName}/${saved.name}")} (${saved.kind.lowercase()}).")
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

    private fun create(
        client: GraphQlClient,
        workspaceId: String,
        folder: VariableCatalog,
        wanted: String,
        holds: String,
    ): Variable = client.query(
        CREATE_VARIABLE,
        buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("catalogId", folder.id)
                    put("name", wanted)
                    // The server's own form offers STRING; NUMBER and BOOLEAN are set there.
                    put("type", "STRING")
                    put("kind", (type ?: VariableVisibility.SECRET).name)
                    put("value", holds)
                },
            )
        },
        CreateVariableData.serializer(),
    ).createVariable

    private fun update(client: GraphQlClient, existing: Variable, holds: String): Variable = client.query(
        UPDATE_VARIABLE,
        buildJsonObject {
            put("id", existing.id)
            put(
                "input",
                buildJsonObject {
                    put("value", holds)
                    // Left out entirely when not asked for, so the update leaves it alone.
                    type?.let { put("kind", it.name) }
                },
            )
        },
        UpdateVariableData.serializer(),
    ).updateVariable

    private fun resolveValue(): String {
        if (valueStdin) {
            if (value != null) {
                throw ParameterException(spec.commandLine(), "Use either --value or --value-stdin, not both.")
            }
            return readLine() ?: throw ParameterException(
                spec.commandLine(),
                "--value-stdin was given but nothing was piped in.",
            )
        }
        return value ?: throw ParameterException(
            spec.commandLine(),
            "A value is needed: pass --value, or --value-stdin to keep it out of your shell history.",
        )
    }

    private companion object {
        const val CREATE_VARIABLE = "mutation CreateVariable(\$input: CreateVariableInput!) " +
            "{ createVariable(input: \$input) { $VARIABLE_FIELDS } }"
        const val UPDATE_VARIABLE = "mutation UpdateVariable(\$id: ID!, \$input: UpdateVariableInput!) " +
            "{ updateVariable(id: \$id, input: \$input) { $VARIABLE_FIELDS } }"
    }
}

/**
 * Which variable, said either way: `billing/apiKey`, or `--catalog billing --name apiKey`. One
 * is a shorthand for the other, so giving both is an instruction to be read twice rather than a
 * second source of truth, and is refused.
 */
internal fun namedVariable(
    spec: CommandSpec,
    path: String?,
    catalog: String?,
    name: String?,
): Pair<String, String> {
    val shorthand = path?.trim()?.takeIf { it.isNotEmpty() }
    val folderFlag = catalog?.trim()?.takeIf { it.isNotEmpty() }
    val nameFlag = name?.trim()?.takeIf { it.isNotEmpty() }

    if (shorthand != null && (folderFlag != null || nameFlag != null)) {
        throw ParameterException(
            spec.commandLine(),
            "Name it once: either catalog/name, or --catalog and --name.",
        )
    }
    if (shorthand != null) {
        val folder = shorthand.substringBefore('/', "")
        val variable = shorthand.substringAfter('/', "")
        if (folder.isEmpty() || variable.isEmpty()) {
            throw ParameterException(spec.commandLine(), "'$shorthand' is not catalog/name.")
        }
        return folder to variable
    }
    val folder = folderFlag ?: throw ParameterException(
        spec.commandLine(),
        "Which variable? Give catalog/name, or --catalog and --name.",
    )
    return folder to (nameFlag ?: throw ParameterException(spec.commandLine(), "--name is needed as well."))
}

/**
 * `orkx variable get` — what one variable holds.
 *
 * Two ways of naming it, because one is quick to type and the other reads well in a script:
 * `orkx var get billing/apiKey`, or `--catalog billing --name apiKey`.
 *
 * The value goes to standard output on its own, so `KEY=$(orkx var get billing/apiKey)` is
 * the whole of it. Everything else this has to say goes to standard error.
 *
 * A secret is fetched through `revealVariable`, which is the only way one comes back and
 * which the server records. That is the point of asking rather than a side effect of it.
 */
@Command(
    name = "get",
    mixinStandardHelpOptions = true,
    description = ["Print what a variable holds."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableGetCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "CATALOG/NAME",
        description = ["The variable, as catalog/name. The same as --catalog and --name."],
    )
    var path: String? = null

    @Option(names = ["-c", "--catalog"], paramLabel = "NAME", description = ["Which catalog holds it."])
    var catalog: String? = null

    @Option(names = ["-n", "--name"], paramLabel = "NAME", description = ["What it is called."])
    var name: String? = null

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

        val (catalogName, variableName) = namedVariable(spec, path, catalog, name)

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)
        val variables = Variables(client, workspaceId)

        return try {
            val folder = variables.catalog(catalogName)
            if (folder == null) {
                err.println("No catalog called '$catalogName' in workspace $workspaceId.")
                return ExitCode.NOT_FOUND
            }
            val variable = variables.byName(folder.id, variableName)
            if (variable == null) {
                err.println("No variable called '$variableName' in ${folder.name}.")
                return ExitCode.NOT_FOUND
            }

            if (!variable.valueSet) {
                err.println("${folder.name}/${variable.name} holds nothing.")
                return ExitCode.OK
            }

            // A value comes back with the listing; a secret only through the reveal, which
            // the server records against whoever asked.
            val holds = variable.value ?: client.query(
                REVEAL_VARIABLE,
                buildJsonObject { put("id", variable.id) },
                RevealVariableData.serializer(),
            ).revealVariable

            if (holds == null) {
                err.println("The server would not say what ${folder.name}/${variable.name} holds.")
                return ExitCode.NOT_FOUND
            }

            out.println(holds)
            if (variable.kind.equals("SECRET", ignoreCase = true)) {
                err.println(style.faint("Reading ${folder.name}/${variable.name} was recorded."))
            }
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
        const val REVEAL_VARIABLE = "mutation RevealVariable(\$id: ID!) { revealVariable(id: \$id) }"
    }
}

@Serializable
data class DeleteVariableData(val deleteVariable: Boolean = false)

/**
 * `orkx variable delete` — removes a variable and what it holds.
 *
 * Asks first. A value can be typed again; a secret is gone, because the server is the only
 * place it was and nothing reads one back except a reveal. With nothing attached to answer,
 * `--yes` has to be given.
 *
 * Without this a catalog could be made and filled from the CLI but never emptied, which left
 * `variable catalog delete` unreachable in practice: the server only removes an empty one.
 */
@Command(
    name = "delete",
    mixinStandardHelpOptions = true,
    description = ["Delete a variable and its value."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class VariableDeleteCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "CATALOG/NAME",
        description = ["The variable, as catalog/name. The same as --catalog and --name."],
    )
    var path: String? = null

    @Option(names = ["-c", "--catalog"], paramLabel = "NAME", description = ["Which catalog holds it."])
    var catalog: String? = null

    @Option(names = ["-n", "--name"], paramLabel = "NAME", description = ["What it is called."])
    var name: String? = null

    @Option(names = ["-y", "--yes"], description = ["Do not ask first."])
    var yes: Boolean = false

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to look in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null
    internal var interactive: Boolean = attachedToTerminal()
    internal var readLine: () -> String? = { readStandardInputLine() }

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val (catalogName, variableName) = namedVariable(spec, path, catalog, name)

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)
        val variables = Variables(client, workspaceId)

        return try {
            val folder = variables.catalog(catalogName) ?: run {
                err.println("No catalog called '$catalogName' in workspace $workspaceId.")
                return ExitCode.NOT_FOUND
            }
            val variable = variables.byName(folder.id, variableName) ?: run {
                err.println("No variable called '$variableName' in ${folder.name}.")
                return ExitCode.NOT_FOUND
            }

            if (!yes) {
                if (!interactive) {
                    err.println(
                        "Deleting ${folder.name}/${variable.name} takes its value with it. " +
                            "Pass --yes to say you meant to.",
                    )
                    return ExitCode.USAGE
                }
                // A secret is worth naming as one: it is the case that cannot be typed again.
                val what = if (variable.kind.equals("SECRET", ignoreCase = true)) "secret" else "value"
                out.print("Delete the $what ${folder.name}/${variable.name}? [y/N] ")
                out.flush()
                val answer = readLine()?.trim()?.lowercase()
                if (answer != "y" && answer != "yes") {
                    out.println("${folder.name}/${variable.name} was left alone.")
                    return ExitCode.OK
                }
            }

            val deleted = client.query(
                DELETE_VARIABLE,
                buildJsonObject { put("id", variable.id) },
                DeleteVariableData.serializer(),
            ).deleteVariable

            if (deleted) {
                out.println("Deleted ${style.name("${folder.name}/${variable.name}")}.")
                ExitCode.OK
            } else {
                // It was there a moment ago, when its id was looked up.
                err.println("The server no longer has a variable ${variable.id}.")
                ExitCode.NOT_FOUND
            }
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
        const val DELETE_VARIABLE = "mutation DeleteVariable(\$id: ID!) { deleteVariable(id: \$id) }"
    }
}

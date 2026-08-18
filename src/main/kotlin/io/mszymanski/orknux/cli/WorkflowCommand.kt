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
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.util.concurrent.Callable

/**
 * A workflow as one workspace has it.
 *
 * Two ids, and they are not interchangeable: `id` identifies the *assignment* to this
 * workspace, `workflowId` the definition that runs. `startExecution` takes the second.
 */
@Serializable
data class WorkspaceWorkflow(
    val id: String,
    val workflowId: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val lastRun: LastRun? = null,
    /** When a scheduled trigger will start it next; null when nothing schedules it. */
    val nextRun: String? = null,
)

/** Where a workflow last got to. Null on one that has never run. */
@Serializable
data class LastRun(
    val executionId: String = "",
    val status: String = "",
    val startedAt: String = "",
    val durationSeconds: Int? = null,
)

@Serializable
data class WorkspaceWorkflowPage(
    val content: List<WorkspaceWorkflow> = emptyList(),
    val totalElements: Int = 0,
)

@Serializable
data class WorkspaceWorkflowsData(val workspaceWorkflows: WorkspaceWorkflowPage)

@Serializable
data class StartExecutionData(val startExecution: ExecutionDetail)

/** Every page: a workflow missing from the list is a workflow that cannot be named. */
internal fun fetchWorkflows(client: GraphQlClient, workspaceId: String): List<WorkspaceWorkflow> {
    val collected = mutableListOf<WorkspaceWorkflow>()
    var page = 0
    while (true) {
        val result = client.query(
            WORKFLOWS_QUERY,
            buildJsonObject { put("workspaceId", workspaceId); put("page", page) },
            WorkspaceWorkflowsData.serializer(),
        ).workspaceWorkflows
        collected += result.content
        if (result.content.isEmpty() || collected.size >= result.totalElements) return collected
        page++
    }
}

private const val WORKFLOWS_QUERY =
    "query WorkspaceWorkflows(\$workspaceId: ID!, \$page: Int) " +
        "{ workspaceWorkflows(workspaceId: \$workspaceId, page: \$page, size: 100) " +
        "{ content { id workflowId name description enabled lastRun { executionId status startedAt } nextRun } " +
        "totalElements } }"

/** The `workflow` group. Dispatches, like the root command. */
@Command(
    name = "workflow",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [WorkflowListCommand::class, WorkflowRunCommand::class],
    description = ["Run a workspace's workflows."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkflowCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * `orkx workflow list` — the workspace's workflows, and where each last got to.
 *
 * The id shown is the definition's, which is the one `orkx workflow run` takes and the one
 * `orkx execution list` reports against a run. A workflow's assignment id is not shown: two
 * numbers in one table, only one of which starts anything, is how the wrong one gets used.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List the workspace's workflows."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkflowListCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

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

        val workflows = try {
            fetchWorkflows(clientFactory(session.server, session.cookie), workspaceId)
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

        if (workflows.isEmpty()) {
            err.println("No workflows in workspace $workspaceId.")
            return ExitCode.OK
        }

        val rows = workflows.map { workflow ->
            listOf(
                workflow.workflowId,
                workflow.name,
                if (workflow.enabled) "" else style.faint("disabled"),
                workflow.lastRun?.let { style.status(it.status) } ?: style.faint("never run"),
                workflow.lastRun?.startedAt?.let { style.faint(formatTimestamp(it)) } ?: "",
                workflow.nextRun?.let { style.faint(formatTimestamp(it)) } ?: "",
                style.faint(workflow.description.orEmpty()),
            )
        }
        renderTable(
            listOf("ID", "NAME", "", "LAST RUN", "WHEN", "NEXT RUN", "DESCRIPTION").map(style::heading),
            rows,
        ).forEach(out::println)
        return ExitCode.OK
    }
}

/**
 * `orkx workflow run <workflow>` — starts one by hand.
 *
 * Named by its name, or by either of its ids. The listing is fetched either way, and what is
 * sent is always the definition's `workflowId` — a workflow's assignment id sits in the same
 * list and looks exactly like it, so taking the number on trust is how the wrong workflow
 * gets run. When one number matches two different workflows, that is said rather than guessed.
 *
 * Recorded as a `MANUAL` run, which is what it is, and the audit says a person started it.
 *
 * This starts work: whatever the workflow does, it does. There is no prompt, because naming a
 * workflow on the command line is already the decision — but what started is printed.
 */
@Command(
    name = "run",
    mixinStandardHelpOptions = true,
    description = ["Start a workflow by hand."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class WorkflowRunCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(
        index = "0",
        paramLabel = "WORKFLOW",
        description = ["Which one: its name, or its id."],
    )
    var workflow: String = ""

    @Option(
        names = ["-i", "--input"],
        paramLabel = "JSON",
        description = [
            "What the first node is handed, as JSON. A trigger supplies this from whatever " +
                "fired; leaving it out hands the run nothing.",
        ],
    )
    var input: String? = null

    @Option(names = ["--input-stdin"], description = ["Read that input from standard input."])
    var inputStdin: Boolean = false

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to run in. Defaults to the one from 'orkx workspace use'."],
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

        val wanted = workflow.trim()
        if (wanted.isEmpty()) throw ParameterException(spec.commandLine(), "Which workflow?")
        val payload = resolveInput()

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)

        return try {
            val available = fetchWorkflows(client, workspaceId)
            val matches = available.filter { it.matches(wanted) }
            when {
                matches.isEmpty() -> {
                    err.println("No workflow called '$wanted' in workspace $workspaceId.")
                    if (available.isNotEmpty()) {
                        err.println("There is: ${available.joinToString(", ") { it.name }}.")
                    }
                    return ExitCode.NOT_FOUND
                }
                // One number can be one workflow's definition and another's assignment.
                matches.size > 1 -> {
                    err.println(
                        "'$wanted' matches ${matches.size} workflows: " +
                            matches.joinToString(", ") { "${it.name} (id ${it.workflowId})" } + ".",
                    )
                    return ExitCode.USAGE
                }
            }
            val chosen = matches.single()
            if (!chosen.enabled) {
                // Not a refusal: the server runs it, and running a disabled workflow by hand is
                // how one is tested. Worth saying, because nothing else will.
                err.println("${chosen.name} is disabled; running it by hand anyway.")
            }

            val started = client.query(
                START_EXECUTION,
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    // The definition, never the assignment.
                    put("workflowId", chosen.workflowId)
                    payload?.let { put("input", it) }
                },
                StartExecutionData.serializer(),
            ).startExecution

            out.println("Started run ${style.name(started.id)} of ${style.name(started.workflowName)}.")
            out.println("  ${style.faint("Status ")}  ${style.status(started.status)}")
            out.println("  ${style.faint("Trigger")}  ${started.trigger}")
            started.temporalUrl?.let { out.println("  ${style.faint("Temporal")} ${style.faint(it)}") }
            out.println()
            out.println(style.faint("Follow it with 'orkx execution get ${started.id}'."))
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

    private fun resolveInput(): String? {
        if (inputStdin) {
            if (input != null) {
                throw ParameterException(spec.commandLine(), "Use either --input or --input-stdin, not both.")
            }
            return readLine() ?: throw ParameterException(
                spec.commandLine(),
                "--input-stdin was given but nothing was piped in.",
            )
        }
        // Passed through as it came: the server hands it to the first node, and what that
        // node will take is the workflow's business rather than this command's.
        return input
    }

    private companion object {
        const val START_EXECUTION =
            "mutation StartExecution(\$workspaceId: ID!, \$workflowId: ID!, \$input: String) " +
                "{ startExecution(workspaceId: \$workspaceId, workflowId: \$workflowId, input: \$input) " +
                "{ id workspaceId workflowId workflowName status trigger startedAt temporalUrl } }"
    }
}

/** By name, or by either id — the same number can name two different workflows. */
private fun WorkspaceWorkflow.matches(wanted: String): Boolean =
    name.equals(wanted, ignoreCase = true) || workflowId == wanted || id == wanted

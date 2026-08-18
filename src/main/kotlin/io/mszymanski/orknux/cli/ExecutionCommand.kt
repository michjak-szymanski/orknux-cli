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
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.PrintWriter
import java.util.concurrent.Callable

/** The `execution` group: what the workflows did. Dispatches, like the root command. */
@Command(
    name = "execution",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        ExecutionListCommand::class,
        ExecutionGetCommand::class,
        ExecutionLogsCommand::class,
        ExecutionRestartCommand::class,
    ],
    description = ["Look at workflow runs."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ExecutionCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * One run, as a list shows it. The status and trigger are kept as strings rather than
 * enums: the server may learn a new one, and a CLI that cannot print `CANCELLED` because it
 * has not been recompiled is worse than one that prints a word it has not seen before.
 */
@Serializable
data class Execution(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val status: String,
    val trigger: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val durationSeconds: Int? = null,
    val stoppedReason: String? = null,
)

@Serializable
data class ExecutionPage(
    val content: List<Execution> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Int = 0,
    val totalPages: Int = 0,
)

@Serializable
data class ExecutionsData(val workspaceExecutions: ExecutionPage)

@Serializable
data class ExecutionStep(
    val key: String,
    val kind: String,
    val name: String,
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val durationSeconds: Int? = null,
    val error: String? = null,
)

/** The graph's edges and the run's log are in this type too; a terminal wants neither. */
@Serializable
data class ExecutionDetail(
    val id: String,
    val workspaceId: String,
    val workflowId: String,
    val workflowName: String,
    val status: String,
    val trigger: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val durationSeconds: Int? = null,
    val stoppedAtNodeKey: String? = null,
    val stoppedReason: String? = null,
    val error: String? = null,
    val steps: List<ExecutionStep> = emptyList(),
    val temporalUrl: String? = null,
)

@Serializable
data class ExecutionData(val execution: ExecutionDetail? = null)

/** `rerunExecution` returns the run it started, and is declared non-null. */
@Serializable
data class RerunData(val rerunExecution: ExecutionDetail)

/**
 * `orkx execution list` — a workspace's runs, newest first.
 *
 * Workspace-scoped, because the server's query is: it takes `workspaceId: ID!`. The stored
 * choice from `orkx workspace use` supplies it, and `--workspace` looks elsewhere for one
 * command without disturbing that choice.
 *
 * Unlike workspaces, runs accumulate without limit, so this does not fetch every page. It
 * takes the newest `--limit` of them and says how many there are in total — a list that
 * silently stops reads as though it were all of them.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List a workspace's workflow runs, newest first."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ExecutionListCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to look in. Defaults to the one from 'orkx workspace use'."],
    )
    var workspace: String? = null

    @Option(
        names = ["-n", "--limit"],
        paramLabel = "COUNT",
        description = ["How many of the newest runs to show. Defaults to $DEFAULT_LIMIT."],
    )
    var limit: Int = DEFAULT_LIMIT

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        if (limit < 1) {
            throw ParameterException(spec.commandLine(), "--limit must be at least 1.")
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspace(session)

        val page = try {
            clientFactory(session.server, session.cookie).query(
                EXECUTIONS_QUERY,
                buildJsonObject { put("workspaceId", workspaceId); put("size", limit) },
                ExecutionsData.serializer(),
            ).workspaceExecutions
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

        if (page.content.isEmpty()) {
            err.println("No runs yet in workspace $workspaceId.")
            return ExitCode.OK
        }

        renderTable(
            listOf("ID", "WORKFLOW", "STATUS", "TRIGGER", "STARTED", "DURATION")
                .map(style::heading),
            page.content.map { execution ->
                listOf(
                    execution.id,
                    execution.workflowName,
                    style.status(execution.status),
                    execution.trigger,
                    style.faint(formatTimestamp(execution.startedAt)),
                    formatDuration(execution.durationSeconds),
                )
            },
        ).forEach(out::println)

        // Never let a cap pass for the whole truth.
        if (page.totalElements > page.content.size) {
            out.println()
            out.println(
                style.faint("Showing ${page.content.size} of ${page.totalElements}. Ask for more with --limit."),
            )
        }
        return ExitCode.OK
    }

    private fun resolveWorkspace(session: ActiveSession): String {
        val chosen = workspace ?: session.workspaceId ?: throw ParameterException(
            spec.commandLine(),
            "No workspace chosen. Run 'orkx workspace use <id>', or pass --workspace.",
        )
        return serverIdOrNull(chosen) ?: throw ParameterException(
            spec.commandLine(),
            "'$chosen' is not a workspace id; those are numbers. 'orkx workspace list' has them.",
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val EXECUTIONS_QUERY =
            "query Executions(\$workspaceId: ID!, \$size: Int) " +
                "{ workspaceExecutions(workspaceId: \$workspaceId, page: 0, size: \$size) " +
                "{ content { id workflowId workflowName status trigger startedAt finishedAt " +
                "durationSeconds stoppedReason } page size totalElements totalPages } }"
    }
}

/**
 * `orkx execution get <id>` — what one run did.
 *
 * Not workspace-scoped: the server resolves the run's own workspace and checks access
 * against that. Which also means access failure is visible here, unlike `workspace use` —
 * a run belonging to a workspace the caller may not see is `FORBIDDEN`, while a run that
 * does not exist is null. The two are reported as the different things they are.
 */
@Command(
    name = "get",
    mixinStandardHelpOptions = true,
    description = ["Show one workflow run and its steps."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ExecutionGetCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The run's id, as 'orkx execution list' shows it."])
    var id: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = serverIdOrNull(id) ?: throw ParameterException(
            spec.commandLine(),
            "'${id.trim()}' is not a run id; those are numbers. 'orkx execution list' has them.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val execution = try {
            clientFactory(session.server, session.cookie).query(
                EXECUTION_QUERY,
                buildJsonObject { put("id", wanted) },
                ExecutionData.serializer(),
            ).execution
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

        if (execution == null) {
            err.println("No run $wanted at ${session.server}.")
            return ExitCode.NOT_FOUND
        }

        print(out, style, execution)
        return ExitCode.OK
    }

    private fun print(out: PrintWriter, style: Style, execution: ExecutionDetail) {
        out.println("Run ${style.name(execution.id)}")
        val facts = buildList {
            add("Workflow" to "${execution.workflowName} ${style.faint("(id ${execution.workflowId})")}")
            add("Workspace" to execution.workspaceId)
            add("Status" to style.status(execution.status))
            add("Trigger" to execution.trigger)
            add("Started" to formatTimestamp(execution.startedAt))
            execution.finishedAt?.let { add("Finished" to formatTimestamp(it)) }
            add("Duration" to formatDuration(execution.durationSeconds))
            execution.stoppedAtNodeKey?.let { add("Stopped at" to it) }
            execution.stoppedReason?.let { add("Stopped" to it) }
            execution.error?.let { add("Error" to style.bad(it)) }
            execution.temporalUrl?.let { add("Temporal" to style.faint(it)) }
        }
        val labelWidth = facts.maxOf { it.first.length }
        facts.forEach { (label, value) -> out.println("  ${style.faint(label.padEnd(labelWidth))}  $value") }

        if (execution.steps.isEmpty()) {
            out.println()
            out.println(style.faint("No steps recorded."))
            return
        }

        out.println()
        out.println(style.heading("Steps"))
        val rows = execution.steps.map { step ->
            listOf(
                style.status(step.status),
                style.faint(step.kind),
                step.name,
                formatDuration(step.durationSeconds),
            )
        }
        renderTable(listOf("STATUS", "KIND", "NAME", "DURATION").map(style::heading), rows)
            .forEach { out.println("  $it") }

        // A step's error is the thing the person came for, so it is not left in a column.
        execution.steps.filter { it.error != null }.forEach { step ->
            out.println()
            out.println("  ${step.name} ${style.bad("failed")}:")
            step.error!!.trim().lines().forEach { out.println("    ${style.bad(it)}") }
        }
    }

    private companion object {
        const val EXECUTION_QUERY =
            "query Execution(\$id: ID!) { execution(id: \$id) " +
                "{ id workspaceId workflowId workflowName status trigger startedAt finishedAt " +
                "durationSeconds stoppedAtNodeKey stoppedReason error temporalUrl " +
                "steps { key kind name status startedAt finishedAt durationSeconds error } } }"
    }
}

/**
 * `orkx execution restart <id>` — runs a workflow again, against what the original run was
 * given.
 *
 * This starts something. The server carries the old payload over deliberately, so the new
 * run acts on the same event as the original: if that workflow answered somebody, it
 * answers them again. There is no prompt, because naming one run on the command line is
 * already the decision — but the output says what started, so nothing is ambiguous.
 *
 * The new run is not the old one: it gets a fresh id and is recorded as MANUAL, whatever
 * fired the original.
 */
@Command(
    name = "restart",
    mixinStandardHelpOptions = true,
    description = ["Run a workflow again, with the input the original run was given."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ExecutionRestartCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The run to restart, as 'orkx execution list' shows it."])
    var id: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = serverIdOrNull(id) ?: throw ParameterException(
            spec.commandLine(),
            "'${id.trim()}' is not a run id; those are numbers. 'orkx execution list' has them.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val started = try {
            clientFactory(session.server, session.cookie).query(
                RERUN_MUTATION,
                buildJsonObject { put("id", wanted) },
                RerunData.serializer(),
            ).rerunExecution
        } catch (e: SessionExpired) {
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // A run that is not there is refused rather than answered null: the mutation
            // returns a non-null type, so the server throws instead.
            err.println(e.message)
            return ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        // Leads with the new run's id: nothing about run $wanted has changed, and the thing
        // the caller now has to follow is a different run.
        out.println("Started run ${style.name(started.id)}, a restart of $wanted.")
        out.println("  ${style.faint("Workflow")}  ${started.workflowName} ${style.faint("(id ${started.workflowId})")}")
        out.println("  ${style.faint("Status  ")}  ${style.status(started.status)}")
        out.println("  ${style.faint("Trigger ")}  ${started.trigger}")
        started.temporalUrl?.let { out.println("  ${style.faint("Temporal")}  ${style.faint(it)}") }
        out.println()
        out.println(style.faint("Follow it with 'orkx execution get ${started.id}'."))
        return ExitCode.OK
    }

    private companion object {
        /** Asks for no steps: a run that has just started has nothing to show yet. */
        const val RERUN_MUTATION =
            "mutation Rerun(\$id: ID!) { rerunExecution(id: \$id) " +
                "{ id workspaceId workflowId workflowName status trigger startedAt temporalUrl } }"
    }
}

/** A line of a run's log. `nodeKey` matches an [ExecutionStep]; null means the run itself. */
@Serializable
data class ExecutionLogLine(
    val id: String,
    val nodeKey: String? = null,
    val at: String,
    val level: String,
    val message: String,
)

/** Only what names a step, because that is all the log needs from one. */
@Serializable
data class StepName(val key: String, val name: String)

/** The run, as `logs` asks for it: no graph, no per-step input or output. */
@Serializable
data class ExecutionLogs(
    val id: String,
    val workflowName: String,
    val status: String,
    val steps: List<StepName> = emptyList(),
    val logs: List<ExecutionLogLine> = emptyList(),
)

@Serializable
data class LogsData(val execution: ExecutionLogs? = null)

/**
 * `orkx execution logs <id>` — what the run wrote as it went.
 *
 * Every line, not a page of them: the server hands them over in one list, and a log the
 * client trimmed is a log that hides the interesting part.
 *
 * Deliberately not a table. A message can be long, or several lines of one, so the columns
 * before it are padded and the message runs to the edge — with its continuations indented
 * to line up under the first, which is the only way a stack trace stays readable next to
 * everything else.
 */
@Command(
    name = "logs",
    mixinStandardHelpOptions = true,
    description = ["Show what a run logged, oldest first."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ExecutionLogsCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The run whose log to show."])
    var id: String = ""

    @Option(
        names = ["-s", "--step"],
        paramLabel = "NAME",
        description = ["Only lines from steps whose name or key contains this."],
    )
    var step: String? = null

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = serverIdOrNull(id) ?: throw ParameterException(
            spec.commandLine(),
            "'${id.trim()}' is not a run id; those are numbers. 'orkx execution list' has them.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        val execution = try {
            clientFactory(session.server, session.cookie).query(
                LOGS_QUERY,
                buildJsonObject { put("id", wanted) },
                LogsData.serializer(),
            ).execution
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

        if (execution == null) {
            err.println("No run $wanted at ${session.server}.")
            return ExitCode.NOT_FOUND
        }

        // A step's key is what a log line carries; its name is what a person recognises.
        val names = execution.steps.associate { it.key to it.name }
        val lines = execution.logs.filter { matches(it, names) }

        if (lines.isEmpty()) {
            err.println(
                when {
                    execution.logs.isEmpty() -> "Run $wanted logged nothing."
                    else -> "Nothing in run $wanted's log came from a step matching '$step'."
                },
            )
            return ExitCode.OK
        }

        out.println(
            "Run ${style.name(execution.id)}  ${execution.workflowName}  ${style.status(execution.status)}",
        )
        out.println()
        printLog(out, style, lines, names)
        return ExitCode.OK
    }

    private fun matches(line: ExecutionLogLine, names: Map<String, String>): Boolean {
        val filter = step?.takeIf { it.isNotBlank() } ?: return true
        val key = line.nodeKey ?: return false
        return key.contains(filter, ignoreCase = true) ||
            names[key]?.contains(filter, ignoreCase = true) == true
    }

    private fun printLog(
        out: PrintWriter,
        style: Style,
        lines: List<ExecutionLogLine>,
        names: Map<String, String>,
    ) {
        fun nodeOf(line: ExecutionLogLine) = line.nodeKey?.let { names[it] ?: it } ?: RUN_ITSELF

        val timeWidth = lines.maxOf { formatTimestamp(it.at).length }
        val levelWidth = lines.maxOf { it.level.length }
        val nodeWidth = lines.maxOf { nodeOf(it).length }
        val continuation = " ".repeat(timeWidth + levelWidth + nodeWidth + GUTTERS)

        for (line in lines) {
            val at = style.faint(formatTimestamp(line.at).padEnd(timeWidth))
            val level = style.level(line.level) + " ".repeat(levelWidth - line.level.length)
            val node = style.faint(nodeOf(line).padEnd(nodeWidth))
            val message = line.message.trimEnd().lines()

            out.println("$at  $level  $node  ${message.firstOrNull().orEmpty()}".trimEnd())
            message.drop(1).forEach { out.println("$continuation$it".trimEnd()) }
        }
    }

    private companion object {
        /** Stands where a step's name would go, for a line about the run rather than a step. */
        const val RUN_ITSELF = "run"

        /** The three two-space gaps between the four columns. */
        const val GUTTERS = 6

        const val LOGS_QUERY =
            "query ExecutionLogs(\$id: ID!) { execution(id: \$id) " +
                "{ id workflowName status steps { key name } " +
                "logs { id nodeKey at level message } } }"
    }
}

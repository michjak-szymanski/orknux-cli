package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec
import java.io.PrintWriter
import java.util.concurrent.Callable

/** The `chat` group. Dispatches, like the root command. */
@Command(
    name = "chat",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [
        ChatListCommand::class,
        ChatSearchCommand::class,
        ChatOpenCommand::class,
        ChatCreateCommand::class,
        ChatDeleteCommand::class,
        ChatConfigCommand::class,
    ],
    description = ["Talk to a workspace's models."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * A chat, as this command needs it. `modelName` goes null when the model it named is
 * removed, and `agentName` when the agent is deleted — so neither can be relied on to say
 * who answers.
 */
@Serializable
data class ChatSessionInfo(
    val id: String,
    val workspaceId: String,
    val title: String,
    /** Pinned chats come first in the server's own ordering, so a list can just say which. */
    val pinned: Boolean = false,
    val modelId: String? = null,
    val modelName: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val lastMessageAt: String? = null,
) {
    /** What to print in front of an answer. An agent answers as itself, a model as its name. */
    val responder: String get() = agentName ?: modelName ?: "assistant"
}

@Serializable
data class ChatSessionData(val chatSession: ChatSessionInfo? = null)

/** Role is user, assistant, system or tool. */
@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatMessagesData(val chatMessages: List<ChatMessage> = emptyList())

/**
 * `orkx chat open <id>` — says things to a chat and prints the answers as they arrive.
 *
 * The history is printed first and in the same shape as the live exchange, because the
 * server writes the whole answer down when the stream ends: a chat reopened tomorrow should
 * read exactly as it did while it was happening.
 *
 * Chats belong to one person. The server checks the workspace is visible *and* that the
 * chat is the caller's, and answers a chat belonging to somebody else as though it were not
 * there — so this cannot open a colleague's conversation, and does not pretend to know
 * which of the two happened.
 *
 * Reads standard input whether or not that is a terminal, so `echo "hello" | orkx chat open
 * 5` works for a script; the prompt is only printed when somebody is there to read it.
 */
@Command(
    name = "open",
    mixinStandardHelpOptions = true,
    description = ["Open an interactive chat session."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatOpenCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The chat's id."])
    var id: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var streamFactory: (String, String) -> ChatStreamClient = { url, cookie -> ChatStreamClient(url, cookie) }
    internal var styleOverride: Style? = null

    /** Whether to prompt. False under a pipe, where there is nobody to prompt. */
    internal var interactive: Boolean = attachedToTerminal()

    /**
     * One line of input, or null at the end of it. Replaced by the tests.
     *
     * The byte order mark goes here rather than in the loop, so that the loop sees a blank
     * line as blank — a BOM-only line is what `"" | orkx chat open 5` sends from PowerShell,
     * and it must not become a message.
     */
    internal var readLine: () -> String? = { readStandardInputLine() }

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val wanted = serverIdOrNull(id) ?: throw ParameterException(
            spec.commandLine(),
            "'${id.trim()}' is not a chat id; those are numbers.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val graphql = clientFactory(session.server, session.cookie)

        val chat = try {
            graphql.query(
                CHAT_QUERY,
                buildJsonObject { put("id", wanted) },
                ChatSessionData.serializer(),
            ).chatSession
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

        if (chat == null) {
            err.println("No chat $wanted at ${session.server} that ${session.username} can open.")
            return ExitCode.NOT_FOUND
        }

        val history = try {
            graphql.query(
                MESSAGES_QUERY,
                buildJsonObject { put("id", wanted) },
                ChatMessagesData.serializer(),
            ).chatMessages
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            return ExitCode.UNREACHABLE
        }

        printHeader(out, style, chat)
        history.forEach { printMessage(out, style, chat, it) }
        if (history.isNotEmpty()) out.println()

        return converse(out, err, style, chat, streamFactory(session.server, session.cookie))
    }

    private fun converse(
        out: PrintWriter,
        err: PrintWriter,
        style: Style,
        chat: ChatSessionInfo,
        stream: ChatStreamClient,
    ): Int {
        while (true) {
            if (interactive) {
                out.print(style.faint("$YOU> "))
                out.flush()
            }
            val typed = readLine()
            if (typed == null) {
                // End of input: Ctrl+D, or the pipe ran out. Not a failure.
                if (interactive) out.println()
                return ExitCode.OK
            }
            val text = typed.trim()
            if (text.isEmpty()) continue
            if (text in LEAVE) {
                return ExitCode.OK
            }
            // Under a pipe nothing echoed what was typed, so the exchange would read as
            // answers to nothing.
            if (!interactive) out.println(style.faint("$YOU> ") + text)

            when (val outcome = answer(out, err, style, chat, stream, text)) {
                ExitCode.OK -> Unit
                else -> return outcome
            }
        }
    }

    /** Prints one answer as it streams. Returns OK unless the failure ends the session. */
    private fun answer(
        out: PrintWriter,
        err: PrintWriter,
        style: Style,
        chat: ChatSessionInfo,
        stream: ChatStreamClient,
        text: String,
    ): Int {
        out.print(style.name("${chat.responder}> "))
        out.flush()

        var wroteSomething = false
        return try {
            stream.send(chat.id, text) { event ->
                when (event) {
                    is ChatEvent.Text -> {
                        out.print(event.text)
                        out.flush()
                        wroteSomething = wroteSomething || event.text.isNotEmpty()
                    }
                    is ChatEvent.Finished -> {
                        out.println()
                        out.println(style.faint("  (${formatMillis(event.millis)})"))
                    }
                    // The model could not answer this time. The chat is still open.
                    is ChatEvent.Failed -> {
                        if (wroteSomething) out.println()
                        out.println(style.bad(event.reason))
                    }
                }
            }
            out.flush()
            ExitCode.OK
        } catch (e: SessionExpired) {
            out.println()
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // Nothing about this improves by typing again: no model chosen, chat switched off.
            out.println()
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: NotFound) {
            out.println()
            err.println(e.message)
            ExitCode.NOT_FOUND
        } catch (e: ServerUnreachable) {
            out.println()
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private fun printHeader(out: PrintWriter, style: Style, chat: ChatSessionInfo) {
        val who = when {
            chat.agentName != null -> "agent ${chat.agentName}"
            chat.modelName != null -> chat.modelName
            // Both null: the model or agent it named is gone, and sending will be refused.
            else -> "nothing to answer with"
        }
        out.println("Chat ${style.name(chat.id)}  ${chat.title}  ${style.faint("($who)")}")
        if (interactive) {
            out.println(style.faint("Type /exit, or press Ctrl+D, to leave."))
        }
        out.println()
    }

    private fun printMessage(out: PrintWriter, style: Style, chat: ChatSessionInfo, message: ChatMessage) {
        val label = when (message.role.lowercase()) {
            "user" -> style.faint("$YOU> ")
            "assistant" -> style.name("${chat.responder}> ")
            else -> style.faint("${message.role}> ")
        }
        // Only the first line is labelled: prose wraps, and the next label marks the turn.
        out.println(label + message.content.trimEnd())
    }

    /** Seconds to one decimal, because a model's answer is measured in seconds. */
    private fun formatMillis(millis: Double): String = when {
        millis < 1000 -> "${millis.toLong()}ms"
        else -> String.format("%.1fs", millis / 1000)
    }

    private companion object {
        const val YOU = "you"
        val LEAVE = setOf("/exit", "/quit", "/q")

        const val CHAT_QUERY =
            "query Chat(\$id: ID!) { chatSession(id: \$id) " +
                "{ id workspaceId title modelId modelName agentId agentName lastMessageAt } }"

        const val MESSAGES_QUERY = "query ChatMessages(\$id: ID!) { chatMessages(id: \$id) { role content } }"
    }
}

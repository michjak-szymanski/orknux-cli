package io.mszymanski.orknux.cli

import kotlinx.serialization.KSerializer
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

/** A model or an agent: the two things a chat can be answered by. */
internal data class Recipient(val kind: Kind, val id: String, val name: String) {
    enum class Kind { MODEL, AGENT }
}

@Serializable
private data class NamedThing(val id: String, val name: String)

@Serializable
private data class ModelLookup(val model: NamedThing? = null)

@Serializable
private data class AgentLookup(val agent: NamedThing? = null)

/**
 * Works out whether an id names a model or an agent.
 *
 * They are separate catalogues with separate id sequences, so the same number is very likely
 * to be both — `model:9` and `agent:9` say which, and a bare number is resolved by asking.
 * When it turns out to be both, that is reported rather than guessed at.
 *
 * Two requests, not one document asking for both: `model(id)` and `agent(id)` answer null for
 * something that is not there but *throw* for something in a workspace the caller cannot see,
 * and one such throw would take the other's answer down with it.
 */
internal class Recipients(private val client: GraphQlClient) {

    sealed interface Resolution {
        data class One(val recipient: Recipient) : Resolution
        data class Both(val model: Recipient, val agent: Recipient) : Resolution
        object None : Resolution
    }

    fun resolve(raw: String): Resolution {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith(MODEL_PREFIX) -> found(model(trimmed.removePrefix(MODEL_PREFIX)))
            trimmed.startsWith(AGENT_PREFIX) -> found(agent(trimmed.removePrefix(AGENT_PREFIX)))
            else -> both(trimmed)
        }
    }

    private fun found(recipient: Recipient?): Resolution =
        recipient?.let(Resolution::One) ?: Resolution.None

    private fun both(id: String): Resolution {
        val model = model(id)
        val agent = agent(id)
        return when {
            model != null && agent != null -> Resolution.Both(model, agent)
            model != null -> Resolution.One(model)
            agent != null -> Resolution.One(agent)
            else -> Resolution.None
        }
    }

    private fun model(id: String): Recipient? =
        lookup(id, MODEL_QUERY, ModelLookup.serializer()) { it.model }
            ?.let { Recipient(Recipient.Kind.MODEL, it.id, it.name) }

    private fun agent(id: String): Recipient? =
        lookup(id, AGENT_QUERY, AgentLookup.serializer()) { it.agent }
            ?.let { Recipient(Recipient.Kind.AGENT, it.id, it.name) }

    private fun <T> lookup(
        id: String,
        document: String,
        serializer: KSerializer<T>,
        pick: (T) -> NamedThing?,
    ): NamedThing? {
        if (serverIdOrNull(id) == null) return null
        return try {
            pick(client.query(document, buildJsonObject { put("id", id) }, serializer))
        } catch (_: OperationRefused) {
            // Both resolvers refuse only for a workspace the caller cannot see. Something you
            // may not look at is not something you may be answered by, so: not this one.
            null
        }
    }

    private companion object {
        const val MODEL_PREFIX = "model:"
        const val AGENT_PREFIX = "agent:"
        const val MODEL_QUERY = "query Model(\$id: ID!) { model(id: \$id) { id name } }"
        const val AGENT_QUERY = "query Agent(\$id: ID!) { agent(id: \$id) { id name } }"
    }
}

/** The fields these mutations ask back, so every one of them prints the same thing. */
internal const val CHAT_FIELDS = "id workspaceId title modelId modelName agentId agentName"

@Serializable
data class StartChatData(val startChat: ChatSessionInfo)

@Serializable
data class ChooseAgentData(val chooseChatAgent: ChatSessionInfo)

@Serializable
data class ChooseModelData(val chooseChatModel: ChatSessionInfo)

@Serializable
data class RenameChatData(val renameChat: ChatSessionInfo)

@Serializable
data class DeleteChatData(val deleteChat: Boolean = false)

/** What every one of these commands prints once it has changed a chat. */
internal fun describeChat(style: Style, chat: ChatSessionInfo): String {
    val answeredBy = when {
        chat.agentName != null -> "agent ${style.name(chat.agentName)}"
        chat.modelName != null -> style.name(chat.modelName)
        else -> style.faint("nothing to answer with")
    }
    return "Chat ${style.name(chat.id)}  ${chat.title}  ($answeredBy)"
}

/** The message for an id that names one of each, shared by the two commands that take one. */
internal fun ambiguousRecipient(raw: String, both: Recipients.Resolution.Both): String =
    "'${raw.trim()}' is both a model and an agent: ${both.model.name} and ${both.agent.name}. " +
        "Say which with model:${both.model.id} or agent:${both.agent.id}."

/**
 * `orkx chat create` — starts a chat in the workspace in use.
 *
 * Both options may be left out: the server calls an unnamed chat "New chat" and gives one
 * with no recipient the workspace's first active model, which is what the picker would have
 * offered anyway.
 *
 * An agent is not something `startChat` takes, so an agent recipient is a second call. That
 * is the same thing the UI's picker does, and the server brings the agent's own model with
 * it — an agent answering on some other model would not be answering as what it says it is.
 */
@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description = ["Start a new chat."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatCreateCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-r", "--recipient"],
        paramLabel = "ID",
        description = [
            "What answers: a model id or an agent id. Prefix with model: or agent: when the " +
                "same number is both. Defaults to the workspace's first active model.",
        ],
    )
    var recipient: String? = null

    @Option(
        names = ["-n", "--name"],
        paramLabel = "NAME",
        description = ["What to call it. The server calls an unnamed chat \"New chat\"."],
    )
    var name: String? = null

    @Option(
        names = ["-w", "--workspace"],
        paramLabel = "ID",
        description = ["Workspace to start it in. Defaults to the one from 'orkx workspace use'."],
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

        return try {
            val chosen = recipient?.let {
                when (val resolution = Recipients(client).resolve(it)) {
                    is Recipients.Resolution.One -> resolution.recipient
                    is Recipients.Resolution.Both -> {
                        err.println(ambiguousRecipient(it, resolution))
                        return ExitCode.USAGE
                    }
                    Recipients.Resolution.None -> {
                        err.println("No model or agent with id '${it.trim()}' that ${session.username} can use.")
                        return ExitCode.NOT_FOUND
                    }
                }
            }

            val started = client.query(
                START_CHAT,
                buildJsonObject {
                    put(
                        "input",
                        buildJsonObject {
                            put("workspaceId", workspaceId)
                            name?.trim()?.takeIf(String::isNotEmpty)?.let { put("title", it) }
                            chosen?.takeIf { it.kind == Recipient.Kind.MODEL }?.let { put("modelId", it.id) }
                        },
                    )
                },
                StartChatData.serializer(),
            ).startChat

            val chat = if (chosen?.kind == Recipient.Kind.AGENT) {
                client.query(
                    CHOOSE_AGENT,
                    buildJsonObject { put("id", started.id); put("agentId", chosen.id) },
                    ChooseAgentData.serializer(),
                ).chooseChatAgent
            } else {
                started
            }

            out.println("Created ${describeChat(style, chat)}")
            out.println(style.faint("Open it with 'orkx chat open ${chat.id}'."))
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
        const val START_CHAT = "mutation StartChat(\$input: StartChatInput!) " +
            "{ startChat(input: \$input) { $CHAT_FIELDS } }"
        const val CHOOSE_AGENT = "mutation ChooseAgent(\$id: ID!, \$agentId: ID) " +
            "{ chooseChatAgent(id: \$id, agentId: \$agentId) { $CHAT_FIELDS } }"
    }
}

/**
 * `orkx chat delete <id>` — removes a chat and the history with it.
 *
 * Asks first, because the history goes too and there is no undoing it. With nothing attached
 * to answer the question, `--yes` has to be given: a script that deletes a conversation
 * should have to say that it meant to.
 */
@Command(
    name = "delete",
    mixinStandardHelpOptions = true,
    description = ["Delete a chat and its history."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatDeleteCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "ID", description = ["The chat to delete."])
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
            "'${id.trim()}' is not a chat id; those are numbers.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        if (!yes) {
            if (!interactive) {
                err.println("Deleting a chat takes its history with it. Pass --yes to say you meant to.")
                return ExitCode.USAGE
            }
            out.print("Delete chat $wanted and everything said in it? [y/N] ")
            out.flush()
            val answer = readLine()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") {
                out.println("Chat $wanted was not deleted.")
                return ExitCode.OK
            }
        }

        return try {
            val deleted = clientFactory(session.server, session.cookie).query(
                DELETE_CHAT,
                buildJsonObject { put("id", wanted) },
                DeleteChatData.serializer(),
            ).deleteChat

            if (deleted) {
                out.println("Deleted chat ${style.name(wanted)}.")
                ExitCode.OK
            } else {
                // The server answers false rather than failing when there is no such chat.
                err.println("No chat $wanted at ${session.server} to delete.")
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
        const val DELETE_CHAT = "mutation DeleteChat(\$id: ID!) { deleteChat(id: \$id) }"
    }
}

/** The `chat config` group: changing a chat rather than talking in it. */
@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = [ChatSetRecipientCommand::class, ChatSetNameCommand::class],
    description = ["Change a chat's settings."],
    commandListHeading = "%nCommands:%n",
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatConfigCommand : Runnable {

    @Spec
    lateinit var spec: CommandSpec

    override fun run() {
        throw ParameterException(spec.commandLine(), "Missing required subcommand")
    }
}

/**
 * `orkx chat config set-recipient` — changes what answers.
 *
 * One mutation either way, because the server keeps the pair straight itself: choosing a bare
 * model ends the agent's part in the chat, and choosing an agent brings the agent's own model
 * with it. Doing half of that here would be a second copy of a rule that already exists.
 */
@Command(
    name = "set-recipient",
    mixinStandardHelpOptions = true,
    description = ["Choose the model or agent that answers a chat."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatSetRecipientCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-c", "--chat-id"],
        paramLabel = "ID",
        required = true,
        description = ["The chat to change."],
    )
    var chatId: String = ""

    @Option(
        names = ["-r", "--recipient"],
        paramLabel = "ID",
        required = true,
        description = [
            "What should answer: a model id or an agent id. Prefix with model: or agent: " +
                "when the same number is both.",
        ],
    )
    var recipient: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val chat = serverIdOrNull(chatId) ?: throw ParameterException(
            spec.commandLine(),
            "'${chatId.trim()}' is not a chat id; those are numbers.",
        )

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val client = clientFactory(session.server, session.cookie)

        return try {
            val chosen = when (val resolution = Recipients(client).resolve(recipient)) {
                is Recipients.Resolution.One -> resolution.recipient
                is Recipients.Resolution.Both -> {
                    err.println(ambiguousRecipient(recipient, resolution))
                    return ExitCode.USAGE
                }
                Recipients.Resolution.None -> {
                    err.println("No model or agent with id '${recipient.trim()}' that ${session.username} can use.")
                    return ExitCode.NOT_FOUND
                }
            }

            val updated = when (chosen.kind) {
                Recipient.Kind.MODEL -> client.query(
                    CHOOSE_MODEL,
                    buildJsonObject { put("id", chat); put("modelId", chosen.id) },
                    ChooseModelData.serializer(),
                ).chooseChatModel
                Recipient.Kind.AGENT -> client.query(
                    CHOOSE_AGENT,
                    buildJsonObject { put("id", chat); put("agentId", chosen.id) },
                    ChooseAgentData.serializer(),
                ).chooseChatAgent
            }

            out.println(describeChat(style, updated))
            ExitCode.OK
        } catch (e: SessionExpired) {
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: OperationRefused) {
            // "That agent belongs to another workspace", "… is not active", "… has no model".
            err.println(e.message)
            ExitCode.REJECTED
        } catch (e: ServerUnreachable) {
            err.println(e.message)
            ExitCode.UNREACHABLE
        }
    }

    private companion object {
        const val CHOOSE_MODEL = "mutation ChooseModel(\$id: ID!, \$modelId: ID) " +
            "{ chooseChatModel(id: \$id, modelId: \$modelId) { $CHAT_FIELDS } }"
        const val CHOOSE_AGENT = "mutation ChooseAgent(\$id: ID!, \$agentId: ID) " +
            "{ chooseChatAgent(id: \$id, agentId: \$agentId) { $CHAT_FIELDS } }"
    }
}

/** `orkx chat config set-name` — renames a chat. */
@Command(
    name = "set-name",
    mixinStandardHelpOptions = true,
    description = ["Rename a chat."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatSetNameCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-c", "--chat-id"],
        paramLabel = "ID",
        required = true,
        description = ["The chat to rename."],
    )
    var chatId: String = ""

    @Option(
        names = ["-n", "--name"],
        paramLabel = "NAME",
        required = true,
        description = ["What to call it."],
    )
    var name: String = ""

    internal var store: SessionStore = SessionStore.default()
    internal var clientFactory: (String, String) -> GraphQlClient = { url, cookie -> GraphQlClient(url, cookie) }
    internal var styleOverride: Style? = null

    override fun call(): Int {
        val out = spec.commandLine().out
        val err = spec.commandLine().err
        val style = styleOverride ?: styleFor(spec)

        val chat = serverIdOrNull(chatId) ?: throw ParameterException(
            spec.commandLine(),
            "'${chatId.trim()}' is not a chat id; those are numbers.",
        )
        val title = name.trim()
        if (title.isEmpty()) {
            // The server says the same thing; saying it here saves a round trip.
            throw ParameterException(spec.commandLine(), "A chat needs a name.")
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }

        return try {
            val renamed = clientFactory(session.server, session.cookie).query(
                RENAME_CHAT,
                buildJsonObject { put("id", chat); put("title", title) },
                RenameChatData.serializer(),
            ).renameChat

            out.println(describeChat(style, renamed))
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
        const val RENAME_CHAT = "mutation RenameChat(\$id: ID!, \$title: String!) " +
            "{ renameChat(id: \$id, title: \$title) { $CHAT_FIELDS } }"
    }
}

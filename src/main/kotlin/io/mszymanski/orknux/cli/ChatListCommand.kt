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
import java.io.PrintWriter
import java.util.concurrent.Callable

@Serializable
data class ChatSessionsData(val chatSessions: List<ChatSessionInfo> = emptyList())

/** `chatsMentioning` answers with ids and nothing else; the titles come from the list. */
@Serializable
data class ChatsMentioningData(val chatsMentioning: List<String> = emptyList())

/** Everything both commands need from the server, and the same query for both. */
internal const val CHAT_LIST_QUERY =
    "query ChatSessions(\$workspaceId: ID!) { chatSessions(workspaceId: \$workspaceId) " +
        "{ id workspaceId title pinned modelId modelName agentId agentName lastMessageAt } }"

/**
 * One row per chat, in the order the server gave them — pinned first, then most recent. The
 * `why` column is only there for a search, which is the only case with anything to say.
 */
internal fun renderChats(
    out: PrintWriter,
    style: Style,
    chats: List<ChatSessionInfo>,
    why: Map<String, String> = emptyMap(),
) {
    val headings = mutableListOf("", "ID", "TITLE", "ANSWERED BY", "LAST MESSAGE")
    if (why.isNotEmpty()) headings += "MATCHED"

    val rows = chats.map { chat ->
        val row = mutableListOf(
            // Pinned, which is why it is at the top.
            if (chat.pinned) style.marker("*") else "",
            chat.id,
            chat.title,
            when {
                chat.agentName != null -> "agent ${chat.agentName}"
                chat.modelName != null -> chat.modelName
                else -> style.faint("nothing")
            },
            style.faint(chat.lastMessageAt?.let(::formatTimestamp) ?: "never"),
        )
        if (why.isNotEmpty()) row += style.faint(why[chat.id].orEmpty())
        row
    }
    renderTable(headings.map(style::heading), rows).forEach(out::println)
}

/**
 * `orkx chat list` — the caller's chats in a workspace.
 *
 * Only ever the caller's: the server answers this one for whoever is asking, so there is no
 * seeing a colleague's conversations and no flag that would let you try.
 *
 * Not paged, because the server does not page it — a person's chats in one workspace are a
 * list, not a feed.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List your chats, pinned first. * marks a pinned one."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatListCommand : Callable<Int> {

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

        val chats = try {
            clientFactory(session.server, session.cookie).query(
                CHAT_LIST_QUERY,
                buildJsonObject { put("workspaceId", workspaceId) },
                ChatSessionsData.serializer(),
            ).chatSessions
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

        if (chats.isEmpty()) {
            // Having no chats is a state, not a failure.
            err.println("No chats of ${session.username}'s in workspace $workspaceId.")
            return ExitCode.OK
        }

        renderChats(out, style, chats)
        return ExitCode.OK
    }
}

/**
 * `orkx chat search <text>` — finds a chat by what it is called, and with `--messages` by what
 * was said in it.
 *
 * Two different questions, which is why the second is asked for. Titles are matched here,
 * over the list this already has; searching everything ever said is the server's `chatsMentioning`
 * and a good deal more work, so it happens when asked and not before. The UI draws the same
 * line, with the deeper search behind a switch.
 *
 * The results are unioned, in the server's order, and the `MATCHED` column says which of the
 * two found each one.
 */
@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Find a chat by name, or by what was said in it."],
    optionListHeading = "%nOptions:%n",
    sortOptions = false,
)
class ChatSearchCommand : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "TEXT", description = ["What to look for."])
    var text: String = ""

    @Option(
        names = ["-m", "--messages"],
        description = ["Also look inside what was said, not only at the names."],
    )
    var messages: Boolean = false

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

        val needle = text.trim()
        if (needle.isEmpty()) {
            throw ParameterException(spec.commandLine(), "There is nothing to search for.")
        }

        val session = store.read().active() ?: run {
            err.println("Not signed in. Run 'orkx login' first.")
            return ExitCode.REJECTED
        }
        val workspaceId = resolveWorkspaceId(spec, session, workspace)
        val client = clientFactory(session.server, session.cookie)

        val chats: List<ChatSessionInfo>
        val said: Set<String>
        try {
            chats = client.query(
                CHAT_LIST_QUERY,
                buildJsonObject { put("workspaceId", workspaceId) },
                ChatSessionsData.serializer(),
            ).chatSessions
            said = if (messages) {
                client.query(
                    MENTIONING_QUERY,
                    buildJsonObject { put("workspaceId", workspaceId); put("text", needle) },
                    ChatsMentioningData.serializer(),
                ).chatsMentioning.toSet()
            } else {
                emptySet()
            }
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

        val why = mutableMapOf<String, String>()
        val found = chats.filter { chat ->
            val byName = chat.title.contains(needle, ignoreCase = true)
            val bySaying = chat.id in said
            if (byName || bySaying) {
                why[chat.id] = listOfNotNull("name".takeIf { byName }, "said".takeIf { bySaying }).joinToString(", ")
            }
            byName || bySaying
        }

        if (found.isEmpty()) {
            // Finding nothing is an answer. The exit code stays 0, as it does for any other
            // list that is legitimately empty.
            err.println(
                buildString {
                    append("No chat in workspace $workspaceId is called anything like '$needle'")
                    if (messages) append(", nor said it") else append(". Try --messages to look inside them")
                    append(".")
                },
            )
            return ExitCode.OK
        }

        // Only worth a column when there were two ways to match.
        renderChats(out, style, found, if (messages) why else emptyMap())
        return ExitCode.OK
    }

    private companion object {
        const val MENTIONING_QUERY =
            "query ChatsMentioning(\$workspaceId: ID!, \$text: String!) " +
                "{ chatsMentioning(workspaceId: \$workspaceId, text: \$text) }"
    }
}

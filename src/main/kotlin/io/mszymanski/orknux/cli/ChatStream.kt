package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** The one part of a chat that is not GraphQL, because an answer arrives over seconds. */
private const val CHAT_STREAM_PATH = "/api/chats"

@Serializable
private data class SendMessage(val text: String, val attachmentIds: List<Long> = emptyList())

@Serializable
private data class Chunk(val text: String = "")

@Serializable
private data class Done(val millis: Double = 0.0)

@Serializable
private data class Failure(val reason: String = "")

/**
 * Spring's error body. `detail` is the part written for whoever called — "This chat has no
 * model to answer with; choose one first" — so it is the part worth showing.
 */
@Serializable
private data class ProblemDetail(
    val detail: String? = null,
    val title: String? = null,
    val status: Int? = null,
)

/** The server had no such thing. Distinct from a refusal, so the exit code can be. */
class NotFound(message: String) : Exception(message)

/**
 * What arrives while a chat answers. The server sends three kinds and nothing else.
 */
sealed interface ChatEvent {

    /** A piece of the answer. Several arrive for a model, exactly one for an agent. */
    data class Text(val text: String) : ChatEvent

    /** The answer is complete, and took this long. */
    data class Finished(val millis: Double) : ChatEvent

    /**
     * The model could not answer. A normal thing inside a working chat — no credentials, a
     * refusal, a tool that would not run — so it ends the answer, not the conversation.
     */
    data class Failed(val reason: String) : ChatEvent
}

/**
 * Says something in a chat and reports the answer as it lands.
 *
 * Server-sent events over a POST. `BodyHandlers.ofLines` hands lines over as they arrive,
 * which is the whole point: an answer composed over half a minute has to appear while it is
 * being composed, not afterwards.
 *
 * No timeout on the request. A large local model can think for minutes, and a client that
 * gives up on a working answer is worse than one that waits — Ctrl+C is the way out.
 */
class ChatStreamClient(
    private val baseUrl: String,
    private val cookie: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {

    fun send(chatId: String, text: String, onEvent: (ChatEvent) -> Unit) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$CHAT_STREAM_PATH/$chatId/stream"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Cookie", cookie)
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(SendMessage(text))))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofLines())
        } catch (e: HttpTimeoutException) {
            throw ServerUnreachable("The server at $baseUrl stopped answering.", e)
        } catch (e: IOException) {
            throw ServerUnreachable("Cannot reach the server at $baseUrl: ${e.message ?: e::class.simpleName}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ServerUnreachable("Interrupted while waiting for the server at $baseUrl.", e)
        }

        when (val status = response.statusCode()) {
            200 -> Unit
            401 -> throw SessionExpired(
                "Your session at $baseUrl has expired. Run 'orkx login' to start another.",
            )
            404 -> throw NotFound(problem(response) ?: "That chat is not there any more.")
            // Everything the server calls a bad request here is the caller's to put right:
            // no model chosen, an unusable agent, nothing typed, chat switched off.
            400 -> throw OperationRefused(problem(response) ?: "The chat would not take that.")
            else -> throw ServerUnreachable("The server at $baseUrl answered $status to a chat message.")
        }

        response.body().use { lines -> parse(lines.iterator(), onEvent) }
    }

    /**
     * Server-sent events: `event:` names it, `data:` carries it, a blank line ends it.
     * Several `data:` lines are joined with newlines, as the format says — this server sends
     * one, but a parser that only works for one server is a parser that breaks quietly.
     */
    private fun parse(lines: Iterator<String>, onEvent: (ChatEvent) -> Unit) {
        var event: String? = null
        val data = StringBuilder()

        fun dispatch() {
            val name = event
            if (name != null && data.isNotEmpty()) {
                decode(name, data.toString())?.let(onEvent)
            }
            event = null
            data.setLength(0)
        }

        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                // A comment, a retry hint, an id: nothing this client needs.
                else -> Unit
            }
        }
        // A stream that ends without its final blank line still said something.
        dispatch()
    }

    private fun decode(event: String, data: String): ChatEvent? = try {
        when (event) {
            "chunk" -> ChatEvent.Text(json.decodeFromString<Chunk>(data).text)
            "done" -> ChatEvent.Finished(json.decodeFromString<Done>(data).millis)
            "error" -> ChatEvent.Failed(json.decodeFromString<Failure>(data).reason)
            else -> null
        }
    } catch (_: Exception) {
        // One unreadable frame is not worth ending an answer over.
        null
    }

    private fun problem(response: HttpResponse<out java.util.stream.Stream<String>>): String? {
        val body = response.body().use { it.toList().joinToString("\n") }
        return try {
            json.decodeFromString<ProblemDetail>(body).let { it.detail ?: it.title }
        } catch (_: Exception) {
            body.takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

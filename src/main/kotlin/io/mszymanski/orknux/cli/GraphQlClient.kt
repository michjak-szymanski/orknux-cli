// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Everything but sign-in lives here. Spring's default path, which the server does not override. */
const val GRAPHQL_PATH = "/graphql"

@Serializable
private data class GraphQlRequest(val query: String, val variables: JsonObject)

@Serializable
private data class GraphQlEnvelope<T>(
    val data: T? = null,
    val errors: List<GraphQlError> = emptyList(),
)

@Serializable
data class GraphQlError(val message: String)

/**
 * The server answered 200 and said no in the body — a GraphQL failure is not an HTTP one.
 * `FORBIDDEN` arrives this way, as does anything a resolver refused.
 */
class OperationRefused(message: String) : Exception(message)

/** The session is gone: expired, or thrown away by a server restart. Only a fresh login fixes it. */
class SessionExpired(message: String) : Exception(message)

/**
 * Talks GraphQL to one orknux-server as one signed-in user.
 *
 * The serializer is passed in rather than found by reflection, which is what keeps this
 * working in the native image without any metadata of its own.
 */
class GraphQlClient(
    private val baseUrl: String,
    private val cookie: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {

    fun <T> query(document: String, variables: JsonObject, data: KSerializer<T>): T {
        val body = json.encodeToString(GraphQlRequest(document, variables))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$GRAPHQL_PATH"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Cookie", cookie)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = send(request)
        when (response.statusCode()) {
            200 -> Unit
            401 -> throw SessionExpired(
                "Your session at $baseUrl has expired. Run 'orkx login' to start another.",
            )
            else -> throw ServerUnreachable(
                "The server at $baseUrl answered ${response.statusCode()} to a GraphQL request.",
            )
        }

        val envelope = try {
            json.decodeFromString(GraphQlEnvelope.serializer(data), response.body())
        } catch (e: Exception) {
            throw ServerUnreachable("The server at $baseUrl answered something unreadable: ${e.message}", e)
        }

        envelope.errors.firstOrNull()?.let { throw OperationRefused(explain(it.message)) }
        return envelope.data
            ?: throw ServerUnreachable("The server at $baseUrl answered with neither data nor an error.")
    }

    /**
     * The server's own words, and one sentence of ours where they lead nowhere.
     *
     * `INTERNAL_ERROR for 43d0fe06-…` is a correlation id and nothing else: it says the server
     * broke, not what broke, and the reader is left grepping a log. `orkx admin doctor` exists
     * to answer exactly that, so it is offered — without claiming to know the cause, because
     * this does not.
     */
    private fun explain(message: String): String = when {
        message.startsWith("INTERNAL_ERROR") ->
            "$message\nThe server logged the reason under that id. 'orkx admin doctor' may say why."
        else -> message
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: HttpTimeoutException) {
        throw ServerUnreachable("The server at $baseUrl did not answer in ${REQUEST_TIMEOUT.toSeconds()}s.", e)
    } catch (e: IOException) {
        throw ServerUnreachable("Cannot reach the server at $baseUrl: ${e.message ?: e::class.simpleName}", e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ServerUnreachable("Interrupted while waiting for the server at $baseUrl.", e)
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

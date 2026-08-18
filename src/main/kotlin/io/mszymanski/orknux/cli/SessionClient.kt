// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

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

/** The server's login endpoint. Mirrors `LOGIN_PATH` in orknux-server's `SecurityConfig`. */
const val SESSION_PATH = "/api/session"

/**
 * `POST /api/session` — the server checks these against the directory over LDAP.
 *
 * Sent as JSON rather than as form fields, matching the server's `LoginRequest`.
 */
@Serializable
private data class Credentials(val username: String, val password: String)

/**
 * What the server reports about whoever just signed in. `admin` says the caller holds
 * the server's configured administrator role, which is what decides whether they see
 * every workspace or only the ones their directory groups grant.
 *
 * Fields default so that a server which grows a field does not break an older `orkx`.
 */
@Serializable
data class SessionUser(
    val username: String,
    val roles: List<String> = emptyList(),
    val admin: Boolean = false,
    val email: String? = null,
)

/** A successful sign-in: who the server says you are, and the cookie that proves it. */
data class SignedIn(val user: SessionUser, val cookie: String)

/**
 * What `GET /api/session` says about an address. Three answers worth telling apart: it is
 * orknux-server and knows us, it is orknux-server and does not, or it is something else
 * entirely — which is what a mistyped host name looks like.
 */
sealed interface Probe {
    data class SignedIn(val user: SessionUser) : Probe
    object Unauthenticated : Probe

    /** [reason] completes the sentence "it …", so the caller need not know what went wrong. */
    data class NotOrknux(val reason: String) : Probe
}

/** The server answered, and said no. Nothing worth retrying without new credentials. */
class CredentialsRejected(message: String) : Exception(message)

/** Nothing usable at that address: connection refused, DNS failure, timeout, or a body we cannot read. */
class ServerUnreachable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to one orknux-server. Auth there is a session cookie, not a bearer token —
 * there is no refresh endpoint and no expiry to inspect, so the only thing to keep is
 * the cookie, and the only way to renew it is to sign in again.
 */
class SessionClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttpClient(),
) {

    /**
     * Signs in and returns the session. The password is taken as a [CharArray] so the
     * caller can clear it; serialising it does put a copy on the heap, which is as good
     * as the JVM allows.
     */
    fun login(username: String, password: CharArray): SignedIn {
        val body = json.encodeToString(Credentials(username, String(password)))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$SESSION_PATH"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = send(request)
        when (response.statusCode()) {
            200 -> Unit
            401 -> throw CredentialsRejected("Invalid username or password.")
            else -> throw ServerUnreachable(
                "The server at $baseUrl answered ${response.statusCode()} to the sign-in request.",
            )
        }

        val cookie = cookiesFrom(response)
            ?: throw ServerUnreachable(
                "The server at $baseUrl accepted the credentials but set no cookie to sign in with.",
            )
        return SignedIn(user = decode(response.body()), cookie = cookie)
    }

    /**
     * Asks the server who it thinks we are. With no cookie this only establishes that
     * orknux-server is answering there, which is the point of it: a 401 from this endpoint is
     * a working server, while anything else is not one.
     */
    fun probe(cookie: String? = null): Probe {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$SESSION_PATH"))
            .header("Accept", "application/json")
            .apply { cookie?.let { header("Cookie", it) } }
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()

        val response = send(request)
        return when (response.statusCode()) {
            // A 200 carrying a web page rather than a session is the commonest way to point
            // this at the wrong port, and it is not worth a parser error at the terminal.
            200 -> runCatching { Probe.SignedIn(decode(response.body())) }
                .getOrElse { Probe.NotOrknux("answered $SESSION_PATH with something that is not a session") }
            401 -> Probe.Unauthenticated
            else -> Probe.NotOrknux("answered $SESSION_PATH with ${response.statusCode()}")
        }
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

    private fun decode(body: String): SessionUser = try {
        json.decodeFromString<SessionUser>(body)
    } catch (e: Exception) {
        throw ServerUnreachable("The server at $baseUrl answered something that is not a session: ${e.message}", e)
    }

    /**
     * Every cookie the response set, as a `Cookie` header sends them back.
     *
     * `Set-Cookie: SESSION=abc; Path=/; HttpOnly` carries one pair and a pile of attributes
     * meant for a browser; only the pair travels back.
     *
     * All of them are kept, and none is picked by name. The name was `JSESSIONID` while
     * sessions lived in Tomcat's memory and became `SESSION` the day Spring Session was added
     * to make them outlive a restart — which broke every command in this CLI until it stopped
     * looking for a particular one. Which cookie signs a request in is the server's affair,
     * and this also covers the CSRF token its own comments say is still to come.
     */
    private fun cookiesFrom(response: HttpResponse<String>): String? = response.headers()
        .allValues("set-cookie")
        .mapNotNull { header ->
            header.substringBefore(';').trim().takeIf { it.contains('=') && !it.endsWith('=') }
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    // The server sets the cookie on the response; we store it ourselves rather than
    // in a cookie manager that dies with the process.
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

/**
 * Trims a user-supplied base URL to something joinable, and rejects what would only
 * fail later with a worse message.
 */
fun normalizeBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    require(trimmed.isNotEmpty()) { "The server URL is empty." }
    val uri = try {
        URI.create(trimmed)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("'$raw' is not a URL.")
    }
    require(uri.scheme?.lowercase() in setOf("http", "https")) {
        "'$raw' needs an http:// or https:// scheme."
    }
    require(!uri.host.isNullOrBlank()) { "'$raw' has no host." }
    return trimmed
}

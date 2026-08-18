// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionClientTest {

    @Test
    fun `keeps the cookie and drops its attributes`() {
        StubSessionServer(setCookie = "JSESSIONID=A1; Path=/; HttpOnly; SameSite=Lax").use { server ->
            val signedIn = SessionClient(server.baseUrl).login("alice", "hunter2".toCharArray())

            assertEquals("JSESSIONID=A1", signedIn.cookie)
        }
    }

    @Test
    fun `tolerates a session field it has not heard of`() {
        StubSessionServer(
            body = """{"username":"alice","roles":["ROLE_USERS"],"admin":false,"tenant":"acme"}""",
        ).use { server ->
            val signedIn = SessionClient(server.baseUrl).login("alice", "hunter2".toCharArray())

            assertEquals("alice", signedIn.user.username)
            assertEquals(listOf("ROLE_USERS"), signedIn.user.roles)
        }
    }

    @Test
    fun `reads a session that carries only a username`() {
        StubSessionServer(body = """{"username":"alice"}""").use { server ->
            val user = SessionClient(server.baseUrl).login("alice", "hunter2".toCharArray()).user

            assertEquals(emptyList(), user.roles)
            assertEquals(false, user.admin)
            assertEquals(null, user.email)
        }
    }

    @Test
    fun `turns a 401 into a rejection`() {
        StubSessionServer(status = 401, body = "", setCookie = null).use { server ->
            assertFailsWith<CredentialsRejected> {
                SessionClient(server.baseUrl).login("alice", "wrong".toCharArray())
            }
        }
    }

    @Test
    fun `names the status when the server answers something else`() {
        StubSessionServer(status = 500, body = "boom", setCookie = null).use { server ->
            val failure = assertFailsWith<ServerUnreachable> {
                SessionClient(server.baseUrl).login("alice", "hunter2".toCharArray())
            }
            assertContains(failure.message!!, "answered 500")
        }
    }

    @Test
    fun `complains about a body that is not a session`() {
        StubSessionServer(body = "<html>not json</html>").use { server ->
            val failure = assertFailsWith<ServerUnreachable> {
                SessionClient(server.baseUrl).login("alice", "hunter2".toCharArray())
            }
            assertContains(failure.message!!, "not a session")
        }
    }

    @Test
    fun `normalizes a base URL`() {
        assertEquals("http://localhost:8080", normalizeBaseUrl(" http://localhost:8080/ "))
        assertEquals("https://orknux.example.com", normalizeBaseUrl("https://orknux.example.com///"))
        assertEquals("http://localhost:8080/gateway", normalizeBaseUrl("http://localhost:8080/gateway"))
    }

    @Test
    fun `rejects a base URL that cannot be joined to a path`() {
        for (bad in listOf("", "   ", "localhost:8080", "ftp://localhost", "http://")) {
            val failure = assertFailsWith<IllegalArgumentException>("accepted '$bad'") { normalizeBaseUrl(bad) }
            assertTrue(failure.message!!.isNotBlank())
        }
    }
}

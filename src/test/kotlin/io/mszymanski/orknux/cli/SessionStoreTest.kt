package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {

    @TempDir
    lateinit var home: Path

    @Test
    fun `writes and reads a session`() {
        val store = SessionStore(home)
        store.write(StoredSession("http://localhost:8080", "alice", "JSESSIONID=ABC"))

        assertEquals(StoredSession("http://localhost:8080", "alice", "JSESSIONID=ABC"), store.read())
    }

    @Test
    fun `has no session before the first login`() {
        assertNull(SessionStore(home.resolve("not-created-yet")).read())
    }

    @Test
    fun `replaces the previous session rather than appending to it`() {
        val store = SessionStore(home)
        store.write(StoredSession("http://localhost:8080", "alice", "JSESSIONID=OLD"))
        store.write(StoredSession("http://localhost:8080", "bob", "JSESSIONID=NEW"))

        assertEquals("bob", store.read()?.username)
        assertEquals("JSESSIONID=NEW", store.read()?.cookie)
        // The atomic write leaves no temp files behind.
        assertEquals(listOf("session.json"), Files.list(home).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
    }

    @Test
    fun `survives a file somebody else broke`() {
        val store = SessionStore(home)
        Files.createDirectories(home)
        Files.writeString(store.file, "{ not json")

        assertNull(store.read())
        // And a fresh login still works over the top of it.
        store.write(StoredSession("http://localhost:8080", "alice", "JSESSIONID=ABC"))
        assertEquals("alice", store.read()?.username)
    }

    @Test
    fun `leaves the session readable only by its owner`() {
        val store = SessionStore(home)
        store.write(StoredSession("http://localhost:8080", "alice", "JSESSIONID=ABC"))

        val posix = Files.getFileAttributeView(store.file, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                posix.readAttributes().permissions(),
            )
            return
        }
        val acl = Files.getFileAttributeView(store.file, AclFileAttributeView::class.java)
        checkNotNull(acl) { "neither POSIX nor ACL: nothing to assert on this filesystem" }
        assertEquals(listOf(acl.owner), acl.acl.map { it.principal() })
        assertTrue(acl.acl.single().flags().isEmpty(), "the entry must not be inheritable")
    }

    @Test
    fun `defaults the home to an orknux directory under the config root`() {
        val previous = System.getProperty("orknux.config.home")
        System.setProperty("orknux.config.home", home.toString())
        try {
            assertEquals(home, SessionStore.defaultHome())
        } finally {
            if (previous == null) System.clearProperty("orknux.config.home") else System.setProperty("orknux.config.home", previous)
        }
    }
}

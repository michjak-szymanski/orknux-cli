package io.mszymanski.orknux.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * What `orkx login` leaves behind. The cookie is the whole credential — the server
 * issues no token to inspect and no refresh endpoint to call — so this file is as
 * sensitive as the password was, and is written owner-only.
 *
 * `server` is remembered too, so later commands need no `--server` and a bare
 * `orkx login` returns to wherever you last were.
 *
 * The workspace is here for the same reason: the server keeps no current workspace, so
 * the choice is the client's to hold. Its name is kept beside the id only so that a
 * message can say something a person recognises; the id is what anything sends.
 */
@Serializable
data class StoredSession(
    val server: String,
    /**
     * Null between `orkx server use` and `orkx login`: knowing where to talk is not the same
     * as being able to. Every command that needs credentials asks for [active].
     */
    val username: String? = null,
    val cookie: String? = null,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
)

/** A connection that can be used: somewhere to talk, and the means to. */
data class ActiveSession(
    val server: String,
    val username: String,
    val cookie: String,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
)

/**
 * The stored connection when it is signed in, and null when it is a server and nothing more.
 * One place to make that judgement, so no command has to decide what half a session means.
 */
internal fun StoredSession?.active(): ActiveSession? {
    val stored = this ?: return null
    return ActiveSession(
        server = stored.server,
        username = stored.username ?: return null,
        cookie = stored.cookie ?: return null,
        workspaceId = stored.workspaceId,
        workspaceName = stored.workspaceName,
    )
}

/**
 * The session file, and where it lives.
 *
 * Resolution order for the directory, first hit wins:
 *  - `orknux.config.home` system property — what the tests use
 *  - `ORKNUX_CONFIG_HOME` — the server's env vars are all `ORKNUX_*`
 *  - `XDG_CONFIG_HOME/orknux`
 *  - `%APPDATA%\orknux` on Windows
 *  - `~/.config/orknux`
 */
class SessionStore(val home: Path) {

    val file: Path = home.resolve("session.json")

    fun read(): StoredSession? {
        if (!Files.isRegularFile(file)) return null
        return try {
            json.decodeFromString<StoredSession>(Files.readString(file))
        } catch (_: Exception) {
            // A hand-edited or half-written file is not worth failing a fresh login over.
            null
        }
    }

    /**
     * Writes the session, replacing any previous one. Written to a sibling and moved into
     * place so an interrupted write cannot leave a truncated file where a session was.
     */
    fun write(session: StoredSession) {
        Files.createDirectories(home)
        restrictToOwner(home)
        val temp = Files.createTempFile(home, "session", ".json")
        try {
            restrictToOwner(temp)
            Files.writeString(temp, json.encodeToString(session))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
        restrictToOwner(file)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun default(): SessionStore = SessionStore(defaultHome())

        internal fun defaultHome(): Path {
            System.getProperty("orknux.config.home")?.takeIf(String::isNotBlank)
                ?.let { return Path.of(it) }
            System.getenv("ORKNUX_CONFIG_HOME")?.takeIf(String::isNotBlank)
                ?.let { return Path.of(it) }
            System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
                ?.let { return Path.of(it).resolve(DIRECTORY) }
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                System.getenv("APPDATA")?.takeIf(String::isNotBlank)
                    ?.let { return Path.of(it).resolve(DIRECTORY) }
            }
            return Path.of(System.getProperty("user.home")).resolve(".config").resolve(DIRECTORY)
        }

        private const val DIRECTORY = "orknux"
    }
}

/**
 * Takes every permission away from everyone but the owner: POSIX bits where the
 * filesystem has them, otherwise a single-entry ACL on Windows.
 *
 * Best effort by design. A filesystem that supports neither — a mounted share, say —
 * should not stop a login; the alternative is refusing to work at all somewhere the
 * user has already accepted the risk.
 */
internal fun restrictToOwner(path: Path) {
    try {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { posix ->
            val permissions = if (Files.isDirectory(path)) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            posix.setPermissions(permissions)
            return
        }
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let { view ->
            // Replacing the list drops the inherited entries, which is the point: on a
            // shared machine the local administrators group is exactly who should not
            // be reading a live session cookie out of a user's profile.
            view.acl = listOf(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(view.owner)
                    .setPermissions(*AclEntryPermission.entries.toTypedArray())
                    .build(),
            )
        }
    } catch (_: IOException) {
        // Left as the filesystem made it.
    } catch (_: UnsupportedOperationException) {
        // Same.
    } catch (_: SecurityException) {
        // Same.
    }
}

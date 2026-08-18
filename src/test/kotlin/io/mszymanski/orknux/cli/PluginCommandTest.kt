// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginCommandTest {

    @TempDir
    lateinit var configHome: Path

    @TempDir
    lateinit var work: Path

    // ------------------------------------------------------------------- list

    @Test
    fun `lists what is loaded and what each one brings`() {
        StubGraphQlServer(body = PLUGINS).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "1  teammates  teammates.js")
            assertContains(result.out, "API version 1")
            assertContains(result.out, "3 KB")
            assertContains(result.out, "by alice")
            // Named as it is actually reached, not as the plugin spelled it.
            assertContains(result.out, "teammates_isTeammate(email: string): boolean")
            assertContains(result.out, "Whether that address is one of ours")
        }
    }

    @Test
    fun `says when a plugin declares nothing`() {
        val bare = """{"data":{"plugins":[{"id":"2","key":"empty","name":"empty","filename":"empty.js",""" +
            """"sizeBytes":10,"apiVersion":1,"uploadedAt":"","uploadedBy":"","declaredFunctions":[]}]}}"""
        StubGraphQlServer(body = bare).use { server ->
            signedIn(server.baseUrl)

            assertContains(list(server.baseUrl).out, "declares no functions")
        }
    }

    @Test
    fun `reports an installation with no plugins`() {
        StubGraphQlServer(body = """{"data":{"plugins":[]}}""").use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.err, "No plugins are loaded at ${server.baseUrl}.")
        }
    }

    @Test
    fun `passes on the refusal when the caller is not an administrator`() {
        val refusal = """{"errors":[{"message":"This action requires the administrator role"}]}"""
        StubGraphQlServer(body = refusal).use { server ->
            signedIn(server.baseUrl)

            val result = list(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "This action requires the administrator role")
        }
    }

    // ------------------------------------------------------------------- load

    @Test
    fun `sends the file as one multipart part named file`() {
        val plugin = write("teammates.js", "export const id = 'teammates';\n")
        StubUploadServer(body = loaded()).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, plugin)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(1, server.requestCount)
            assertTrue(server.lastContentType!!.startsWith("multipart/form-data; boundary="), server.lastContentType!!)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            // The server reads its @RequestParam("file") from this name.
            assertEquals("file", server.lastPartName)
            assertEquals("teammates.js", server.lastFilename)
        }
    }

    /** The server decodes strictly as UTF-8, so what is sent has to be what is on disk. */
    @Test
    fun `sends the bytes exactly as they are on disk`() {
        val source = "export const id = 'teammates';\n// naïve — ends with a ünicode line\n"
        val plugin = write("teammates.js", source)
        StubUploadServer(body = loaded()).use { server ->
            signedIn(server.baseUrl)

            load(server.baseUrl, plugin)

            assertEquals(source, server.lastContent!!.toString(Charsets.UTF_8))
            assertTrue(Files.readAllBytes(plugin).contentEquals(server.lastContent), "byte for byte")
        }
    }

    @Test
    fun `says when a load replaced one already there`() {
        val plugin = write("teammates.js", "x")
        StubUploadServer(body = loaded(replaced = true)).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, plugin)

            assertContains(result.out, "Replaced teammates as plugin 1, API version 1.")
            assertContains(result.out, "teammates_isTeammate")
        }
    }

    @Test
    fun `says when a load was a first one`() {
        val plugin = write("teammates.js", "x")
        StubUploadServer(body = loaded(replaced = false)).use { server ->
            signedIn(server.baseUrl)

            assertContains(load(server.baseUrl, plugin).out, "Loaded teammates as plugin 1")
        }
    }

    /** Every refusal is about the file somebody chose, and the server writes the sentence. */
    @Test
    fun `passes on what the server would not take`() {
        val plugin = write("teammates.txt", "x")
        StubUploadServer(
            status = 400,
            body = """{"message":"A plugin is a .js or .mjs file; teammates.txt is not"}""",
        ).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, plugin)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "A plugin is a .js or .mjs file; teammates.txt is not")
        }
    }

    @Test
    fun `refuses a path with no file at it`() {
        StubUploadServer(body = loaded()).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, work.resolve("nothing-here.js"))

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "There is no file at")
            assertEquals(0, server.requestCount, "nothing should have been uploaded")
        }
    }

    @Test
    fun `refuses a directory`() {
        StubUploadServer(body = loaded()).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, work)

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `reports an expired session mid-upload`() {
        val plugin = write("teammates.js", "x")
        StubUploadServer(status = 401).use { server ->
            signedIn(server.baseUrl)

            val result = load(server.baseUrl, plugin)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    // --------------------------------------------------------------- generate

    @Test
    fun `writes the server's template to standard output`() {
        StubUploadServer(template = TEMPLATE).use { server ->
            signedIn(server.baseUrl)

            val result = generate(server.baseUrl)

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            // Printed exactly as it came: this is a file somebody is about to edit.
            assertEquals(TEMPLATE, result.out)
            assertEquals("JSESSIONID=ABC", server.lastCookie)
            assertEquals(1, server.templateRequests)
            assertEquals(0, server.requestCount, "generating uploads nothing")
        }
    }

    @Test
    fun `writes it to a file when one is named`() {
        StubUploadServer(template = TEMPLATE).use { server ->
            signedIn(server.baseUrl)
            val destination = work.resolve("starter.js")

            val result = generate(server.baseUrl, "--output", destination.toString())

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(TEMPLATE, Files.readString(destination))
            // Nothing on stdout, so a redirect of it does not collect the note.
            assertEquals("", result.out)
            assertContains(result.err, "Wrote $destination")
            assertContains(result.err, "orkx plugin load --file $destination")
        }
    }

    /** Somebody's work-in-progress plugin is not this command's to replace. */
    @Test
    fun `will not write over a file that is already there`() {
        StubUploadServer(template = TEMPLATE).use { server ->
            signedIn(server.baseUrl)
            val destination = work.resolve("mine.js")
            Files.writeString(destination, "// months of work")

            val result = generate(server.baseUrl, "--output", destination.toString())

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "There is already a file at $destination")
            assertEquals("// months of work", Files.readString(destination))
            // Refused before the server was asked, so it costs nothing.
            assertEquals(0, server.templateRequests)
        }
    }

    @Test
    fun `writes over it when told to`() {
        StubUploadServer(template = TEMPLATE).use { server ->
            signedIn(server.baseUrl)
            val destination = work.resolve("mine.js")
            Files.writeString(destination, "// months of work")

            val result = generate(server.baseUrl, "--output", destination.toString(), "--force")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertEquals(TEMPLATE, Files.readString(destination))
        }
    }

    /**
     * `/api/plugins/template` has no handler for being refused the administrator role, so that
     * arrives as a 500. Reported as the possibility it is, not as a diagnosis.
     */
    @Test
    fun `suggests the likely reason for a server error`() {
        StubUploadServer(templateStatus = 500).use { server ->
            signedIn(server.baseUrl)

            val result = generate(server.baseUrl)

            assertEquals(ExitCode.UNREACHABLE, result.exitCode)
            assertContains(result.err, "answered 500")
            assertContains(result.err, "administrators only, which may be why")
        }
    }

    @Test
    fun `reports an expired session`() {
        StubUploadServer(templateStatus = 401).use { server ->
            signedIn(server.baseUrl)

            val result = generate(server.baseUrl)

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "has expired")
        }
    }

    @Test
    fun `what it generates is what load sends`() {
        StubUploadServer(template = TEMPLATE, body = loaded()).use { server ->
            signedIn(server.baseUrl)
            val destination = work.resolve("round-trip.js")

            generate(server.baseUrl, "--output", destination.toString())
            load(server.baseUrl, destination)

            assertEquals(TEMPLATE, server.lastContent!!.toString(Charsets.UTF_8))
        }
    }

    // ----------------------------------------------------------------- unload

    @Test
    fun `unloads when told to, without asking`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":true}}""").use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "1", "--yes")

            assertEquals(ExitCode.OK, result.exitCode, result.err)
            assertContains(result.out, "Unloaded plugin 1.")
            assertContains(server.lastBody!!, "mutation UnloadPlugin")
            assertContains(server.lastBody!!, """"id":"1"""")
        }
    }

    @Test
    fun `asks first when somebody is there`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":true}}""").use { server ->
            signedIn(server.baseUrl)

            val yes = unload(server.baseUrl, "1") { it.interactive = true; it.readLine = { "y" } }
            assertEquals(ExitCode.OK, yes.exitCode, yes.err)
            assertContains(yes.out, "Unload plugin 1 from this installation? [y/N]")
            assertContains(yes.out, "Unloaded plugin 1.")
        }
    }

    @Test
    fun `unloads nothing on anything but yes`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":true}}""").use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "1") { it.interactive = true; it.readLine = { "n" } }

            assertEquals(ExitCode.OK, result.exitCode)
            assertContains(result.out, "Plugin 1 was left alone.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `refuses to unload unasked when nothing is attached`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":true}}""").use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "1")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "Pass --yes to say you meant to.")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `reports a plugin that was not there`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":false}}""").use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "999", "--yes")

            assertEquals(ExitCode.NOT_FOUND, result.exitCode)
            assertContains(result.err, "No plugin 999 at ${server.baseUrl} to unload.")
        }
    }

    /** The case that would actually break a workflow, and the server is what catches it. */
    @Test
    fun `passes on a refusal to unload something still in use`() {
        val inUse = """{"errors":[{"message":"teammates_isTeammate is used by \"Onboarding\""}]}"""
        StubGraphQlServer(body = inUse).use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "1", "--yes")

            assertEquals(ExitCode.REJECTED, result.exitCode)
            assertContains(result.err, "is used by")
        }
    }

    @Test
    fun `refuses a plugin id that cannot be one`() {
        StubGraphQlServer(body = """{"data":{"unloadPlugin":true}}""").use { server ->
            signedIn(server.baseUrl)

            val result = unload(server.baseUrl, "teammates", "--yes")

            assertEquals(ExitCode.USAGE, result.exitCode)
            assertContains(result.err, "'teammates' is not a plugin id; those are numbers.")
            assertEquals(0, server.requestCount)
        }
    }

    // ----------------------------------------------------------------- shared

    @Test
    fun `all three want a session`() {
        StubGraphQlServer(body = PLUGINS).use { server ->
            assertContains(list(server.baseUrl).err, "Not signed in.")
            assertContains(unload(server.baseUrl, "1", "--yes").err, "Not signed in.")
        }
        StubUploadServer(body = loaded()).use { server ->
            assertContains(load(server.baseUrl, write("teammates.js", "x")).err, "Not signed in.")
        }
    }

    @Test
    fun `colour adds nothing but colour`() {
        StubGraphQlServer(body = PLUGINS).use { server ->
            signedIn(server.baseUrl)

            val plain = list(server.baseUrl) { it.styleOverride = Style(enabled = false) }
            val coloured = list(server.baseUrl) { it.styleOverride = Style(enabled = true) }

            assertTrue(coloured.out.length > plain.out.length)
            assertEquals(plain.out, stripAnsi(coloured.out))
        }
    }

    private fun write(name: String, content: String): Path =
        work.resolve(name).also { Files.writeString(it, content) }

    private fun signedIn(server: String) =
        SessionStore(configHome).write(StoredSession(server, "alice", "JSESSIONID=ABC", "1", "foo"))

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun list(
        server: String,
        configure: (PluginListCommand) -> Unit = {},
    ): Result = execute(listOf("plugin", "list")) { command ->
        (command.subcommands.getValue("list").getCommand<PluginListCommand>()).apply {
            store = SessionStore(configHome)
            clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
            configure(this)
        }
    }

    private fun load(server: String, file: Path): Result =
        execute(listOf("plugin", "load", "--file", file.toString())) { command ->
            command.subcommands.getValue("load").getCommand<PluginLoadCommand>().apply {
                store = SessionStore(configHome)
                uploadFactory = { _, cookie -> PluginClient(server, cookie) }
            }
        }

    private fun generate(server: String, vararg args: String): Result =
        execute(listOf("plugin", "generate") + args) { command ->
            command.subcommands.getValue("generate").getCommand<PluginGenerateCommand>().apply {
                store = SessionStore(configHome)
                clientFactory = { _, cookie -> PluginClient(server, cookie) }
            }
        }

    private fun unload(
        server: String,
        id: String,
        vararg args: String,
        configure: (PluginUnloadCommand) -> Unit = {},
    ): Result = execute(listOf("plugin", "unload", id) + args) { command ->
        command.subcommands.getValue("unload").getCommand<PluginUnloadCommand>().apply {
            store = SessionStore(configHome)
            clientFactory = { _, cookie -> GraphQlClient(server, cookie) }
            interactive = false
            configure(this)
        }
    }

    private fun execute(args: List<String>, wire: (picocli.CommandLine) -> Unit): Result {
        val out = StringWriter()
        val err = StringWriter()
        val command = orkxCommandLine()
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
        wire(command.subcommands.getValue("plugin"))

        val exitCode = command.execute(*args.toTypedArray())
        return Result(exitCode, out.toString(), err.toString())
    }

    private companion object {
        /** Stands in for whatever the server generates; only that it survives intact matters. */
        val TEMPLATE = """
            export default class extends OrknuxPlugin {
              id() { return 'starter'; }
            }
        """.trimIndent()

        const val PLUGINS = """{"data":{"plugins":[{"id":"1","key":"teammates","name":"teammates",""" +
            """"filename":"teammates.js","sizeBytes":3072,"apiVersion":1,""" +
            """"uploadedAt":"2026-08-17T13:22:11+02:00","uploadedBy":"alice","declaredFunctions":[""" +
            """{"name":"isTeammate","description":"Whether that address is one of ours",""" +
            """"signature":"(email: string): boolean"}]}]}}"""

        fun loaded(replaced: Boolean = false): String =
            """{"plugin":{"id":"1","key":"teammates","name":"teammates","filename":"teammates.js",""" +
                """"sizeBytes":3072,"apiVersion":1,"uploadedAt":"2026-08-17T13:22:11+02:00",""" +
                """"uploadedBy":"alice","declaredFunctions":[]},"replaced":$replaced,""" +
                """"provides":["teammates_isTeammate"]}"""
    }
}

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * These assertions never spell out an escape sequence. What matters about styling is not
 * which codes it emits but that it adds no width and removes no text — so that is what is
 * checked, and the tests stay readable.
 */
class StyleTest {

    private val on = Style(enabled = true)
    private val off = Style(enabled = false)

    @Test
    fun `adds nothing at all when disabled`() {
        assertEquals("alice", off.name("alice"))
        assertEquals("Steps", off.heading("Steps"))
        assertEquals("2026-01-01", off.faint("2026-01-01"))
        assertEquals("FAILED", off.status("FAILED"))
        assertEquals("ERROR", off.level("ERROR"))
        assertEquals("*", off.marker("*"))
    }

    @Test
    fun `styling occupies no width`() {
        for (styled in listOf(on.name("alice"), on.heading("ID"), on.faint("x"), on.status("COMPLETED"))) {
            assertEquals(stripAnsi(styled).length, visibleLength(styled))
        }
        assertEquals(5, visibleLength(on.name("alice")))
    }

    @Test
    fun `styling keeps the text intact`() {
        assertEquals("nightly-sync", stripAnsi(on.name("nightly-sync")))
        assertEquals("FAILED", stripAnsi(on.status("FAILED")))
    }

    @Test
    fun `does actually style when enabled`() {
        assertNotEquals("alice", on.name("alice"))
        assertTrue(on.name("alice").length > "alice".length)
    }

    @Test
    fun `colours a status by what it means`() {
        val completed = on.status("COMPLETED")
        val failed = on.status("FAILED")
        val running = on.status("RUNNING")

        assertNotEquals(completed, failed)
        assertNotEquals(failed, running)
        // SUCCESS reads like COMPLETED, so it is coloured the same way.
        assertEquals(stripAnsi(on.status("SUCCESS")), "SUCCESS")
        assertNotEquals(on.status("SUCCESS"), "SUCCESS")
    }

    /**
     * The server already answers with more statuses than its schema admits — SKIPPED and
     * PENDING turned up on real data. One it has never heard of is printed as it came.
     */
    @Test
    fun `leaves a status it does not know unstyled`() {
        assertEquals("CANCELLED", on.status("CANCELLED"))
        assertEquals("", on.status(""))
    }

    @Test
    fun `colours a log level`() {
        assertNotEquals("ERROR", on.level("ERROR"))
        assertNotEquals(on.level("ERROR"), on.level("SUCCESS"))
        assertEquals("TRACE", on.level("TRACE"))
    }

    @Test
    fun `styles nothing when there is nothing to style`() {
        assertEquals("", on.name(""))
        assertEquals("", on.faint(""))
    }

    @Test
    fun `strips what it added`() {
        assertEquals("plain", stripAnsi("plain"))
        assertEquals("alice", stripAnsi(on.faint(on.name("alice"))))
    }
}

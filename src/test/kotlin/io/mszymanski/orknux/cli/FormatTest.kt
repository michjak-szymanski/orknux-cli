package io.mszymanski.orknux.cli

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormatTest {

    @Test
    fun `sizes every column to its widest cell`() {
        val table = renderTable(
            listOf("ID", "NAME"),
            listOf(listOf("1", "a-long-name"), listOf("222", "b")),
        )

        assertEquals(
            listOf(
                "ID   NAME",
                "1    a-long-name",
                "222  b",
            ),
            table,
        )
    }

    @Test
    fun `leaves no trailing whitespace on a short last cell`() {
        val table = renderTable(listOf("ID", "DESCRIPTION"), listOf(listOf("1", "")))

        assertEquals("1", table[1])
    }

    @Test
    fun `keeps a heading wider than its cells`() {
        val table = renderTable(listOf("DURATION"), listOf(listOf("3s")))

        assertEquals(listOf("DURATION", "3s"), table)
    }

    @Test
    fun `refuses a row that does not match the headings`() {
        assertFailsWith<IllegalArgumentException> {
            renderTable(listOf("A", "B"), listOf(listOf("only one")))
        }
    }

    /**
     * The one that matters: a coloured table and a plain one must be the same table. Escape
     * codes carry no width, so measuring cells by `String.length` would pad the coloured
     * ones short and knock every later column out of line.
     */
    @Test
    fun `lays a coloured table out exactly like a plain one`() {
        val style = Style(enabled = true)
        val headings = listOf("ID", "STATUS", "NAME")
        val rows = listOf(
            listOf("1", "COMPLETED", "nightly-sync"),
            listOf("22", "FAILED", "a"),
        )

        val coloured = renderTable(
            headings.map(style::heading),
            rows.map { listOf(it[0], style.status(it[1]), style.name(it[2])) },
        )

        assertEquals(renderTable(headings, rows), coloured.map(::stripAnsi))
    }

    @Test
    fun `renders an empty table as its headings`() {
        assertEquals(listOf("A  B"), renderTable(listOf("A", "B"), emptyList()))
    }

    /** The server answers in its own offset; the reader is here, not there. */
    @Test
    fun `shows a timestamp in this machine's zone`() {
        val raw = "2026-08-17T13:22:11+02:00"
        val expected = OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        assertEquals(expected, formatTimestamp(raw))
    }

    @Test
    fun `passes through a timestamp it cannot read`() {
        assertEquals("whenever", formatTimestamp("whenever"))
    }

    @Test
    fun `shows a duration at the scale it happened`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("59s", formatDuration(59))
        assertEquals("1m 0s", formatDuration(60))
        assertEquals("1m 2s", formatDuration(62))
        assertEquals("59m 59s", formatDuration(3599))
        assertEquals("1h 0m", formatDuration(3600))
        assertEquals("2h 5m", formatDuration(7500))
    }

    /** Null is a run still going, which is not the same as one that took no time. */
    @Test
    fun `shows an unfinished run as unfinished`() {
        assertEquals("-", formatDuration(null))
    }
}

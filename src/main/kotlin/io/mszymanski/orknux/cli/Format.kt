// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * How every list this CLI prints is laid out: columns left-aligned, two spaces between
 * them, no trailing whitespace. One function so `workspace list` and `execution list` do
 * not drift into looking like different programs.
 *
 * A marker column is an ordinary column with an empty heading — see `workspace list`,
 * where it holds the asterisk.
 */
internal fun renderTable(headings: List<String>, rows: List<List<String>>): List<String> {
    require(rows.all { it.size == headings.size }) { "every row needs one cell per heading" }

    // Measured by what shows, not by String.length: a coloured cell carries escape codes
    // that occupy no width, and padding by length pushes every later column out of line.
    val widths = headings.indices.map { column ->
        maxOf(visibleLength(headings[column]), rows.maxOfOrNull { visibleLength(it[column]) } ?: 0)
    }

    fun line(cells: List<String>) = cells
        .mapIndexed { column, cell -> cell + " ".repeat(widths[column] - visibleLength(cell)) }
        .joinToString(GUTTER)
        .trimEnd()

    return listOf(line(headings)) + rows.map(::line)
}

private const val GUTTER = "  "

/**
 * An ISO-8601 offset timestamp from the server, shown in this machine's zone — the server
 * answers in its own offset, and nobody reading a terminal wants to do that arithmetic.
 *
 * Anything unparseable is passed through untouched rather than dropped: a timestamp we do
 * not understand is still the only record of when something happened.
 */
internal fun formatTimestamp(raw: String): String = try {
    OffsetDateTime.parse(raw)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(TIMESTAMP)
} catch (_: DateTimeParseException) {
    raw
}

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Null means the run has not finished, which is worth showing as such rather than as zero. */
internal fun formatDuration(seconds: Int?): String = when {
    seconds == null -> "-"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

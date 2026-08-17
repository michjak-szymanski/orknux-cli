package io.mszymanski.orknux.cli

/**
 * Windows PowerShell prefixes whatever it pipes into a native process with a UTF-8 byte
 * order mark. Every line that arrives on standard input goes through here first.
 *
 * Trimming does not deal with it: `U+FEFF` is a format character, not whitespace, so
 * `"\uFEFF".trim()` is not empty. Left in place it becomes an invisible character on the
 * front of a password, or — worse, because it is silent — a chat message consisting of
 * nothing but a BOM, sent because the line did not look blank.
 */
internal fun String.withoutByteOrderMark(): String = removePrefix(BYTE_ORDER_MARK)

/**
 * One line from standard input, cleaned of the mark above, or null at the end of it.
 *
 * The reader is made once and kept: a fresh one each call would drop whatever the last had
 * buffered, which for an interactive loop means losing input that was already typed.
 */
internal fun readStandardInputLine(): String? = stdin.readLine()?.withoutByteOrderMark()

private val stdin by lazy { System.`in`.bufferedReader() }

private const val BYTE_ORDER_MARK = "\uFEFF"

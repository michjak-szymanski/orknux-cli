package io.mszymanski.orknux.cli

/**
 * The server's ids are numbers.
 *
 * The schema calls them `ID!`, which is nominally opaque, but every resolver binds the
 * argument to a Kotlin `Long` — `workspace(@Argument id: Long)`, `execution(@Argument id:
 * Long)`. Sending a word therefore does not come back as a bad request; it comes back as
 * `INTERNAL_ERROR` and a correlation number, which tells the person at the terminal
 * nothing at all.
 *
 * So ids are checked here first. Returns the trimmed id, or null for anything that could
 * not be one — the caller words its own refusal, because "workspace" and "execution" want
 * different sentences.
 *
 * If the server's ids ever stop being numbers, this is the one place to change.
 */
internal fun serverIdOrNull(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() && it.toLongOrNull() != null }

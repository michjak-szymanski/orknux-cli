package io.mszymanski.orknux.cli

import picocli.CommandLine.Help
import picocli.CommandLine.Model.CommandSpec

/** When to colour, in the conventional three states that `git` and `ls` have. */
enum class ColorWhen { AUTO, ALWAYS, NEVER }

/**
 * Colour and weight for the output, or nothing at all.
 *
 * Escape codes are written directly rather than through picocli's `@|bold …|@` markup,
 * because most of what gets styled here is text from the server — a workflow named
 * `report|@2` would otherwise be read as markup and mangled.
 *
 * Styling is decoration and never information: a status is coloured *and* spelled out, so
 * a pipe, a log file or a colour-blind reader loses nothing.
 */
internal class Style(internal val enabled: Boolean) {

    /** Column headings and the title of a block. */
    fun heading(text: String): String = sgr(text, BOLD)

    /** A name worth finding again: a user, a workspace, a workflow. */
    fun name(text: String): String = sgr(text, BOLD)

    /** Present but secondary — a label, a timestamp, an id beside a name. */
    fun faint(text: String): String = sgr(text, GREY)

    fun good(text: String): String = sgr(text, GREEN)

    fun bad(text: String): String = sgr(text, RED)

    /** The asterisk against the workspace in use. */
    fun marker(text: String): String = sgr(text, CYAN)

    /**
     * A run's or step's status, coloured by what it means. An unknown one is left plain
     * rather than guessed at — the server has more of these than the schema admits, and a
     * word printed without colour is still the word.
     */
    fun status(value: String): String = when (value.uppercase()) {
        "COMPLETED", "SUCCESS", "HEALTHY", "OK" -> good(value)
        "FAILED", "ERROR", "DOWN", "FAIL" -> bad(value)
        "RUNNING", "PENDING", "DEGRADED", "WARN" -> sgr(value, YELLOW)
        "SKIPPED" -> faint(value)
        else -> value
    }

    /** A log line's level. `LogLevel` is INFO, SUCCESS or ERROR; anything else stays plain. */
    fun level(value: String): String = when (value.uppercase()) {
        "SUCCESS" -> good(value)
        "ERROR" -> bad(value)
        "INFO" -> faint(value)
        else -> value
    }

    private fun sgr(text: String, code: String): String =
        if (!enabled || text.isEmpty()) text else "$ESCAPE$code" + "m" + text + RESET

    private companion object {
        const val ESCAPE = "\u001B["
        const val RESET = "\u001B[0m"
        const val BOLD = "1"
        const val RED = "31"
        const val GREEN = "32"
        const val YELLOW = "33"
        const val CYAN = "36"

        /** Bright black. The faint code, 2, is ignored by too many terminals to rely on. */
        const val GREY = "90"
    }
}

/**
 * Whether to colour, for one command run.
 *
 * `AUTO` needs both of two things to agree. Picocli's `Help.Ansi.AUTO` is asked because it
 * knows the conventions — `NO_COLOR`, `CLICOLOR`, `TERM=dumb` — and any of those still
 * turns colour off. But on its own it says yes too readily: it infers support from `TERM`,
 * so under a Unix-like shell on Windows it answers yes even when the output is a pipe, and
 * `orkx execution list > runs.txt` would fill the file with escape codes.
 *
 * So a real terminal is required as well. `Console.isTerminal` is the JDK's own isatty and
 * exists from Java 22; the older `System.console() != null` test cannot stand in for it,
 * because since that same release a console is handed out for redirected output too.
 */
internal fun styleFor(spec: CommandSpec): Style {
    val chosen = (spec.root().userObject() as? Orkx)?.color ?: ColorWhen.AUTO
    return Style(
        when (chosen) {
            ColorWhen.ALWAYS -> true
            ColorWhen.NEVER -> false
            ColorWhen.AUTO -> attachedToTerminal() && Help.Ansi.AUTO.enabled()
        },
    )
}

/**
 * Whether a person is on the other end. Also what decides whether an interactive command
 * prints a prompt — there is nobody to prompt at the end of a pipe.
 */
internal fun attachedToTerminal(): Boolean = System.console()?.isTerminal() == true

/**
 * The width a string occupies on screen, which is not its length once it carries colour.
 * Any column that might hold styled text has to be measured with this, or the escape
 * codes are counted as characters and the table stops lining up.
 */
internal fun visibleLength(text: String): Int = stripAnsi(text).length

/** The same text with every colour code taken out — what a redirected run would have written. */
internal fun stripAnsi(text: String): String = SGR.replace(text, "")

private val SGR = Regex("\u001B\\[[0-9;]*m")

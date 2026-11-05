package app.assistant.domain.engine.redact

import app.assistant.domain.error.Outcome
import java.time.Duration

/** The typed stand-in for each kind of removed item (Requirement 46.2). None of them matches any detector. */
enum class Placeholder(val text: String) {
    NAME("[NAME]"),
    PHONE("[PHONE]"),
    ACCOUNT("[ACCOUNT]"),
    CARD("[CARD]"),
    UPI("[UPI]"),
    EMAIL("[EMAIL]"),
    LINK("[LINK]"),
    INSTRUCTION("[INSTRUCTION]"),
}

/** Why a text could not be redacted. The caller sends nothing on any of these (Requirement 46.6). */
enum class RedactionError {
    /** A configured pattern does not compile, matches nothing useful, or would match a placeholder. */
    INVALID_PATTERN,

    /** The input is longer than the Redactor accepts. */
    TOO_LONG,

    /** Matching used up its time budget. */
    TIMED_OUT,

    /** Redacting did not settle on one output, so idempotence could not be promised. */
    NOT_STABLE,
}

/**
 * The configurable part of the Redactor: the Instruction_Pattern_List and the person names it must
 * always remove (the Redactor_Test_Set names). Build one with [of], which refuses a list that could
 * make redaction unsafe; there is no other way in.
 */
class RedactionPatterns private constructor(internal val instruction: List<Regex>, internal val knownNames: List<Regex>, internal val timeBudget: Duration) {
    companion object {
        /** Long enough for any real message, short enough that a bad pattern fails closed quickly. */
        val DEFAULT_TIME_BUDGET: Duration = Duration.ofSeconds(2)

        /**
         * Each instruction pattern is a case-insensitive regular expression. A pattern is rejected
         * if it is blank, does not compile, matches the empty text, or matches any [Placeholder];
         * the last two would stop redaction from ever settling.
         */
        fun of(
            instructionPatterns: List<String>,
            knownNames: List<String> = emptyList(),
            timeBudget: Duration = DEFAULT_TIME_BUDGET,
        ): Outcome<RedactionPatterns, RedactionError> {
            val instruction = instructionPatterns.map { source -> compile(source) ?: return Outcome.Err(RedactionError.INVALID_PATTERN) }
            if (instruction.any { it.matchesEmptyOrPlaceholder() }) return Outcome.Err(RedactionError.INVALID_PATTERN)
            val names = knownNames.filter { it.isNotBlank() }.map { Regex("(?<![A-Za-z])" + Regex.escape(it.trim()) + "(?![A-Za-z])", RegexOption.IGNORE_CASE) }
            return Outcome.Ok(RedactionPatterns(instruction, names, timeBudget))
        }

        private fun compile(source: String): Regex? = if (source.isBlank()) null else runCatching { Regex(source, RegexOption.IGNORE_CASE) }.getOrNull()

        private fun Regex.matchesEmptyOrPlaceholder(): Boolean = matches("") || containsMatchIn("") || Placeholder.entries.any { containsMatchIn(it.text) }
    }
}

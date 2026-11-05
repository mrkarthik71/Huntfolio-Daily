package app.assistant.domain.engine.redact

import app.assistant.domain.error.Outcome

/**
 * Removes personal identifiers, links and instruction text from a text before it is stored in the
 * Review_Queue or reaches the AI_Provider (Requirements 4.3, 46.2 to 46.4, design: Redactor).
 *
 * It replaces person names, phone numbers, account and card numbers (including masked forms that
 * show only the last digits), UPI IDs, email addresses, links and text matching the
 * Instruction_Pattern_List with a placeholder naming the type, and keeps money amounts, dates and
 * times. Name detection is pattern- and context-based ("Dear X", "to X", honorifics, known names),
 * so a name in an arbitrary position can be missed; that limit is accepted in the design.
 *
 * Fails closed: [redact] returns an error rather than text it could not fully process, and it
 * returns a text only once another pass would change nothing, so redacting twice always gives the
 * same output as redacting once (Requirement 46.4).
 */
object Redactor {
    /** Longer input is refused; the Review_Queue and AI paths never need more. */
    const val MAX_INPUT_LENGTH = 100_000

    private const val MAX_ROUNDS = 6
    private const val CARD_MIN_DIGITS = 13
    private const val CHECK_EVERY = 256

    fun redact(input: String, patterns: RedactionPatterns): Outcome<String, RedactionError> {
        if (input.length > MAX_INPUT_LENGTH) return Outcome.Err(RedactionError.TOO_LONG)
        // A monotonic timer, not the wall clock: it only bounds how long this call may run.
        val deadline = System.nanoTime() + patterns.timeBudget.toNanos()
        return try {
            var current = input
            repeat(MAX_ROUNDS) {
                val next = pass(current, patterns, deadline)
                if (next == current) return Outcome.Ok(current)
                current = next
            }
            Outcome.Err(RedactionError.NOT_STABLE)
        } catch (_: TimeBudgetExceeded) {
            Outcome.Err(RedactionError.TIMED_OUT)
        }
    }

    private class TimeBudgetExceeded : RuntimeException()

    /** A view of a text that gives up once the deadline passes, so no regular expression can run unbounded. */
    private class Limited(private val base: CharSequence, private val deadline: Long) : CharSequence {
        private var reads = 0

        override val length: Int get() = base.length

        override fun get(index: Int): Char {
            if (++reads % CHECK_EVERY == 0 && System.nanoTime() > deadline) throw TimeBudgetExceeded()
            return base[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Limited(base.subSequence(startIndex, endIndex), deadline)

        override fun toString(): String = base.toString()
    }

    private fun Regex.replaceAll(text: String, deadline: Long, with: (MatchResult) -> String): String = replace(Limited(text, deadline)) { with(it) }

    private fun pass(text: String, patterns: RedactionPatterns, deadline: Long): String {
        var out = text
        for (link in RedactionDetectors.LINKS) out = link.replaceAll(out, deadline) { Placeholder.LINK.text }
        out = RedactionDetectors.ADDRESS.replaceAll(out, deadline, ::address)
        for (instruction in patterns.instruction) out = instruction.replaceAll(out, deadline) { Placeholder.INSTRUCTION.text }
        return withoutProtected(out, deadline) { free -> identifiers(free, patterns, deadline) }
    }

    /** `name@domain.tld` is an email address; `name@handle` with no dot is a UPI ID. Leading punctuation stays. */
    private fun address(match: MatchResult): String {
        val local = match.groups[1] ?: return match.value
        val placeholder = if ('.' in match.groupValues[2]) Placeholder.EMAIL else Placeholder.UPI
        return match.value.substring(0, local.range.first - match.range.first) + placeholder.text
    }

    /** Runs [transform] on the stretches between amounts, dates and times, and leaves those untouched. */
    private fun withoutProtected(text: String, deadline: Long, transform: (String) -> String): String {
        val out = StringBuilder(text.length)
        var last = 0
        for (match in RedactionDetectors.PROTECTED.findAll(Limited(text, deadline))) {
            out.append(transform(text.substring(last, match.range.first)))
            out.append(match.value)
            last = match.range.last + 1
        }
        return out.append(transform(text.substring(last))).toString()
    }

    private fun identifiers(free: String, patterns: RedactionPatterns, deadline: Long): String {
        var out = free
        for (phone in RedactionDetectors.PHONES) out = phone.replaceAll(out, deadline) { Placeholder.PHONE.text }
        out = RedactionDetectors.MASKED.replaceAll(out, deadline) { Placeholder.ACCOUNT.text }
        out = RedactionDetectors.ENDING.replaceAll(out, deadline) { it.groupValues[1] + Placeholder.ACCOUNT.text }
        out = RedactionDetectors.LONG_DIGITS.replaceAll(out, deadline) { m ->
            if (m.value.count(Char::isDigit) >= CARD_MIN_DIGITS) Placeholder.CARD.text else Placeholder.ACCOUNT.text
        }
        for (name in patterns.knownNames) out = name.replaceAll(out, deadline) { Placeholder.NAME.text }
        for (context in RedactionDetectors.NAME_CONTEXTS) out = context.replaceAll(out, deadline) { it.groupValues[1] + Placeholder.NAME.text }
        return out
    }
}

package app.assistant.ai.numbers

import app.assistant.domain.model.PromptType

/**
 * Extracts every number-like token from a text so the Output_Validator can prove that each number
 * an AI output names also appears in the payload the model was given (Property 64, Requirement
 * 47.3). The model may rephrase words freely, but it may not invent figures.
 *
 * A token is normalised to a canonical form so that "₹1,200.00", "Rs 1200", "1200" and "one
 * thousand two hundred" all compare equal, and "3:30 pm" and "15:30" do not have to. The rule is
 * deliberately strict about what counts as a number and lenient about surrounding text.
 */
object NumberTokens {
    /**
     * All distinct canonical numeric tokens in [text]. Currency markers, grouping separators and a
     * trailing "%" are stripped; a bare integer and the same integer written in English words map
     * to the same canonical string, so a payload figure and its spelled-out echo match.
     */
    fun extract(text: String): Set<String> {
        val out = linkedSetOf<String>()
        for (m in DIGITS.findAll(text)) out += canonicalDigits(m.value)
        out += englishNumbers(text)
        out += clockTimes(text)
        return out
    }

    /** True when every numeric token in [candidate] also appears in [source] (output ⊆ payload). */
    fun allPresent(candidate: String, source: String): Boolean {
        val have = extract(source)
        return extract(candidate).all { it in have }
    }

    private fun canonicalDigits(raw: String): String {
        val percent = raw.trimEnd().endsWith("%")
        var s = raw.filter { it.isDigit() || it == '.' }
        if (s.isEmpty()) return raw.trim()
        // Drop a trailing ".00" / ".0" so "1200" and "1200.00" are the same amount.
        if ('.' in s) s = s.trimEnd('0').trimEnd('.')
        if (s.isEmpty()) s = "0"
        // Drop leading zeros but keep a single zero.
        s = s.trimStart('0').ifEmpty { "0" }
        if (s.startsWith('.')) s = "0$s"
        return if (percent) "$s%" else s
    }

    private fun clockTimes(text: String): Set<String> = CLOCK.findAll(text).map { m ->
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        "%02d:%02d".format(h, min)
    }.toSet()

    /** Spelled-out cardinals 0-9999 (e.g. "one thousand two hundred" -> "1200"). Best effort, no decimals. */
    @Suppress("NestedBlockDepth", "ComplexCondition") // a small hand-written number-word scanner
    private fun englishNumbers(text: String): Set<String> {
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        val out = mutableSetOf<String>()
        var i = 0
        while (i < words.size) {
            if (words[i] !in UNITS && words[i] !in TENS && words[i] != "hundred" && words[i] != "thousand") {
                i++
                continue
            }
            var total = 0
            var current = 0
            var consumed = 0
            while (i < words.size) {
                val w = words[i]
                when {
                    w in UNITS -> current += UNITS.getValue(w)
                    w in TENS -> current += TENS.getValue(w)
                    w == "hundred" -> current = (if (current == 0) 1 else current) * HUNDRED
                    w == "thousand" -> {
                        total += (if (current == 0) 1 else current) * THOUSAND
                        current = 0
                    }
                    w == "and" && consumed > 0 -> {}
                    else -> break
                }
                i++
                consumed++
            }
            if (consumed > 0) out += (total + current).toString()
            i++
        }
        return out
    }

    private const val HUNDRED = 100
    private const val THOUSAND = 1000

    // A run of digits, optional grouping and decimals, optional leading currency marker, optional trailing %.
    private val DIGITS = Regex("""(?:₹|rs\.?|inr)?\s?\d[\d,]*(?:\.\d+)?\s?%?""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
}

/**
 * What an AI output of each [PromptType] is allowed to derive, from the design's Derivation_Rules
 * (Requirement 47.3). Wording may always be rephrased; numbers, dates and times must come from the
 * payload; identity (which task, which gap) is by opaque handle only.
 */
object DerivationRules {
    data class Rule(
        /** Free prose the model may write (titles, encouragement, summaries). */
        val mayRephraseText: Boolean,
        /** Every number in the output must appear in the payload (checked via [NumberTokens]). */
        val numbersFromPayloadOnly: Boolean,
        /** The output may only name items by the opaque handles present in the payload. */
        val handlesFromPayloadOnly: Boolean,
        /** Daily-plan placements must fall inside the payload's Free_Gaps (Requirement 48.6). */
        val placementsWithinFreeGaps: Boolean,
    )

    fun forType(type: PromptType): Rule = when (type) {
        PromptType.DAILY_PLAN -> Rule(mayRephraseText = true, numbersFromPayloadOnly = true, handlesFromPayloadOnly = true, placementsWithinFreeGaps = true)
        PromptType.EVENING_REVIEW,
        PromptType.WEEKLY_REVIEW,
        PromptType.MONTHLY_MONEY_REVIEW,
        PromptType.ASSISTANT_ANSWER,
        -> Rule(mayRephraseText = true, numbersFromPayloadOnly = true, handlesFromPayloadOnly = true, placementsWithinFreeGaps = false)
        PromptType.MAIL_CLASSIFIER -> Rule(
            mayRephraseText = true,
            numbersFromPayloadOnly = true,
            handlesFromPayloadOnly = true,
            placementsWithinFreeGaps = false,
        )
        PromptType.SMS_FALLBACK_PARSER -> Rule(
            mayRephraseText = false,
            numbersFromPayloadOnly = true,
            handlesFromPayloadOnly = false,
            placementsWithinFreeGaps = false,
        )
    }
}

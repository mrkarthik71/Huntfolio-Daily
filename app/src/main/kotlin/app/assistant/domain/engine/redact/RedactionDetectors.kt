package app.assistant.domain.engine.redact

/**
 * The patterns the Redactor looks for. Every quantifier is bounded or possessive, and every scan
 * starts only at the beginning of a token, so matching a long input stays linear and cannot
 * backtrack catastrophically (the Redactor also runs under a time budget as a second guard).
 */
internal object RedactionDetectors {
    private const val RUPEE = "\\u20B9"
    private const val MARKER = "(?:rs\\.?|inr|$RUPEE)"
    private const val MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"

    /** Amounts, dates and times are kept (Requirement 46.2), so no other detector may look inside them. */
    val PROTECTED: Regex = Regex(
        listOf(
            "(?<![a-z])$MARKER\\s?\\d[\\d,]*(?:\\.\\d+)?(?:/-)?",
            "(?<![\\d.,])\\d[\\d,]*(?:\\.\\d+)?(?:/-)?\\s?$MARKER(?![a-z])",
            "(?<![\\w])\\d{1,2}(?:st|nd|rd|th)?[-/ ]?$MONTH[a-z]{0,6}[-/ ,]{0,3}\\d{2,4}(?!\\d)",
            "(?<![a-z])$MONTH[a-z]{0,6}\\.?\\s\\d{1,2}(?:st|nd|rd|th)?,?\\s\\d{4}(?!\\d)",
            "(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)",
            "(?<![\\d.])\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}(?!\\d)",
            "(?<![\\d:])\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[ap]m\\b)?(?!\\d)",
            "(?<![\\d,.])\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?(?!\\d)",
            "(?<![\\d.])\\d+\\.\\d{1,2}(?!\\d)",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    private const val URL_BODY = "[^\\s<>\"']{1,2000}"
    private const val TLDS = "com|in|net|org|co|io|me|ly|app|xyz|info|biz|ai|link|click|online|site|shop|store|page|top|example"

    val LINKS: List<Regex> = listOf(
        Regex("\\b(?:https?|ftp)://$URL_BODY", RegexOption.IGNORE_CASE),
        Regex("\\bwww\\.$URL_BODY", RegexOption.IGNORE_CASE),
        Regex("(?<![@\\w.-])(?:[a-z0-9-]{1,63}\\.){1,8}(?:$TLDS)\\b(?:[/?#]$URL_BODY)?", RegexOption.IGNORE_CASE),
    )

    /** `local@domain`: group 1 is the address without leading punctuation; group 2 is the part after the `@`. */
    val ADDRESS: Regex = Regex(
        "(?<![a-z0-9._%+-])[._%+-]*+([a-z0-9][a-z0-9._%+-]{0,200}+)@([a-z0-9-]{1,63}+(?:\\.[a-z0-9-]{1,63}+){0,8}+)",
        RegexOption.IGNORE_CASE,
    )

    /** Indian mobile numbers with an optional country code or trunk zero, and `+` international numbers. */
    val PHONES: List<Regex> = listOf(
        Regex("(?<![\\d+])(?:(?:\\+91|91|0)[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)"),
        Regex("(?<![\\d+])\\+\\d{1,3}[ -]?\\d(?:[ -]?\\d){6,12}(?!\\d)"),
    )

    /** Masked account or card numbers showing only the last digits: `XX1234`, `XXXX XXXX 1234`, `****1234`. */
    val MASKED: Regex = Regex("(?<![A-Za-z0-9])(?:[Xx*\\u2022]{2,12}+[ -]?+){1,5}+\\d{2,6}+(?!\\d)")

    /** `card ending 4321`, `a/c ending in 1234`: group 1 is the keyword, group 2 the digits shown. */
    val ENDING: Regex = Regex("\\b(ending(?: in)?\\s{1,3})(\\d{2,6})(?!\\d)", RegexOption.IGNORE_CASE)

    /** Nine or more digits, with single spaces or hyphens between them: an account or card number. */
    val LONG_DIGITS: Regex = Regex("(?<!\\d)\\d(?:[ -]?\\d){8,18}(?!\\d)")

    private const val NAME = "[A-Z][A-Za-z]{1,30}(?:\\s{1,3}[A-Z][A-Za-z]{1,30}){0,2}"

    /** A greeting, honorific or "to/from/by/for" before a capitalised name: group 1 is the keyword, group 2 the name. */
    val NAME_CONTEXTS: List<Regex> = listOf(
        Regex("\\b((?i:dear|hi|hello|hey|mr|mrs|ms|miss|dr|shri|smt|sri|prof)\\b\\.?\\s{1,3})($NAME)"),
        Regex("\\b((?i:paid to|sent to|received from|to|from|by|for|attn|c/o)\\b\\s{1,3})($NAME)"),
    )
}

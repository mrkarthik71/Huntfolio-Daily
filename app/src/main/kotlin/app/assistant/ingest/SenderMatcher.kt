package app.assistant.ingest

import app.assistant.domain.model.SenderEntry

/**
 * Matches an SMS sender ID against the Bank_Sender_List (Requirements 26.1, 26.3, 26.4). The whole
 * sender ID must match: an optional prefix, the core header, and an optional suffix, ignoring
 * letter case. A look-alike such as `FAKEHDFCBK`, `HDFCBK2` or `XX-HDFCBK-SSS` is not a match,
 * and an entry with no prefix or suffix pattern accepts nothing around its core header (design:
 * threat "look-alike sender ID"). Pure: the list is data kept apart from parser code.
 */
object SenderMatcher {
    /** A sender ID is at most 11 characters, or a phone number; far longer input is never a bank. */
    private const val MAX_SENDER_LENGTH = 40

    fun matches(senderId: String, list: List<SenderEntry>): Boolean {
        val id = senderId.trim()
        return id.isNotEmpty() && id.length <= MAX_SENDER_LENGTH && list.any { regexFor(it).matches(id) }
    }

    private fun regexFor(entry: SenderEntry): Regex {
        val prefix = entry.prefixPattern?.let { "(?:${patternSource(it)})?" }.orEmpty()
        val suffix = entry.suffixPattern?.let { "(?:${patternSource(it)})?" }.orEmpty()
        return Regex(prefix + Regex.escape(entry.coreHeader) + suffix, RegexOption.IGNORE_CASE)
    }

    private fun patternSource(pattern: String): String = pattern.map { if (it == '?') "[A-Za-z0-9]" else Regex.escape(it.toString()) }.joinToString("")
}

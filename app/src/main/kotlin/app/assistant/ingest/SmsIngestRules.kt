package app.assistant.ingest

import java.time.LocalDate

/**
 * Pure rules for Phase 3's SMS ingest (Requirements 26.2, 26.7). No clock access: the caller
 * supplies the date or the parts.
 */
object SmsIngestRules {

    /**
     * Where the SMS_History_Import starts, once, when the User first enables SMS reading on
     * [enabledOn]: the same day of the month three calendar months earlier, or the last day of
     * that month when the day does not exist (Requirement 26.2, design Property 32). `minusMonths`
     * already clamps a short month this way (31 May minus 3 months is 28 or 29 February), so this
     * is the rule stated directly rather than reimplemented.
     */
    fun historyImportStart(enabledOn: LocalDate): LocalDate = enabledOn.minusMonths(HISTORY_MONTHS)

    /**
     * Joins multipart SMS parts, delivered in any order, into the original Message_Text in part
     * order (Requirement 26.7, design Property 33). [parts] pairs each part's 0-based index with
     * its text; a gap or a repeated index is the caller's bug, not ours, and is rejected rather
     * than silently joining the wrong text.
     */
    fun joinParts(parts: List<Pair<Int, String>>): String {
        require(parts.isNotEmpty()) { "no SMS parts to join" }
        val byIndex = parts.toMap()
        require(byIndex.size == parts.size) { "a part index was delivered more than once" }
        require(byIndex.keys == (0 until parts.size).toSet()) { "SMS part indices must be exactly 0 until the part count, with no gap" }
        return (0 until parts.size).joinToString("") { byIndex.getValue(it) }
    }

    private const val HISTORY_MONTHS = 3L
}

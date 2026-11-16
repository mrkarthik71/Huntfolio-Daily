package app.assistant.domain.engine.ask

import java.time.LocalDateTime

/**
 * Tidies what a speech recogniser writes (or a hurried hand types) into the shapes Ask's rules read:
 * "Remind me to call Amma at 6 p.m." → "Remind me to call Amma at 6 pm",
 * "spent rupees 450 on lunch." → "spent 450 on lunch". Meaning is never changed, only spelling.
 */
object SpokenText {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val pm = Regex("\\bp\\.\\s?m\\.?(?=\\s|$)", opt)
    private val am = Regex("\\ba\\.\\s?m\\.?(?=\\s|$)", opt)
    private val oClock = Regex("\\s*o'?\\s?clock\\b", opt)
    private val rupeesBefore = Regex("\\b(?:rupees?|rs\\.?|inr)\\s*(?=\\d)", opt)
    private val rupeesAfter = Regex("(?<=\\d)\\s*(?:rupees?|rs\\.?|/-)(?=\\s|$)", opt)
    private val rupeeSign = Regex("₹\\s*(?=\\d)")
    private val thousands = Regex("(?<=\\d),(?=\\d{3}\\b)")
    private val spaces = Regex("\\s+")
    private val half = Regex("\\bhalf past (\\d{1,2})\\b", opt)
    private val quarterPast = Regex("\\bquarter past (\\d{1,2})\\b", opt)

    fun normalise(raw: String): String {
        var s = raw.trim().trimEnd('.', '!', '?', ',').trim()
        s = pm.replace(s, "pm")
        s = am.replace(s, "am")
        s = oClock.replace(s, "")
        s = half.replace(s) { "${it.groupValues[1]}:30" }
        s = quarterPast.replace(s) { "${it.groupValues[1]}:15" }
        s = rupeeSign.replace(s, "")
        s = rupeesBefore.replace(s, "")
        s = rupeesAfter.replace(s, "")
        s = thousands.replace(s, "")
        return spaces.replace(s, " ").trim()
    }

    /**
     * From the recogniser's guesses, the first one Ask can act on or answer by rules; otherwise the
     * first guess. So "add milk to grocery" wins over a mis-heard "admin to grocery".
     */
    fun best(candidates: List<String>, now: LocalDateTime): String? {
        val tidy = candidates.map(::normalise).filter { it.isNotBlank() }
        return tidy.firstOrNull { AskActions.parse(it, now.toLocalDate()) != null }
            ?: tidy.firstOrNull { AskParser.parse(it, now).intent != AskIntent.UNKNOWN }
            ?: tidy.firstOrNull()
    }
}

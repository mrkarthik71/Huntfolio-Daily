package app.assistant.domain.engine.mail

import app.assistant.domain.model.MailHeader

/**
 * A single Shortlist_Rule (Requirement 53): a sender/subject pattern and the rule score it adds when
 * it matches. Rules are user-editable with limits; an empty rule set shortlists nothing (Requirement
 * 53.5), so the mailbox stays silent until the user adds a rule.
 */
data class ShortlistRule(
    val id: Long,
    /** Matches the From address (substring, case-insensitive). Blank = any sender. */
    val senderContains: String,
    /** Matches the Subject (substring, case-insensitive). Blank = any subject. */
    val subjectContains: String,
    /** Added to the Rule_Score when this rule matches (Requirement 54.2). */
    val score: Int,
) {
    fun matches(header: MailHeader): Boolean {
        val senderOk = senderContains.isBlank() || header.from.contains(senderContains, ignoreCase = true)
        val subjectOk = subjectContains.isBlank() || header.subject.contains(subjectContains, ignoreCase = true)
        // A rule with both blank would match everything, which the editor forbids; guard anyway.
        if (senderContains.isBlank() && subjectContains.isBlank()) return false
        return senderOk && subjectOk
    }
}

/**
 * Mail triage (Requirement 52 to 55, design: Mail_Triage). Pure functions: whether an email is an
 * OTP to discard, whether it is shortlisted for AI exposure, its deterministic Rule_Score, and how a
 * Sender_Penalty lowers the final importance. The AI classifier only ever sees shortlisted mail
 * (Requirement 54.4), so an email no rule matches never reaches a provider.
 */
object MailTriage {
    /** An OTP/verification email that must be discarded with no snippet and no AI (Requirement 54.1). */
    fun isOtp(header: MailHeader, otpPatterns: List<Regex>): Boolean = otpPatterns.any { it.containsMatchIn(header.subject) || it.containsMatchIn(header.from) }

    /** True when at least one enabled rule matches (Requirement 53.1). Empty rules shortlist nothing. */
    fun shortlisted(header: MailHeader, rules: List<ShortlistRule>): Boolean = rules.any { it.matches(header) }

    /** The deterministic sum of matching rule scores (Requirement 54.2, 54.3). Order-independent. */
    fun ruleScore(header: MailHeader, rules: List<ShortlistRule>): Int = rules.filter { it.matches(header) }.sumOf { it.score }

    /**
     * The final importance after a Sender_Penalty (Requirement 55.5, 55.6): the base score minus the
     * penalty, clamped to zero. A sender the user marked "not important" is pushed below the
     * threshold without losing the record of why.
     */
    fun applyPenalty(baseScore: Int, senderPenalty: Int): Int = (baseScore - senderPenalty).coerceAtLeast(0)

    /** The highest score a rule or the classifier may contribute, so scores stay comparable. */
    const val MAX_SCORE = 10

    /**
     * Built-in shortlist rules, used together with the User's own, so a fresh connection already
     * keeps the mail that matters (subject words only; ids are negative so they never clash).
     * Interviews and deadlines score above the default Importance_Threshold (6), so they notify;
     * bills and payments sit at the threshold; orders, bookings and receipts are kept quietly.
     */
    val DEFAULT_RULES: List<ShortlistRule> = listOf(
        "interview" to 9, "offer letter" to 9, "action required" to 7, "deadline" to 7, "last date" to 7, "appointment" to 6,
        "payment due" to 6, "bill" to 6, "invoice" to 6, "statement" to 5, "emi" to 6, "premium due" to 6, "renewal" to 5,
        "subscription" to 5, "receipt" to 4, "payment" to 4, "order" to 3, "shipped" to 3, "delivered" to 3, "booking" to 4,
        "ticket" to 4, "pnr" to 4, "itinerary" to 4, "meeting" to 4, "reminder" to 4,
    ).mapIndexed { i, (word, score) -> ShortlistRule(-(i + 1L), "", word, score) }
}

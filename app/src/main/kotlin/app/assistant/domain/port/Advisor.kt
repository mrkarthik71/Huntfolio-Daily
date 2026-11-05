package app.assistant.domain.port

import app.assistant.domain.model.PromptType

/** One handled item in an AI payload: an opaque handle with its (to-be-redacted) label, e.g. t1 -> "Pay the electricity bill". */
data class AdviceItem(val handle: String, val label: String)

/** A free time window the daily plan may place tasks into, in minutes from midnight. */
data class AdviceGap(val handle: String, val startMinute: Int, val endMinute: Int)

/** Facts for a planning/review payload: handled items, numeric facts, and optional free gaps. */
data class ReviewFacts(
    val items: List<AdviceItem> = emptyList(),
    val gaps: List<AdviceGap> = emptyList(),
    /** Numeric/opaque facts already safe to send (counts, totals in ₹, streak days). */
    val numbers: Map<String, String> = emptyMap(),
    val summaryLine: String = "",
)

/** Facts for a single-message payload (mail classifier, SMS fallback): the message text goes through redaction first. */
data class MessageFacts(val handle: String, val rawText: String, val numbers: Map<String, String> = emptyMap())

/** One validated AI suggestion with the reason it was made. */
data class AdviceSuggestion(val text: String, val reason: String)

/** Why AI wording is not available. [DISABLED] is the normal "AI off" state and shows no indication. */
enum class AdviceUnavailable { DISABLED, OFFLINE, CAP_REACHED, NOT_CONFIGURED, FAILED, PAYLOAD_SKIPPED, NO_PROMPT }

/** AI wording for a plan or review, shown after the rule-based result (Requirement 48.1). */
sealed interface ReviewAdvice {
    data class Wording(val summary: String?, val suggestions: List<AdviceSuggestion>) : ReviewAdvice

    data class Unavailable(val reason: AdviceUnavailable) : ReviewAdvice
}

/** The AI mail classification for one shortlisted message (Requirement 55). */
sealed interface MailAdvice {
    data class Classified(val score: Int, val category: String) : MailAdvice

    data class Unavailable(val reason: AdviceUnavailable) : MailAdvice
}

/** The AI SMS fallback proposal for a Review_Queue item (Requirement 49). */
sealed interface SmsAdvice {
    /** A validated proposal, stored as canonical JSON until the User accepts or edits it. */
    data class Proposal(val json: String) : SmsAdvice

    /** The AI answered but not with an SMS proposal. */
    data object Unusable : SmsAdvice

    data class Unavailable(val reason: AdviceUnavailable) : SmsAdvice
}

/**
 * The single entry point features use for AI wording and judgment (task 30.2). It is called after the
 * rule-based result is shown, never throws and never blocks it; when AI is off it answers Unavailable
 * with [AdviceUnavailable.DISABLED] at once with no network. Implemented in the ai layer.
 */
interface Advisor {
    suspend fun review(type: PromptType, facts: ReviewFacts): ReviewAdvice

    suspend fun classifyMail(facts: MessageFacts): MailAdvice

    suspend fun parseSms(facts: MessageFacts): SmsAdvice

    companion object {
        /** An advisor for AI mode None (and tests): every call is unavailable with [AdviceUnavailable.DISABLED]. */
        val Disabled: Advisor = object : Advisor {
            override suspend fun review(type: PromptType, facts: ReviewFacts): ReviewAdvice = ReviewAdvice.Unavailable(AdviceUnavailable.DISABLED)

            override suspend fun classifyMail(facts: MessageFacts): MailAdvice = MailAdvice.Unavailable(AdviceUnavailable.DISABLED)

            override suspend fun parseSms(facts: MessageFacts): SmsAdvice = SmsAdvice.Unavailable(AdviceUnavailable.DISABLED)
        }
    }
}

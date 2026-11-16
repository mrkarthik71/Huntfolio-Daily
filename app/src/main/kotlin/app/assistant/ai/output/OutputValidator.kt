package app.assistant.ai.output

import app.assistant.ai.numbers.NumberTokens
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.PromptType
import kotlinx.serialization.SerializationException

/** Why an AI output was rejected (Requirement 47). Any of these makes the feature use the rule-based result. */
enum class OutputError {
    /** Not a single JSON object, or an unknown key, or a type mismatch (Requirement 47.5). */
    MALFORMED,

    /** A text field is longer than its limit, or there are more than three suggestions (47.6, 47.7). */
    TOO_LONG,

    /** The mail score is outside 0..10, or the category is not one of the known set (47.6). */
    OUT_OF_RANGE,

    /** A number in the output does not appear in the payload (Requirement 47.3, Property 64). */
    NUMBER_NOT_IN_PAYLOAD,

    /** An item handle is not one the payload offered (Requirement 47.10, Property 66). */
    UNKNOWN_HANDLE,

    /** A daily-plan placement falls outside the payload's Free_Gaps (Requirement 48.6). */
    OUTSIDE_FREE_GAPS,

    /** The output gives restricted advice (investment/medical/legal) (Requirement 47.4). */
    RESTRICTED_ADVICE,

    /** The output contains a phone number, email, UPI id or account number (Requirement 47.9). */
    CONTACT_DETAILS,
}

/** The payload facts the validator checks an output against. Built by the Summary_Builder alongside the payload. */
data class OutputContext(
    /** Opaque handles the payload offered (task/goal/gap/item ids). */
    val knownHandles: Set<String>,
    /** The full redacted payload text, for number-provenance checks. */
    val payloadText: String,
    /** Free gaps as [startMinute, endMinute) windows (daily plan only). */
    val freeGaps: List<IntRange> = emptyList(),
)

/**
 * Decodes an AI response strictly and checks it against its schema and the payload (Requirement 47,
 * design: Output_Validator). On any violation it returns an [OutputError] and the caller discards
 * the response; it never repairs or partially accepts output.
 */
class OutputValidator(private val restrictedAdvice: List<Regex>) {

    fun validate(type: PromptType, json: String, context: OutputContext): Outcome<ValidatedOutput, OutputError> = try {
        when (type) {
            PromptType.DAILY_PLAN -> dailyPlan(strictJson.decodeFromString(DailyPlanOutput.serializer(), json), context)
            PromptType.EVENING_REVIEW -> strictJson.decodeFromString(EveningReviewOutput.serializer(), json).let {
                review(it.summary, it.suggestions, type, context)
            }
            PromptType.WEEKLY_REVIEW -> strictJson.decodeFromString(WeeklyReviewOutput.serializer(), json).let {
                review(it.summary, it.suggestions, type, context)
            }
            PromptType.MONTHLY_MONEY_REVIEW -> strictJson.decodeFromString(MonthlyMoneyReviewOutput.serializer(), json).let {
                review(it.summary, it.suggestions, type, context)
            }
            PromptType.ASSISTANT_ANSWER -> strictJson.decodeFromString(AssistantAnswerOutput.serializer(), json).let {
                review(it.summary, it.suggestions, type, context)
            }
            PromptType.GOAL_BREAKDOWN -> breakdown(strictJson.decodeFromString(GoalBreakdownOutput.serializer(), json), context)
            PromptType.MAIL_CLASSIFIER -> mail(strictJson.decodeFromString(MailClassifierOutput.serializer(), json), context)
            PromptType.SMS_FALLBACK_PARSER -> sms(strictJson.decodeFromString(SmsFallbackOutput.serializer(), json), context)
        }
    } catch (_: SerializationException) {
        Outcome.Err(OutputError.MALFORMED)
    } catch (_: IllegalArgumentException) {
        Outcome.Err(OutputError.MALFORMED)
    }

    @Suppress("ReturnCount") // a validator rejects at the first failing rule
    private fun dailyPlan(dto: DailyPlanOutput, ctx: OutputContext): Outcome<ValidatedOutput, OutputError> {
        checkText(dto.summary, OutputLimits.MAX_SUMMARY, ctx)?.let { return Outcome.Err(it) }
        suggestionError(dto.suggestions, ctx)?.let { return Outcome.Err(it) }
        if (dto.placements.size > MAX_PLACEMENTS) return Outcome.Err(OutputError.TOO_LONG)
        for (p in dto.placements) {
            if (p.taskHandle !in ctx.knownHandles) return Outcome.Err(OutputError.UNKNOWN_HANDLE)
            if (p.endMinute <= p.startMinute) return Outcome.Err(OutputError.MALFORMED)
            if (ctx.freeGaps.none { p.startMinute >= it.first && p.endMinute <= it.last }) return Outcome.Err(OutputError.OUTSIDE_FREE_GAPS)
        }
        return Outcome.Ok(ValidatedOutput.DailyPlan(dto))
    }

    private fun review(summary: String, suggestions: List<SuggestionDto>, type: PromptType, ctx: OutputContext): Outcome<ValidatedOutput, OutputError> {
        checkText(summary, OutputLimits.MAX_SUMMARY, ctx)?.let { return Outcome.Err(it) }
        suggestionError(suggestions, ctx)?.let { return Outcome.Err(it) }
        return Outcome.Ok(ValidatedOutput.Review(type, summary, suggestions))
    }

    @Suppress("ReturnCount") // a validator rejects at the first failing rule
    private fun breakdown(dto: GoalBreakdownOutput, ctx: OutputContext): Outcome<ValidatedOutput, OutputError> {
        if (dto.summary.isNotEmpty()) checkText(dto.summary, OutputLimits.MAX_SUMMARY, ctx)?.let { return Outcome.Err(it) }
        if (dto.steps.isEmpty()) return Outcome.Err(OutputError.MALFORMED)
        if (dto.steps.size > OutputLimits.MAX_STEPS) return Outcome.Err(OutputError.TOO_LONG)
        for (s in dto.steps) {
            if (s.goal !in ctx.knownHandles) return Outcome.Err(OutputError.UNKNOWN_HANDLE)
            if (s.size !in OutputLimits.STEP_SIZES) return Outcome.Err(OutputError.OUT_OF_RANGE)
            if (s.title.isBlank()) return Outcome.Err(OutputError.MALFORMED)
            checkText(s.title, OutputLimits.MAX_ONE_LINE, ctx)?.let { return Outcome.Err(it) }
        }
        return Outcome.Ok(ValidatedOutput.Breakdown(dto))
    }

    @Suppress("ReturnCount") // a validator rejects at the first failing rule
    private fun mail(dto: MailClassifierOutput, ctx: OutputContext): Outcome<ValidatedOutput, OutputError> {
        if (dto.itemHandle !in ctx.knownHandles) return Outcome.Err(OutputError.UNKNOWN_HANDLE)
        if (dto.score < OutputLimits.MAIL_SCORE_MIN || dto.score > OutputLimits.MAIL_SCORE_MAX) return Outcome.Err(OutputError.OUT_OF_RANGE)
        if (dto.category !in OutputLimits.MAIL_CATEGORIES) return Outcome.Err(OutputError.OUT_OF_RANGE)
        checkText(dto.oneLine, OutputLimits.MAX_ONE_LINE, ctx)?.let { return Outcome.Err(it) }
        return Outcome.Ok(ValidatedOutput.Mail(dto))
    }

    private fun sms(dto: SmsFallbackOutput, ctx: OutputContext): Outcome<ValidatedOutput, OutputError> {
        if (dto.type !in OutputLimits.SMS_TYPES) return Outcome.Err(OutputError.OUT_OF_RANGE)
        // The amount must be a figure that was actually in the redacted text (Requirement 49.4).
        if (!NumberTokens.allPresent(dto.amountText, ctx.payloadText)) return Outcome.Err(OutputError.NUMBER_NOT_IN_PAYLOAD)
        checkText(dto.merchant, OutputLimits.MAX_SHORT, ctx)?.let { return Outcome.Err(it) }
        return Outcome.Ok(ValidatedOutput.Sms(dto))
    }

    private fun suggestionError(list: List<SuggestionDto>, ctx: OutputContext): OutputError? {
        if (list.size > OutputLimits.MAX_SUGGESTIONS) return OutputError.TOO_LONG
        for (s in list) {
            checkText(s.text, OutputLimits.MAX_SHORT, ctx)?.let { return it }
            checkText(s.reason, OutputLimits.MAX_SHORT, ctx)?.let { return it }
        }
        return null
    }

    /** Length, number-provenance, restricted-advice and contact checks every free-text field must pass. */
    @Suppress("ReturnCount") // a validator rejects at the first failing rule
    private fun checkText(text: String, max: Int, ctx: OutputContext): OutputError? {
        if (text.length > max) return OutputError.TOO_LONG
        if (!NumberTokens.allPresent(text, ctx.payloadText)) return OutputError.NUMBER_NOT_IN_PAYLOAD
        if (restrictedAdvice.any { it.containsMatchIn(text) }) return OutputError.RESTRICTED_ADVICE
        if (CONTACT_PATTERNS.any { it.containsMatchIn(text) }) return OutputError.CONTACT_DETAILS
        return null
    }

    companion object {
        private const val MAX_PLACEMENTS = 20

        /**
         * Contact-detail patterns the model must never emit (Requirement 47.9). These mirror the
         * Redactor's intent but are deliberately independent: the Redactor removes contacts from
         * input, this rejects an output that tries to produce one.
         */
        private val CONTACT_PATTERNS: List<Regex> = listOf(
            Regex("""(?<![\d+])(?:\+?91[ -]?|0)?[6-9]\d{4}[ -]?\d{5}(?!\d)"""),
            Regex("""[a-z0-9][a-z0-9._%+-]{0,60}@[a-z0-9-]{1,63}(?:\.[a-z0-9-]{1,63})+""", RegexOption.IGNORE_CASE),
            Regex("""[a-z0-9][a-z0-9.\-_]{1,60}@[a-z]{2,30}""", RegexOption.IGNORE_CASE),
            Regex("""(?<!\d)\d(?:[ -]?\d){8,18}(?!\d)"""),
        )
    }
}

/** The accepted output in a typed form the feature code consumes. */
sealed interface ValidatedOutput {
    data class DailyPlan(val output: DailyPlanOutput) : ValidatedOutput
    data class Review(val type: PromptType, val summary: String, val suggestions: List<SuggestionDto>) : ValidatedOutput
    data class Mail(val output: MailClassifierOutput) : ValidatedOutput
    data class Breakdown(val output: GoalBreakdownOutput) : ValidatedOutput
    data class Sms(val output: SmsFallbackOutput) : ValidatedOutput
}

/** Re-encodes a validated output to canonical JSON for storage or round-trip tests (Requirement 47; Property 63). */
object OutputSerializer {
    fun toJson(output: ValidatedOutput): String = when (output) {
        is ValidatedOutput.DailyPlan -> strictJson.encodeToString(DailyPlanOutput.serializer(), output.output)
        is ValidatedOutput.Review -> when (output.type) {
            PromptType.WEEKLY_REVIEW -> strictJson.encodeToString(WeeklyReviewOutput.serializer(), WeeklyReviewOutput(output.summary, output.suggestions))
            PromptType.MONTHLY_MONEY_REVIEW -> strictJson.encodeToString(
                MonthlyMoneyReviewOutput.serializer(),
                MonthlyMoneyReviewOutput(output.summary, output.suggestions),
            )
            PromptType.ASSISTANT_ANSWER -> strictJson.encodeToString(
                AssistantAnswerOutput.serializer(),
                AssistantAnswerOutput(output.summary, output.suggestions),
            )
            else -> strictJson.encodeToString(EveningReviewOutput.serializer(), EveningReviewOutput(output.summary, suggestions = output.suggestions))
        }
        is ValidatedOutput.Mail -> strictJson.encodeToString(MailClassifierOutput.serializer(), output.output)
        is ValidatedOutput.Breakdown -> strictJson.encodeToString(GoalBreakdownOutput.serializer(), output.output)
        is ValidatedOutput.Sms -> strictJson.encodeToString(SmsFallbackOutput.serializer(), output.output)
    }
}

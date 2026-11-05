package app.assistant.ai.feature

import app.assistant.ai.gateway.AiGateway
import app.assistant.ai.output.SuggestionDto
import app.assistant.ai.output.ValidatedOutput
import app.assistant.ai.payload.SummaryBuilder
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.AiFailure

/**
 * The single entry point features use for AI wording (task 30.2). It is called AFTER the rule-based
 * result is already shown, builds the payload with the [SummaryBuilder], runs it through the
 * [AiGateway], and returns typed [Advice] on success or an [Unavailable] reason the screen turns
 * into the "AI wording unavailable" indication. It never throws and never blocks the rule-based
 * result; when AI is off (mode None) it returns [Unavailable.Disabled] at once with no network.
 */
class FeatureAdvisor(
    private val gateway: AiGateway,
    private val builder: SummaryBuilder,
    private val prompt: (PromptType) -> String?,
) {
    sealed interface Result {
        data class Advice(val output: ValidatedOutput) : Result
        data class Unavailable(val reason: Reason) : Result
    }

    enum class Reason { DISABLED, OFFLINE, CAP_REACHED, NOT_CONFIGURED, FAILED, PAYLOAD_SKIPPED, NO_PROMPT }

    /** Daily plan / evening / weekly / monthly reviews: handled items + numbers + (plan only) free gaps. */
    suspend fun review(type: PromptType, facts: SummaryBuilder.ReviewFacts): Result {
        val prompt = prompt(type) ?: return Result.Unavailable(Reason.NO_PROMPT)
        val built = when (val b = builder.buildReview(type, facts)) {
            is Outcome.Ok -> b.value
            is Outcome.Err -> return Result.Unavailable(Reason.PAYLOAD_SKIPPED)
        }
        return run(type, built.payload, prompt, built.context)
    }

    /** Mail classifier / SMS fallback: a single message in the payload's data field. */
    suspend fun message(type: PromptType, facts: SummaryBuilder.MessageFacts): Result {
        val prompt = prompt(type) ?: return Result.Unavailable(Reason.NO_PROMPT)
        val built = when (val b = builder.buildMessage(type, facts)) {
            is Outcome.Ok -> b.value
            is Outcome.Err -> return Result.Unavailable(Reason.PAYLOAD_SKIPPED)
        }
        return run(type, built.payload, prompt, built.context)
    }

    private suspend fun run(type: PromptType, payload: app.assistant.domain.port.RedactedPayload, prompt: String, context: app.assistant.ai.output.OutputContext): Result =
        when (val r = gateway.run(type, payload, prompt, context)) {
            is Outcome.Ok -> Result.Advice(r.value.output)
            is Outcome.Err -> Result.Unavailable(map(r.error))
        }

    private fun map(failure: AiFailure): Reason = when (failure) {
        AiFailure.DISABLED -> Reason.DISABLED
        AiFailure.OFFLINE -> Reason.OFFLINE
        AiFailure.CAP_REACHED -> Reason.CAP_REACHED
        AiFailure.NOT_CONFIGURED -> Reason.NOT_CONFIGURED
        else -> Reason.FAILED
    }

    companion object {
        /** Convenience: pull the suggestions out of a review/plan advice for the Suggestions surface. */
        fun suggestionsOf(output: ValidatedOutput): List<SuggestionDto> = when (output) {
            is ValidatedOutput.DailyPlan -> output.output.suggestions
            is ValidatedOutput.Review -> output.suggestions
            else -> emptyList()
        }
    }
}

package app.assistant.ai.feature

import app.assistant.ai.gateway.AiGateway
import app.assistant.ai.output.OutputContext
import app.assistant.ai.output.OutputSerializer
import app.assistant.ai.output.SuggestionDto
import app.assistant.ai.output.ValidatedOutput
import app.assistant.ai.payload.SummaryBuilder
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.AdviceSuggestion
import app.assistant.domain.port.AdviceUnavailable
import app.assistant.domain.port.Advisor
import app.assistant.domain.port.AiFailure
import app.assistant.domain.port.MailAdvice
import app.assistant.domain.port.MessageFacts
import app.assistant.domain.port.RedactedPayload
import app.assistant.domain.port.ReviewAdvice
import app.assistant.domain.port.ReviewFacts
import app.assistant.domain.port.SmsAdvice

/**
 * The real [Advisor] (task 30.2): builds the redacted payload with the [SummaryBuilder], runs it
 * through the [AiGateway], and maps the validated output to domain advice. It never throws; any
 * failure becomes an Unavailable reason the screen turns into the "AI wording unavailable" line.
 */
class GatewayAdvisor(
    private val gateway: AiGateway,
    private val builder: SummaryBuilder,
    private val prompt: (PromptType) -> String?,
) : Advisor {

    override suspend fun review(type: PromptType, facts: ReviewFacts): ReviewAdvice {
        val prompt = prompt(type) ?: return ReviewAdvice.Unavailable(AdviceUnavailable.NO_PROMPT)
        val built = when (val b = builder.buildReview(type, facts)) {
            is Outcome.Ok -> b.value
            is Outcome.Err -> return ReviewAdvice.Unavailable(AdviceUnavailable.PAYLOAD_SKIPPED)
        }
        return when (val r = run(type, built.payload, prompt, built.context)) {
            is Outcome.Ok -> when (val out = r.value) {
                is ValidatedOutput.DailyPlan -> ReviewAdvice.Wording(out.output.summary, out.output.suggestions.map(::suggestion))
                is ValidatedOutput.Review -> ReviewAdvice.Wording(out.summary, out.suggestions.map(::suggestion))
                else -> ReviewAdvice.Unavailable(AdviceUnavailable.FAILED)
            }
            is Outcome.Err -> ReviewAdvice.Unavailable(r.error)
        }
    }

    override suspend fun classifyMail(facts: MessageFacts): MailAdvice {
        val type = PromptType.MAIL_CLASSIFIER
        val prompt = prompt(type) ?: return MailAdvice.Unavailable(AdviceUnavailable.NO_PROMPT)
        val built = when (val b = builder.buildMessage(type, facts)) {
            is Outcome.Ok -> b.value
            is Outcome.Err -> return MailAdvice.Unavailable(AdviceUnavailable.PAYLOAD_SKIPPED)
        }
        return when (val r = run(type, built.payload, prompt, built.context)) {
            is Outcome.Ok -> (r.value as? ValidatedOutput.Mail)?.output
                ?.let { MailAdvice.Classified(it.score, it.category) }
                ?: MailAdvice.Unavailable(AdviceUnavailable.FAILED)
            is Outcome.Err -> MailAdvice.Unavailable(r.error)
        }
    }

    override suspend fun parseSms(facts: MessageFacts): SmsAdvice {
        val type = PromptType.SMS_FALLBACK_PARSER
        val prompt = prompt(type) ?: return SmsAdvice.Unavailable(AdviceUnavailable.NO_PROMPT)
        val built = when (val b = builder.buildMessage(type, facts)) {
            is Outcome.Ok -> b.value
            is Outcome.Err -> return SmsAdvice.Unavailable(AdviceUnavailable.PAYLOAD_SKIPPED)
        }
        return when (val r = run(type, built.payload, prompt, built.context)) {
            // The amount in a validated proposal is already proven to be in the redacted text.
            is Outcome.Ok -> {
                val out = r.value
                if (out is ValidatedOutput.Sms) SmsAdvice.Proposal(OutputSerializer.toJson(out)) else SmsAdvice.Unusable
            }
            is Outcome.Err -> SmsAdvice.Unavailable(r.error)
        }
    }

    private suspend fun run(type: PromptType, payload: RedactedPayload, prompt: String, context: OutputContext): Outcome<ValidatedOutput, AdviceUnavailable> =
        when (val r = gateway.run(type, payload, prompt, context)) {
            is Outcome.Ok -> Outcome.Ok(r.value.output)
            is Outcome.Err -> Outcome.Err(map(r.error))
        }

    private fun suggestion(dto: SuggestionDto) = AdviceSuggestion(dto.text, dto.reason)

    private fun map(failure: AiFailure): AdviceUnavailable = when (failure) {
        AiFailure.DISABLED -> AdviceUnavailable.DISABLED
        AiFailure.OFFLINE -> AdviceUnavailable.OFFLINE
        AiFailure.CAP_REACHED -> AdviceUnavailable.CAP_REACHED
        AiFailure.NOT_CONFIGURED -> AdviceUnavailable.NOT_CONFIGURED
        else -> AdviceUnavailable.FAILED
    }
}

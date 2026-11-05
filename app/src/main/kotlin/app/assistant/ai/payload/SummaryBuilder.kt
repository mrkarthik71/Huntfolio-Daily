package app.assistant.ai.payload

import app.assistant.domain.port.MessageFacts
import app.assistant.domain.port.ReviewFacts
import app.assistant.ai.output.OutputContext
import app.assistant.domain.engine.redact.RedactionError
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.RedactedPayload
import app.assistant.domain.port.RedactedText

/**
 * Builds the [RedactedPayload] for each Prompt_Type from the feature's own facts (Requirement 46,
 * design: Summary_Builder). Each type has ONE fixed schema. Items are named only by opaque handles
 * (`t1`, `g2`, `gap3`), so a model never sees an internal id, and every free-text value is passed
 * through the Redactor. Message_Text appears only in the single `data` field of the SMS-fallback
 * and mail-classifier payloads (Requirement 46.1); nowhere else.
 *
 * A payload is SKIPPED (Outcome.Err) when any value fails redaction or the rendered payload exceeds
 * [MAX_PAYLOAD_CHARS] (Requirement 46.7); the caller then shows the rule-based result with no send.
 */
class SummaryBuilder(private val patterns: RedactionPatterns) {

    fun buildReview(type: PromptType, facts: ReviewFacts): Outcome<BuiltPayload, RedactionError> {
        require(type in REVIEW_TYPES) { "buildReview called for $type" }
        val fields = linkedMapOf<String, RedactedText>()
        val lists = linkedMapOf<String, List<RedactedText>>()
        val handles = linkedSetOf<String>()

        facts.numbers.forEach { (k, v) -> fields[k] = RedactedText.opaqueHandle(sanitizeNumber(v)) }
        if (facts.summaryLine.isNotBlank()) {
            when (val r = RedactedText.redact(facts.summaryLine, patterns)) {
                is Outcome.Ok -> fields["context"] = r.value
                is Outcome.Err -> return Outcome.Err(r.error)
            }
        }

        val itemTexts = mutableListOf<RedactedText>()
        for (item in facts.items) {
            handles += item.handle
            when (val r = RedactedText.redact("${item.handle}: ${item.label}", patterns)) {
                is Outcome.Ok -> itemTexts += r.value
                is Outcome.Err -> return Outcome.Err(r.error)
            }
        }
        if (itemTexts.isNotEmpty()) lists["items"] = itemTexts

        val gapRanges = mutableListOf<IntRange>()
        if (facts.gaps.isNotEmpty()) {
            val gapTexts = facts.gaps.map { g ->
                handles += g.handle
                gapRanges += g.startMinute..g.endMinute
                RedactedText.opaqueHandle(g.handle)
            }
            lists["free_gaps"] = gapTexts
            // The numeric window is itself a fact the model may echo; carry it as structured numbers.
            facts.gaps.forEachIndexed { i, g -> fields["gap${i}_start"] = RedactedText.opaqueHandle(g.startMinute.toString()); fields["gap${i}_end"] = RedactedText.opaqueHandle(g.endMinute.toString()) }
        }

        return finish(type, fields, lists, handles, gapRanges)
    }

    fun buildMessage(type: PromptType, facts: MessageFacts): Outcome<BuiltPayload, RedactionError> {
        require(type in MESSAGE_TYPES) { "buildMessage called for $type" }
        val fields = linkedMapOf<String, RedactedText>()
        val handles = linkedSetOf(facts.handle)
        fields["item"] = RedactedText.opaqueHandle(facts.handle)
        facts.numbers.forEach { (k, v) -> fields[k] = RedactedText.opaqueHandle(sanitizeNumber(v)) }
        // The ONLY place Message_Text is allowed, and only after redaction (Requirement 46.1).
        when (val r = RedactedText.redact(facts.rawText, patterns)) {
            is Outcome.Ok -> fields["data"] = r.value
            is Outcome.Err -> return Outcome.Err(r.error)
        }
        return finish(type, fields, emptyMap(), handles, emptyList())
    }

    private fun finish(
        type: PromptType,
        fields: Map<String, RedactedText>,
        lists: Map<String, List<RedactedText>>,
        handles: Set<String>,
        gaps: List<IntRange>,
    ): Outcome<BuiltPayload, RedactionError> {
        val rendered = render(fields, lists)
        if (rendered.length > MAX_PAYLOAD_CHARS) return Outcome.Err(RedactionError.TOO_LONG)
        val payload = RedactedPayload.of(type, SCHEMA_VERSION, fields, lists, rendered.length)
        val context = OutputContext(knownHandles = handles, payloadText = rendered, freeGaps = gaps)
        return Outcome.Ok(BuiltPayload(payload, context))
    }

    private fun render(fields: Map<String, RedactedText>, lists: Map<String, List<RedactedText>>): String {
        val sb = StringBuilder()
        fields.forEach { (k, v) -> sb.append(k).append('=').append(v.value).append('\n') }
        lists.forEach { (k, v) -> sb.append(k).append('=').append(v.joinToString("|") { it.value }).append('\n') }
        return sb.toString()
    }

    /** Keep only the characters an opaque numeric handle may contain, so a stray label never becomes a "number". */
    private fun sanitizeNumber(v: String): String = v.filter { it.isLetterOrDigit() || it in ".-%:_" }.ifBlank { "0" }

    companion object {
        const val MAX_PAYLOAD_CHARS = 4_000
        const val SCHEMA_VERSION = 1

        private val REVIEW_TYPES = setOf(PromptType.DAILY_PLAN, PromptType.EVENING_REVIEW, PromptType.WEEKLY_REVIEW, PromptType.MONTHLY_MONEY_REVIEW)
        private val MESSAGE_TYPES = setOf(PromptType.MAIL_CLASSIFIER, PromptType.SMS_FALLBACK_PARSER)
    }
}

/** A payload ready to send together with the context the Output_Validator checks the reply against. */
data class BuiltPayload(val payload: RedactedPayload, val context: OutputContext)

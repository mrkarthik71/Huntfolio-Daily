package app.assistant.domain.port

import app.assistant.domain.engine.redact.RedactionError
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.engine.redact.Redactor
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.PromptType

/**
 * The AI advice boundary (Requirements 42, 43, 46, 47; design: AI_Gateway / AI_Provider).
 *
 * Everything the app sends to an AI_Provider is a [RedactedPayload], and a [RedactedPayload] can
 * only hold [RedactedText] values, which in turn can only be minted by passing a string through the
 * [Redactor]. There is no other constructor, so no un-redacted text can reach a provider by
 * construction (Requirement 46.2 to 46.4). The gateway, not the provider, enforces the mode, the
 * cap, the breaker and the audit record; a provider only turns a payload into a result.
 */

/**
 * A piece of text that has been through the [Redactor]. The constructor is private, so the only way
 * to obtain one is [redactedTextOf] (or the always-safe [opaqueHandle] for a machine token such as
 * `t1`). Once held, [value] is the redacted string, never the original.
 */
@JvmInline
value class RedactedText private constructor(val value: String) {
    companion object {
        /**
         * Runs [raw] through the [Redactor] with [patterns]; a success yields a [RedactedText], a
         * failure yields the [RedactionError] so the caller can skip the item (Requirement 46.6).
         */
        fun redact(raw: String, patterns: RedactionPatterns): Outcome<RedactedText, RedactionError> = when (val r = Redactor.redact(raw, patterns)) {
            is Outcome.Ok -> Outcome.Ok(RedactedText(r.value))
            is Outcome.Err -> Outcome.Err(r.error)
        }

        /**
         * An opaque machine handle (`t1`, `g2`, `gap3`, a number, an enum name) that carries no
         * personal text and so needs no redaction. Reject anything with a letter-and-space shape
         * that could be a name slipping past as a "handle": handles are `[A-Za-z]+[0-9]+`, bare
         * numbers, or short upper-case tokens.
         */
        fun opaqueHandle(token: String): RedactedText {
            require(token.length <= MAX_HANDLE && HANDLE.matches(token)) { "not an opaque handle: '$token'" }
            return RedactedText(token)
        }

        /** A value already produced by the Redactor elsewhere (e.g. a stored Review_Queue text). */
        fun ofAlreadyRedacted(value: String): RedactedText = RedactedText(value)

        private const val MAX_HANDLE = 40
        private val HANDLE = Regex("""[A-Za-z][A-Za-z0-9_]*\d|\d+|[A-Z][A-Z0-9_]{0,38}|-?\d+(\.\d+)?|true|false""")
    }
}

/** Convenience for the common redaction call. */
fun redactedTextOf(raw: String, patterns: RedactionPatterns): Outcome<RedactedText, RedactionError> = RedactedText.redact(raw, patterns)

/**
 * The fully-built, redaction-safe request body for one AI call. Its [fields] are a fixed schema per
 * [promptType] (built by the Summary_Builder) and every value is a [RedactedText], so the payload
 * cannot carry raw personal text. [sizeChars] is the rendered length the gateway checks against the
 * 4,000-character limit (Requirement 46.7).
 */
class RedactedPayload private constructor(
    val promptType: PromptType,
    val schemaVersion: Int,
    val fields: Map<String, RedactedText>,
    val lists: Map<String, List<RedactedText>>,
    val sizeChars: Int,
) {
    companion object {
        /**
         * The only way to build a payload. Every value is already a [RedactedText]; the builder only
         * assembles them, so it cannot introduce un-redacted text. [sizeChars] is supplied by the
         * Summary_Builder, which renders the payload once and measures it.
         */
        fun of(
            promptType: PromptType,
            schemaVersion: Int,
            fields: Map<String, RedactedText>,
            lists: Map<String, List<RedactedText>> = emptyMap(),
            sizeChars: Int,
        ): RedactedPayload = RedactedPayload(promptType, schemaVersion, fields, lists, sizeChars)
    }
}

/** A single request handed to a provider: the payload plus the rendered prompt text for its type. */
data class ProviderRequest(
    val promptType: PromptType,
    val payload: RedactedPayload,
    /** The versioned prompt body for [promptType] (task 30.1); the provider sends payload + prompt. */
    val prompt: String,
    /** Per-Prompt_Type model name chosen in settings; null lets the adapter use its default. */
    val model: String?,
)

/** Why an AI attempt did not yield usable, validated output. The feature falls back to the rule-based result. */
enum class AiFailure {
    /** AI_Mode is None, so nothing is attempted (Requirement 42.2). */
    DISABLED,

    /** The device is offline and the mode is Cloud. */
    OFFLINE,

    /** The circuit breaker is open after repeated failures (Requirement 43.4). */
    BREAKER_OPEN,

    /** The monthly cap is reached; the caller shows the resume date (Requirement 45.4). */
    CAP_REACHED,

    /** No cap (or, in `personal`, no key) is configured, so Cloud is refused (Requirement 45.1, 45.2). */
    NOT_CONFIGURED,

    /** The provider call timed out (30 seconds, Requirement 43.2). */
    TIMEOUT,

    /** The transport failed (no network reply, HTTP error). */
    TRANSPORT,

    /** The provider replied but the output failed strict validation (Requirement 47). */
    INVALID_OUTPUT,

    /** The on-device runtime is unavailable; never falls back to Cloud (Requirement 44.3, 44.4). */
    ON_DEVICE_UNAVAILABLE,

    /** The AI_Mode changed while the call was in flight; the stale result is dropped (Requirement 42.7). */
    MODE_CHANGED,
}

/**
 * The validated output of one AI call: the raw JSON the provider returned (already checked by the
 * Output_Validator before it becomes an [AiResult]) plus the attempt count used.
 */
data class AiResult(val promptType: PromptType, val json: String, val attempts: Int)

/**
 * Turns a [ProviderRequest] into raw JSON text. Implementations do nothing else: no mode, cap,
 * audit, retry or validation — those belong to the AI_Gateway. A provider must never log, store or
 * forward the payload anywhere but its single configured endpoint.
 */
interface AiProvider {
    val mode: AiMode

    /** Returns the provider's raw response text, or an [AiFailure] the gateway maps to a fallback. */
    suspend fun complete(request: ProviderRequest): Outcome<String, AiFailure>
}

/**
 * The AI_Mode None provider: attempts nothing and reserves no usage (Requirement 42.2). It is the
 * first-run default, so a fresh install never contacts a provider until the user turns AI on.
 */
object NoneProvider : AiProvider {
    override val mode: AiMode = AiMode.NONE

    override suspend fun complete(request: ProviderRequest): Outcome<String, AiFailure> = Outcome.Err(AiFailure.DISABLED)
}

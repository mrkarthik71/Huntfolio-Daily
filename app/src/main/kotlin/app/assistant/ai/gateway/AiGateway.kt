package app.assistant.ai.gateway

import app.assistant.ai.output.OutputContext
import app.assistant.ai.output.OutputValidator
import app.assistant.ai.output.ValidatedOutput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.AuditId
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.AiConfig
import app.assistant.domain.port.AiFailure
import app.assistant.domain.port.AiProvider
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.Connectivity
import app.assistant.domain.port.ProviderRequest
import app.assistant.domain.port.RedactedPayload
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The AI_Gateway (Requirement 42, 43, design: AI_Gateway). It is the ONLY place that decides whether
 * to contact a provider. It short-circuits for None, offline and an open breaker with ZERO attempts
 * and no usage; otherwise, per attempt, it reserves budget, writes the Audit_Log send row BEFORE the
 * send (and does not send if that write fails), bounds the call to 30 seconds, validates the reply,
 * and retries the SAME payload up to three times. A result whose mode epoch changed mid-flight is
 * dropped. The circuit breaker opens after three consecutive failures and stays open 30 minutes.
 */
class AiGateway(
    private val provider: () -> AiProvider,
    private val config: AiConfig,
    private val connectivity: Connectivity,
    private val cap: CapGovernor,
    private val audit: AuditLog,
    private val validator: OutputValidator,
    private val time: TimeSource,
    private val breaker: CircuitBreaker = CircuitBreaker(time),
) {
    data class Success(val output: ValidatedOutput, val attempts: Int)

    /**
     * Runs one feature request. [render] turns the payload into the prompt+body text the provider
     * sends; [context] is what the Output_Validator checks the reply against.
     */
    suspend fun run(type: PromptType, payload: RedactedPayload, prompt: String, context: OutputContext): Outcome<Success, AiFailure> {
        val mode = config.mode()
        if (mode == AiMode.NONE) return Outcome.Err(AiFailure.DISABLED)
        if (mode == AiMode.CLOUD && !connectivity.isOnline()) return Outcome.Err(AiFailure.OFFLINE)
        if (mode == AiMode.CLOUD && !config.cloudConfigured()) return Outcome.Err(AiFailure.NOT_CONFIGURED)
        if (breaker.isOpen()) return Outcome.Err(AiFailure.BREAKER_OPEN)

        val capPaise = config.capPaise()
        val startEpoch = config.epoch()
        var lastFailure: AiFailure = AiFailure.TRANSPORT

        for (attempt in 1..MAX_ATTEMPTS) {
            if (mode == AiMode.CLOUD) {
                if (cap.isCapReached(capPaise)) return Outcome.Err(AiFailure.CAP_REACHED)
                if (!cap.tryReserve(capPaise)) return Outcome.Err(AiFailure.CAP_REACHED)
            }
            val request = ProviderRequest(type, payload, prompt, config.model(type))
            val outcome = attemptOnce(mode, type, payload, request, context, startEpoch)
            if (mode == AiMode.CLOUD) cap.record()
            when (outcome) {
                is Outcome.Ok -> {
                    breaker.recordSuccess()
                    return Outcome.Ok(outcome.value)
                }
                is Outcome.Err -> {
                    lastFailure = outcome.error
                    // A mode change or on-device-unavailable is terminal; never fall back or retry.
                    if (outcome.error == AiFailure.MODE_CHANGED || outcome.error == AiFailure.ON_DEVICE_UNAVAILABLE) {
                        breaker.recordFailure()
                        return outcome
                    }
                }
            }
        }
        breaker.recordFailure()
        return Outcome.Err(lastFailure)
    }

    private suspend fun attemptOnce(
        mode: AiMode,
        type: PromptType,
        payload: RedactedPayload,
        request: ProviderRequest,
        context: OutputContext,
        startEpoch: Long,
    ): Outcome<Success, AiFailure> {
        // Audit the send BEFORE contacting the provider; a failed audit write means do not send.
        val auditId = when (val a = audit.recordAiSend(time.now(), mode, type, renderForAudit(payload))) {
            is Outcome.Ok -> a.value
            is Outcome.Err -> return Outcome.Err(AiFailure.TRANSPORT)
        }
        val raw = try {
            withTimeout(TIMEOUT_MS) { provider().complete(request) }
        } catch (_: TimeoutCancellationException) {
            audit.markAiResult(auditId, time.now(), success = false)
            return Outcome.Err(AiFailure.TIMEOUT)
        }
        // Drop a result whose mode/key epoch moved while the call was in flight (Requirement 42.7).
        if (config.epoch() != startEpoch) {
            audit.markAiResult(auditId, time.now(), success = false)
            return Outcome.Err(AiFailure.MODE_CHANGED)
        }
        return when (raw) {
            is Outcome.Ok -> validateReply(type, raw.value, context, auditId)
            is Outcome.Err -> {
                audit.markAiResult(auditId, time.now(), success = false)
                Outcome.Err(raw.error)
            }
        }
    }

    private suspend fun validateReply(type: PromptType, json: String, context: OutputContext, auditId: AuditId): Outcome<Success, AiFailure> =
        when (val v = validator.validate(type, json, context)) {
            is Outcome.Ok -> {
                audit.markAiResult(auditId, time.now(), success = true)
                Outcome.Ok(Success(v.value, attempts = 1))
            }
            is Outcome.Err -> {
                audit.markAiResult(auditId, time.now(), success = false)
                Outcome.Err(AiFailure.INVALID_OUTPUT)
            }
        }

    private fun renderForAudit(payload: RedactedPayload): String = buildString {
        payload.fields.forEach { (k, v) -> append(k).append('=').append(v.value).append('\n') }
        payload.lists.forEach { (k, v) -> append(k).append('=').append(v.joinToString("|") { it.value }).append('\n') }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val TIMEOUT_MS = 30_000L
    }
}

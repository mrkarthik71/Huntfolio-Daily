package app.assistant.ai.ondevice

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.port.AiFailure
import app.assistant.domain.port.AiProvider
import app.assistant.domain.port.ProviderRequest

/** Why the on-device model cannot run, surfaced in Settings (Requirement 44.2). */
enum class OnDeviceUnavailable {
    OS_TOO_OLD,
    NOT_ENOUGH_MEMORY,
    MODEL_NOT_PRESENT,
    RUNTIME_ERROR,
}

/** Whether the on-device runtime is usable, and why not when it is not. Implemented against the device. */
fun interface OnDeviceAvailability {
    /** Null when available; a reason when not. Proposed bar: Android 14+, 8 GB RAM, model present. */
    fun unavailableReason(): OnDeviceUnavailable?
}

/** Turns a redacted payload + prompt into the model's raw text, or throws on a runtime failure. */
fun interface OnDeviceRuntime {
    suspend fun generate(prompt: String, body: String): String
}

/**
 * The OnDevice AI provider (Requirement 44). It checks availability first and NEVER falls back to
 * Cloud on failure (Requirement 44.3, 44.4): an unavailable runtime or a generation error both
 * return an on-device failure, and the gateway treats that as terminal. It makes no network request
 * — a recording OkHttp client must see zero traffic in this mode (task 29.10).
 */
class OnDeviceAiProvider(
    private val availability: OnDeviceAvailability,
    private val runtime: OnDeviceRuntime,
) : AiProvider {
    override val mode: AiMode = AiMode.ON_DEVICE

    /** The last reason the model was unavailable, for the Settings display. */
    var lastUnavailable: OnDeviceUnavailable? = null
        private set

    override suspend fun complete(request: ProviderRequest): Outcome<String, AiFailure> {
        availability.unavailableReason()?.let {
            lastUnavailable = it
            return Outcome.Err(AiFailure.ON_DEVICE_UNAVAILABLE)
        }
        return try {
            Outcome.Ok(runtime.generate(request.prompt, renderPayload(request)))
        } catch (_: Exception) {
            lastUnavailable = OnDeviceUnavailable.RUNTIME_ERROR
            // Never a Cloud fallback: the gateway sees ON_DEVICE_UNAVAILABLE and stops.
            Outcome.Err(AiFailure.ON_DEVICE_UNAVAILABLE)
        }
    }

    private fun renderPayload(request: ProviderRequest): String =
        buildString {
            request.payload.fields.forEach { (k, v) -> append(k).append(": ").append(v.value).append('\n') }
            request.payload.lists.forEach { (k, v) -> append(k).append(":\n"); v.forEach { append("  - ").append(it.value).append('\n') } }
        }
}

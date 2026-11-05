package app.assistant.ai.ondevice

import app.assistant.ai.gateway.CapGovernor
import app.assistant.domain.port.AiUsage

/** [AiUsage] over the cap governor and the on-device provider, for the AI settings screen. */
class AiUsageReport(private val cap: CapGovernor, private val onDevice: OnDeviceAiProvider) : AiUsage {
    override suspend fun spentThisMonthPaise(): Long = cap.spent()

    override fun resumeDateIso(): String = cap.resumeDateIso()

    override fun onDeviceUnavailableReason(): String? = onDevice.lastUnavailable?.name
}

package app.assistant.data.ai

import app.assistant.domain.model.AiMode
import app.assistant.domain.model.PromptType
import app.assistant.domain.port.AiConfig
import app.assistant.domain.port.CloudCredential
import app.assistant.domain.port.CloudKeySource
import app.assistant.domain.port.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiConfig] backed by the settings store and the Cloud key source (design: AI_Gateway config). The
 * epoch changes whenever AI_Mode, the cap or key presence changes, so a reply computed under the old
 * configuration is dropped when the user switches mode or removes the key mid-call (Requirement
 * 42.7). `cloudConfigured` is true only when a positive cap is set AND (in the personal flavour) a
 * key is present; the shared flavour has no key and relies on the Proxy_Server, so key presence is
 * reported true there by its [CloudKeySource].
 */
@Singleton
class SettingsAiConfig @Inject constructor(private val settings: SettingsStore, private val keySource: CloudKeySource) : AiConfig {
    override suspend fun mode(): AiMode = settings.current().aiMode

    override suspend fun capPaise(): Long = settings.current().aiMonthlyCapPaise

    override suspend fun model(type: PromptType): String? = settings.current().aiModels[type]

    override suspend fun cloudConfigured(): Boolean {
        val s = settings.current()
        if (s.aiMonthlyCapPaise <= 0) return false
        return keySource.credential() != null
    }

    override suspend fun epoch(): Long {
        val s = settings.current()
        val keyBit = if (keySource.credential() is CloudCredential.Direct) 1 else 0
        // A stable fingerprint of the configuration that must invalidate an in-flight call.
        return (s.aiMode.ordinal.toLong() shl ORDINAL_SHIFT) xor (s.aiMonthlyCapPaise shl CAP_SHIFT) xor keyBit.toLong()
    }

    private companion object {
        const val ORDINAL_SHIFT = 40
        const val CAP_SHIFT = 1
    }
}

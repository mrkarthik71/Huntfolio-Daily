package app.assistant.app.di

import android.content.Context
import app.assistant.ai.cloud.CloudKeySource
import app.assistant.ai.cloud.OkHttpCloudTransport
import app.assistant.ai.cloud.OpenAiCompatibleProvider
import app.assistant.ai.cloud.ProxyClient
import app.assistant.ai.gateway.AiConfig
import app.assistant.ai.gateway.AiGateway
import app.assistant.ai.gateway.CapGovernor
import app.assistant.ai.gateway.Connectivity
import app.assistant.ai.ondevice.OnDeviceAiProvider
import app.assistant.ai.ondevice.OnDeviceAvailability
import app.assistant.ai.ondevice.OnDeviceRuntime
import app.assistant.ai.ondevice.OnDeviceUnavailable
import app.assistant.ai.output.OutputValidator
import app.assistant.ai.payload.SummaryBuilder
import app.assistant.data.ai.AndroidConnectivity
import app.assistant.data.ai.RoomCapStore
import app.assistant.data.ai.SettingsAiConfig
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.model.AiMode
import app.assistant.domain.port.AiProvider
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.CapStore
import app.assistant.domain.port.NoneProvider
import app.assistant.domain.port.TimeSource
import app.assistant.security.network.EgressPolicy
import app.assistant.security.network.HttpClientFactory
import com.google.gson.JsonParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wires the AI advice layer (Phase 5). The gateway is the only caller of a provider; the provider in
 * force is chosen by the live AI_Mode on each call (None, Cloud, OnDevice). The egress policy adds
 * ONLY the configured AI vendor host, so with no host configured the allow-list stays empty and no
 * AI request can leave the device.
 *
 * NOTE: the AI vendor host + endpoint are left blank here on purpose — the user must supply the exact
 * OpenAI-compatible base URL (Open decision 2 resolved the WIRE FORMAT, not the specific endpoint).
 * Until [AI_VENDOR_HOST] is set, Cloud mode fails closed: the egress interceptor rejects the call.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    abstract fun capStore(impl: RoomCapStore): CapStore

    @Binds
    abstract fun aiConfig(impl: SettingsAiConfig): AiConfig

    @Binds
    abstract fun connectivity(impl: AndroidConnectivity): Connectivity

    companion object {
        /** Configured OpenAI-compatible host (e.g. "api.openai.com"); blank = no Cloud egress allowed. */
        const val AI_VENDOR_HOST = ""
        const val AI_ENDPOINT = "" // e.g. "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        @Provides
        @Singleton
        @Named("ai")
        fun egressPolicy(): EgressPolicy = EgressPolicy { if (AI_VENDOR_HOST.isBlank()) emptySet() else setOf(AI_VENDOR_HOST) }

        @Provides
        @Singleton
        fun outputValidator(@ApplicationContext context: Context): OutputValidator {
            val patterns = runCatching {
                val json = context.assets.open("restricted_advice.json").bufferedReader().use { it.readText() }
                JsonParser.parseString(json).asJsonObject.getAsJsonArray("patterns").map { Regex(it.asString, RegexOption.IGNORE_CASE) }
            }.getOrDefault(emptyList())
            return OutputValidator(patterns)
        }

        @Provides
        @Singleton
        fun redactionPatterns(@ApplicationContext context: Context): RedactionPatterns {
            val list = runCatching {
                val json = context.assets.open("instruction_patterns.json").bufferedReader().use { it.readText() }
                JsonParser.parseString(json).asJsonObject.getAsJsonArray("patterns").map { it.asString }
            }.getOrDefault(emptyList())
            return when (val p = RedactionPatterns.of(list)) {
                is app.assistant.domain.error.Outcome.Ok -> p.value
                is app.assistant.domain.error.Outcome.Err -> RedactionPatterns.of(emptyList()).let { (it as app.assistant.domain.error.Outcome.Ok).value }
            }
        }

        @Provides
        @Singleton
        fun summaryBuilder(patterns: RedactionPatterns): SummaryBuilder = SummaryBuilder(patterns)

        @Provides
        @Singleton
        fun cloudProvider(
            keySource: CloudKeySource,
            proxy: ProxyClient,
            @Named("ai") policy: EgressPolicy,
        ): OpenAiCompatibleProvider {
            val client = HttpClientFactory.create(policy)
            val transport = OkHttpCloudTransport(AI_ENDPOINT) { url, body, apiKey ->
                val request = Request.Builder()
                    .url(url.ifBlank { "https://invalid.invalid/" })
                    .header("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { OkHttpCloudTransport.HttpReply(it.code, it.body?.string().orEmpty()) }
            }
            return OpenAiCompatibleProvider(keySource, transport, proxy, DEFAULT_MODEL)
        }

        @Provides
        @Singleton
        fun onDeviceProvider(): OnDeviceAiProvider {
            // No on-device runtime is bundled yet; availability reports MODEL_NOT_PRESENT so the
            // feature shows the rule-based result and never attempts a Cloud fallback.
            val availability = OnDeviceAvailability { OnDeviceUnavailable.MODEL_NOT_PRESENT }
            val runtime = OnDeviceRuntime { _, _ -> error("on-device runtime not bundled") }
            return OnDeviceAiProvider(availability, runtime)
        }

        @Provides
        @Singleton
        fun capGovernor(store: CapStore, time: TimeSource): CapGovernor = CapGovernor(store, time)

        @Provides
        @Singleton
        fun aiGateway(
            config: AiConfig,
            connectivity: Connectivity,
            cap: CapGovernor,
            audit: AuditLog,
            validator: OutputValidator,
            time: TimeSource,
            none: NoneProviderHolder,
            cloud: OpenAiCompatibleProvider,
            onDevice: OnDeviceAiProvider,
        ): AiGateway {
            // The provider in force is chosen by the live mode on each call.
            val select: () -> AiProvider = {
                when (runBlocking { config.mode() }) {
                    AiMode.CLOUD -> cloud
                    AiMode.ON_DEVICE -> onDevice
                    AiMode.NONE -> none.provider
                }
            }
            return AiGateway(select, config, connectivity, cap, audit, validator, time)
        }

        @Provides
        @Singleton
        fun featureAdvisor(gateway: AiGateway, builder: SummaryBuilder, prompts: app.assistant.data.ai.PromptStore): app.assistant.ai.feature.FeatureAdvisor =
            app.assistant.ai.feature.FeatureAdvisor(gateway, builder) { prompts.prompt(it) }
    }
}

/** Holder so Dagger can inject the object [NoneProvider] without a provider for the singleton object. */
@Singleton
class NoneProviderHolder @javax.inject.Inject constructor() {
    val provider: AiProvider = NoneProvider
}

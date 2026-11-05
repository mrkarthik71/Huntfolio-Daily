package app.assistant.app.di

import app.assistant.data.ai.SharedCloudKeySource
import app.assistant.data.ai.SharedCloudKeyWriter
import app.assistant.domain.port.CloudKeySource
import app.assistant.domain.port.CloudKeyWriter
import app.assistant.domain.port.ProxyClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Shared flavour: no on-device key; every Cloud call routes through the Proxy_Server. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SharedAiModule {
    @Binds
    abstract fun keySource(impl: SharedCloudKeySource): CloudKeySource

    @Binds
    abstract fun keyWriter(impl: SharedCloudKeyWriter): CloudKeyWriter

    companion object {
        /** The configured Proxy_Server host; blank keeps the shared build fail-closed until set. */
        const val PROXY_HOST = ""
        const val PROXY_ENDPOINT = "" // e.g. "https://proxy.example.com/v1/forward"

        /**
         * The Proxy_Server client (Requirement 45.2, 66.3). The server is blocked on ADR 0023, so
         * until PROXY_ENDPOINT + an invite token are configured this fails closed (NOT_CONFIGURED)
         * rather than contacting an unset endpoint.
         */
        @Provides
        @Singleton
        fun proxyClient(holder: app.assistant.data.db.DatabaseHolder, keyManager: app.assistant.security.keys.KeyManager): ProxyClient =
            app.assistant.data.ai.HttpProxyClient(
                endpointUrl = PROXY_ENDPOINT,
                holder = holder,
                keyManager = keyManager,
                policy = { if (PROXY_HOST.isBlank()) emptySet() else setOf(PROXY_HOST) },
            )
    }
}

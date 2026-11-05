package app.assistant.app.di

import app.assistant.ai.cloud.CloudKeySource
import app.assistant.ai.cloud.CloudKeyWriter
import app.assistant.ai.cloud.ProxyClient
import app.assistant.data.ai.PersonalCloudKeySource
import app.assistant.data.ai.PersonalCloudKeyWriter
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AiFailure
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Personal flavour: Cloud key read from the on-device secret store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PersonalAiModule {
    @Binds
    abstract fun keySource(impl: PersonalCloudKeySource): CloudKeySource

    @Binds
    abstract fun keyWriter(impl: PersonalCloudKeyWriter): CloudKeyWriter

    companion object {
        /** The personal flavour never uses the proxy (its credential is always Direct); fail closed if reached. */
        @Provides
        @Singleton
        fun proxyClient(): ProxyClient = ProxyClient { _, _ -> Outcome.Err(AiFailure.NOT_CONFIGURED) }
    }
}

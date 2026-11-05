package app.assistant.app.di

import app.assistant.ai.gateway.CapGovernor
import app.assistant.ai.ondevice.AiUsageReport
import app.assistant.ai.ondevice.OnDeviceAiProvider
import app.assistant.data.career.HuntfolioFileReader
import app.assistant.data.consent.ConsentStore
import app.assistant.data.health.HealthCheck
import app.assistant.data.mail.DataMailboxService
import app.assistant.domain.port.AiUsage
import app.assistant.domain.port.CareerFileReader
import app.assistant.domain.port.ConsentRecord
import app.assistant.domain.port.HealthMonitor
import app.assistant.domain.port.MailboxService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the domain ports the UI uses to their data and ai implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PortBindingsModule {
    @Binds abstract fun careerFileReader(impl: HuntfolioFileReader): CareerFileReader

    @Binds abstract fun consentRecord(impl: ConsentStore): ConsentRecord

    @Binds abstract fun healthMonitor(impl: HealthCheck): HealthMonitor

    @Binds abstract fun mailboxService(impl: DataMailboxService): MailboxService

    companion object {
        @Provides
        fun aiUsage(cap: CapGovernor, onDevice: OnDeviceAiProvider): AiUsage = AiUsageReport(cap, onDevice)
    }
}

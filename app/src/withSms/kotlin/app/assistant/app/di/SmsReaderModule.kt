package app.assistant.app.di

import app.assistant.domain.port.MoneyReaderControls
import app.assistant.domain.port.SenderList
import app.assistant.domain.port.TimeSource
import app.assistant.ingest.pipeline.MessageIngestor
import app.assistant.ingest.sms.AndroidMoneyReaderControls
import app.assistant.ingest.sms.AndroidSmsInbox
import app.assistant.ingest.sms.ReaderStateSmsCursor
import app.assistant.ingest.sms.SmsCursor
import app.assistant.ingest.sms.SmsInbox
import app.assistant.ingest.sms.SmsReaderCore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * withSms build only: binds the real Android SMS reader parts and the money reader controls. The SMS_RECEIVED receiver
 * and the catch-up / history-import workers all run through [SmsReaderCore], constructed here from its ports so it can be
 * unit-tested with fakes without this wiring. The components are declared disabled behind @bool/phase_money in the
 * manifest, so nothing reads until the money phase is on. (MessageIngestor is bound in the shared DataModule.)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmsReaderModule {
    @Binds
    abstract fun moneyReaderControls(impl: AndroidMoneyReaderControls): MoneyReaderControls

    @Binds
    abstract fun smsInbox(impl: AndroidSmsInbox): SmsInbox

    @Binds
    abstract fun smsCursor(impl: ReaderStateSmsCursor): SmsCursor

    companion object {
        @Provides
        @Singleton
        fun smsReaderCore(inbox: SmsInbox, senders: SenderList, cursor: SmsCursor, ingestor: MessageIngestor, time: TimeSource): SmsReaderCore =
            SmsReaderCore(inbox, senders, cursor, ingestor, time)
    }
}

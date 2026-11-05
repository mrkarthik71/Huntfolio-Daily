package app.assistant.app.di

import app.assistant.data.reader.FallbackMoneyReaderControls
import app.assistant.domain.port.MoneyReaderControls
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Sms_Free_Build only: no Sms_Reader exists, so the money reader controls report SMS as NOT_AVAILABLE
 * (Requirements 67.2 to 67.4). The notification reader is unaffected and still works in this build.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SmsFreeReaderModule {
    @Binds
    abstract fun moneyReaderControls(impl: FallbackMoneyReaderControls): MoneyReaderControls
}

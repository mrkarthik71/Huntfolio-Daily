package app.assistant.app.di

import app.assistant.data.reader.AndroidInstalledApps
import app.assistant.data.reader.RoomSenderStore
import app.assistant.domain.port.InstalledApps
import app.assistant.domain.port.SenderStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Flavor-agnostic bindings for the money settings ports. The MoneyReaderControls binding is flavor-specific: the withSms
 * build binds the real Android readers (SmsReaderModule), and the SMS-free build binds the fallback (SmsFreeReaderModule).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MoneyReaderFallbackModule {
    @Binds
    abstract fun installedApps(impl: AndroidInstalledApps): InstalledApps

    @Binds
    abstract fun senderStore(impl: RoomSenderStore): SenderStore
}

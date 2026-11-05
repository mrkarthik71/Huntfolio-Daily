package app.assistant.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.assistant.data.settings.SettingsFiles
import app.assistant.data.settings.SettingsSerializer
import app.assistant.data.settings.proto.SettingsProto
import app.assistant.domain.port.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<SettingsProto> = DataStoreFactory.create(
        serializer = SettingsSerializer,
        // A damaged settings file falls back to the defaults instead of crashing the app.
        corruptionHandler = ReplaceFileCorruptionHandler { SettingsSerializer.defaultValue },
        scope = scope,
        produceFile = { SettingsFiles.file(context) },
    )

    @Provides
    @Singleton
    fun readerStateStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<app.assistant.data.reader.proto.ReaderStateProto> = DataStoreFactory.create(
        serializer = app.assistant.data.reader.ReaderStateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { app.assistant.data.reader.ReaderStateSerializer.defaultValue },
        scope = scope,
        produceFile = { app.assistant.data.reader.ReaderStateFiles.file(context) },
    )
}

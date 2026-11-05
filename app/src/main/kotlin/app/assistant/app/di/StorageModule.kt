package app.assistant.app.di

import android.content.Context
import app.assistant.data.db.DatabaseFactory
import app.assistant.security.keys.AndroidKeystoreWrapper
import app.assistant.security.keys.KeyManager
import app.assistant.security.keys.KeyStoreKeyManager
import app.assistant.security.keys.NoBackupWrappedKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun keyManager(@ApplicationContext context: Context): KeyManager = KeyStoreKeyManager(
        wrapper = AndroidKeystoreWrapper(),
        store = NoBackupWrappedKeyStore(context),
        databaseExists = { DatabaseFactory.databaseFileExists(context) },
    )
}

package app.assistant.app.di

import app.assistant.data.backup.AndroidBackupManager
import app.assistant.data.backup.BackupDataSource
import app.assistant.data.backup.BackupManager
import app.assistant.data.backup.RoomBackupDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Phase 7 encrypted export/restore wiring. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun backupManager(impl: AndroidBackupManager): BackupManager

    @Binds
    abstract fun backupDataSource(impl: RoomBackupDataSource): BackupDataSource
}

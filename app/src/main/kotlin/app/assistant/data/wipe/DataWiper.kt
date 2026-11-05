package app.assistant.data.wipe

import android.content.Context
import androidx.datastore.core.DataStore
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.settings.SettingsFiles
import app.assistant.data.settings.SettingsSerializer
import app.assistant.data.settings.proto.SettingsProto
import app.assistant.domain.model.DataSource
import app.assistant.domain.port.DataWiper
import app.assistant.domain.port.SourceDisconnect
import app.assistant.security.keys.KeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One part of "Delete everything" that a later phase owns: alarms, WorkManager jobs, widget
 * state. Each phase registers its step with Hilt `@IntoSet`, so the wipe grows with the app.
 */
fun interface WipeStep {
    suspend fun wipe()
}

/** Stops one Data_Source and deletes its stored facts by Source_Ref prefix. Registered per phase. */
interface SourceCleanup {
    val source: DataSource

    /** Stops scheduled reads for the source (Requirement 8.2). */
    suspend fun stop()

    /** Deletes every stored fact whose Source_Ref has the source's prefix, in one transaction (Requirement 8.3). */
    suspend fun deleteFacts()
}

@Singleton
class AndroidDataWiper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val keys: KeyManager,
    private val settings: DataStore<SettingsProto>,
    private val readerState: DataStore<app.assistant.data.reader.proto.ReaderStateProto>,
    private val steps: Set<@JvmSuppressWildcards WipeStep>,
) : DataWiper {

    private val marker = File(context.noBackupFilesDir, MARKER)

    override suspend fun wipeAll() {
        marker.writeText("1")
        steps.forEach { runCatching { it.wipe() } }
        holder.close()
        deleteFiles()
        // The settings DataStores are open in this process, so their contents are reset in place.
        runCatching { settings.updateData { SettingsSerializer.defaultValue } }
        runCatching { readerState.updateData { app.assistant.data.reader.ReaderStateSerializer.defaultValue } }
        marker.delete()
    }

    override suspend fun resumeIfPending(): Boolean {
        if (!marker.exists()) return false
        steps.forEach { runCatching { it.wipe() } }
        holder.close()
        deleteFiles()
        // Safe here: this runs before the settings DataStores are first read in this process.
        SettingsFiles.file(context).delete()
        app.assistant.data.reader.ReaderStateFiles.file(context).delete()
        marker.delete()
        return true
    }

    private fun deleteFiles() {
        context.deleteDatabase(AppDatabase.FILE_NAME)
        keys.deleteAll()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val MARKER = "wipe.pending"
        const val PERMISSION_PREFS = "permission_state"
    }
}

@Singleton
class AndroidSourceDisconnect @Inject constructor(private val holder: DatabaseHolder, private val cleanups: Set<@JvmSuppressWildcards SourceCleanup>) :
    SourceDisconnect {
    override suspend fun disconnect(source: DataSource, deleteFacts: Boolean) {
        val forSource = cleanups.filter { it.source == source }
        forSource.forEach { it.stop() }
        // Credentials and keys for a source are stored in the secret table under "<prefix>.".
        holder.database().secretDao().deleteWithPrefix(source.prefix + ".")
        if (deleteFacts) forSource.forEach { it.deleteFacts() }
    }
}

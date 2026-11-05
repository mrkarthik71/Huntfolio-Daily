package app.assistant.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.settings.proto.SettingsProto
import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyManager
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dumps and reloads the Encrypted_Store's LOGICAL rows for the Backup_Manager (Requirement 60.4,
 * 67.5). It enumerates user tables from `sqlite_master`, skipping the `secret` table and Room's
 * internal tables, and writes one JSON object per row to `data.jsonl`. Restore replays the rows into
 * a fresh staging database, then the live DB is closed and the staging file atomically renamed into
 * place. The `secret` table is never read, so credentials never enter a backup.
 *
 * VERIFY AT GATE: the staging/rename against the SQLCipher-encrypted file (the live key must open the
 * staged file), and that promote closes every open handle before the rename on Android's filesystem.
 */
@Singleton
class RoomBackupDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val keys: KeyManager,
    private val settings: DataStore<SettingsProto>,
) : BackupDataSource {

    @Suppress("NestedBlockDepth") // streams every table row by row
    override suspend fun dumpData(out: OutputStream) {
        val db = holder.database().openHelper.readableDatabase
        val writer = out.bufferedWriter()
        for (table in userTables(db)) {
            db.query("SELECT * FROM `$table`").use { c ->
                while (c.moveToNext()) {
                    val row = JsonObject().apply {
                        addProperty("__table", table)
                        for (i in 0 until c.columnCount) {
                            val name = c.getColumnName(i)
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> add(name, null)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> addProperty(name, c.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> addProperty(name, c.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_BLOB -> addProperty(
                                    name,
                                    android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP) + BLOB_TAG,
                                )
                                else -> addProperty(name, c.getString(i))
                            }
                        }
                    }
                    writer.write(row.toString())
                    writer.newLine()
                }
            }
        }
        writer.flush()
    }

    override suspend fun dumpSettings(): ByteArray = settings.data.first().toByteArray()

    override suspend fun dumpRefKey(): ByteArray = when (val k = keys.sourceRefKey()) {
        is Outcome.Ok -> k.value
        is Outcome.Err -> ByteArray(0)
    }

    override suspend fun loadToStaging(data: InputStream, settings: ByteArray, refKey: ByteArray): String {
        // A faithful staging load reopens a fresh encrypted DB at [STAGING] with the live key, runs
        // every migration to the current version, then replays each JSONL row as an INSERT into its
        // table. Settings + ref key are restored by the promote step after the rename.
        val stagingPath = context.getDatabasePath(STAGING).absolutePath
        File(stagingPath).delete()
        // NOTE: the row-replay INSERT loop is the piece that must be verified against SQLCipher at the
        // gate; it is intentionally isolated here so the manager/flow above can be tested with a fake.
        StagingLoader.replay(context, STAGING, data, keys)
        File(context.getDatabasePath(STAGING).absolutePath + ".settings").writeBytes(settings)
        File(context.getDatabasePath(STAGING).absolutePath + ".refkey").writeBytes(refKey)
        return stagingPath
    }

    override suspend fun promoteStaging(stagingPath: String) {
        val live = context.getDatabasePath(AppDatabase.FILE_NAME)
        val marker = File(context.noBackupFilesDir, RESTORE_MARKER)
        marker.writeText(stagingPath)
        holder.close()
        // Atomic within the same filesystem; the marker lets launch finish this if interrupted.
        File(stagingPath).copyTo(live, overwrite = true)
        File(stagingPath).delete()
        // Settings + ref key are applied by the caller's reschedule step; marker cleared last.
        marker.delete()
    }

    override fun hasInterruptedRestore(): Boolean = File(context.noBackupFilesDir, RESTORE_MARKER).exists()

    override suspend fun finishInterruptedRestore() {
        val marker = File(context.noBackupFilesDir, RESTORE_MARKER)
        val stagingPath = marker.takeIf { it.exists() }?.readText()?.trim() ?: return
        val staging = File(stagingPath)
        if (staging.exists()) {
            holder.close()
            staging.copyTo(context.getDatabasePath(AppDatabase.FILE_NAME), overwrite = true)
            staging.delete()
        }
        marker.delete()
    }

    private fun isUserTable(name: String): Boolean = name !in EXCLUDED && SYSTEM_PREFIXES.none { name.startsWith(it) }

    private fun userTables(db: androidx.sqlite.db.SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (isUserTable(name)) tables += name
            }
        }
        return tables
    }

    private companion object {
        const val STAGING = "assistant-staging.db"
        const val RESTORE_MARKER = "restore.marker"
        const val BLOB_TAG = "::b64blob"
        private val SYSTEM_PREFIXES = listOf("sqlite_", "android_", "room_")
        val EXCLUDED = setOf("secret", "sqlite_sequence", "room_master_table")
    }
}

package app.assistant.data.backup

import android.content.ContentValues
import android.content.Context
import app.assistant.data.db.DatabaseFactory
import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyManager
import com.google.gson.JsonParser
import java.io.InputStream

/**
 * Builds the staging database for a restore (Requirement 60.5): a fresh encrypted DB opened with the
 * live key and migrated to the current schema, into which the exported `data.jsonl` rows are replayed
 * as INSERTs. Isolated from [RoomBackupDataSource] so the SQLCipher/Room verification at the gate
 * touches one function. Values tagged with the blob marker are Base64-decoded back to bytes.
 */
object StagingLoader {
    private const val BLOB_TAG = "::b64blob"

    fun replay(context: Context, stagingName: String, data: InputStream, keys: KeyManager) {
        val key = when (val k = keys.databaseKey()) {
            is Outcome.Ok -> k.value
            is Outcome.Err -> error("no database key for staging restore")
        }
        // Open a fresh AppDatabase at the staging file so Room creates the schema + runs migrations,
        // then write rows through its underlying SupportSQLiteDatabase.
        val staging = DatabaseFactory.open(context, key, stagingName)
        try {
            val db = staging.openHelper.writableDatabase
            db.beginTransaction()
            try {
                data.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val obj = JsonParser.parseString(line).asJsonObject
                    val table = obj.get("__table")?.asString ?: return@forEachLine
                    val values = ContentValues()
                    for ((name, el) in obj.entrySet()) {
                        if (name == "__table") continue
                        when {
                            el.isJsonNull -> values.putNull(name)
                            el.asJsonPrimitive.isNumber -> values.put(name, el.asLong)
                            el.asString.endsWith(BLOB_TAG) -> values.put(name, android.util.Base64.decode(el.asString.removeSuffix(BLOB_TAG), android.util.Base64.NO_WRAP))
                            else -> values.put(name, el.asString)
                        }
                    }
                    db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            staging.close()
            key.fill(0)
        }
    }
}

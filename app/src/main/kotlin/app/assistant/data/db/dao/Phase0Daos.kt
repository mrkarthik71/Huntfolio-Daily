package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.assistant.data.db.entity.AuditEntryEntity
import app.assistant.data.db.entity.DiagnosticEventEntity
import app.assistant.data.db.entity.SecretEntity
import kotlinx.coroutines.flow.Flow

/** Insert and query only: the Audit_Log is append-only (design: Audit_Log). */
@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entry: AuditEntryEntity): Long

    @Query("SELECT * FROM audit_entry ORDER BY at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<AuditEntryEntity>

    @Query("SELECT * FROM audit_entry ORDER BY at DESC, id DESC LIMIT :limit")
    fun newest(limit: Int): Flow<List<AuditEntryEntity>>

    @Query("SELECT COUNT(*) FROM audit_entry")
    suspend fun count(): Int
}

@Dao
interface SecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(secret: SecretEntity)

    @Query("SELECT * FROM secret WHERE name = :name")
    suspend fun get(name: String): SecretEntity?

    @Query("DELETE FROM secret WHERE name = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM secret WHERE name LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insert(event: DiagnosticEventEntity): Long

    @Query("DELETE FROM diagnostic_event WHERE id NOT IN (SELECT id FROM diagnostic_event ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Transaction
    suspend fun insertAndTrim(event: DiagnosticEventEntity, keep: Int) {
        insert(event)
        trimTo(keep)
    }

    @Query("SELECT * FROM diagnostic_event ORDER BY id DESC LIMIT :limit")
    fun newest(limit: Int): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT COUNT(*) FROM diagnostic_event")
    suspend fun count(): Int
}

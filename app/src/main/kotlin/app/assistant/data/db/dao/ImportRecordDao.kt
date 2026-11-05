package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.assistant.data.db.entity.ImportRecordEntity

@Dao
interface ImportRecordDao {
    @Query("SELECT key FROM import_record")
    suspend fun allKeys(): List<String>

    @Query("SELECT COUNT(*) FROM import_record WHERE key = :key")
    suspend fun has(key: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: ImportRecordEntity)
}

package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.assistant.data.db.entity.SenderEntryEntity
import kotlinx.coroutines.flow.Flow

/** Reads and edits the User's own Bank_Sender_List entries. The shipped ones are never deleted here. */
@Dao
interface CustomSenderDao {
    @Query("SELECT * FROM sender_entry ORDER BY builtin DESC, id")
    fun observe(): Flow<List<SenderEntryEntity>>

    @Query("SELECT COUNT(*) FROM sender_entry WHERE UPPER(core) = :core")
    suspend fun count(core: String): Int

    @Insert
    suspend fun insert(row: SenderEntryEntity): Long

    @Query("DELETE FROM sender_entry WHERE builtin = 0 AND UPPER(core) = :core")
    suspend fun deleteCustom(core: String)
}

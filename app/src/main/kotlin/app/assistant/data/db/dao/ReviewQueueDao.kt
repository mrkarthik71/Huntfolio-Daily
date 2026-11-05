package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.assistant.data.db.entity.ReviewItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewQueueDao {
    /** Returns -1 when the Source_Ref already has an item (unique index on source_ref). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ReviewItemEntity): Long

    @Query("SELECT * FROM review_item WHERE id = :id")
    suspend fun item(id: Long): ReviewItemEntity?

    @Query("SELECT * FROM review_item ORDER BY received_at DESC, id DESC")
    fun observe(): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_item")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM review_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE review_item SET fallback_attempted = 1, proposal_json = :proposalJson WHERE id = :id")
    suspend fun markFallbackAttempted(id: Long, proposalJson: String?)
}

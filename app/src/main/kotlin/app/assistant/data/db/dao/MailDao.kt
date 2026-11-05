package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.assistant.data.db.entity.InboxItemEntity
import app.assistant.data.db.entity.MailCursorEntity
import app.assistant.data.db.entity.SenderPenaltyEntity
import app.assistant.data.db.entity.ShortlistRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {
    /** -1 when the Source_Ref already has an Inbox_Item (unique index), so items stay unique. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: InboxItemEntity): Long

    @Query("SELECT * FROM inbox_item ORDER BY received_at DESC, id DESC")
    fun items(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_item WHERE id = :id")
    suspend fun item(id: Long): InboxItemEntity?

    @Query("UPDATE inbox_item SET wrong_alert = 1 WHERE id = :id")
    suspend fun markWrongAlert(id: Long)

    @Query("SELECT * FROM shortlist_rule")
    suspend fun rules(): List<ShortlistRuleEntity>

    @Query("SELECT * FROM shortlist_rule")
    fun rulesFlow(): Flow<List<ShortlistRuleEntity>>

    @Insert
    suspend fun addRule(rule: ShortlistRuleEntity): Long

    @Query("DELETE FROM shortlist_rule WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT penalty FROM sender_penalty WHERE sender = :sender")
    suspend fun penalty(sender: String): Int?

    @Upsert
    suspend fun putPenalty(penalty: SenderPenaltyEntity)

    @Query("SELECT * FROM mail_cursor WHERE folder = :folder")
    suspend fun cursor(folder: String): MailCursorEntity?

    @Upsert
    suspend fun putCursor(cursor: MailCursorEntity)

    /** Wrong_Mail_Alert count for items received at/after [sinceEpochMs] (Requirement 55.4, 58.2). */
    @Query("SELECT COUNT(*) FROM inbox_item WHERE wrong_alert = 1 AND received_at >= :sinceEpochMs")
    suspend fun wrongAlertCount(sinceEpochMs: Long): Int

    /** Notification count for items received at/after [sinceEpochMs] (Requirement 58.2). */
    @Query("SELECT COUNT(*) FROM inbox_item WHERE notified = 1 AND received_at >= :sinceEpochMs")
    suspend fun notifiedCount(sinceEpochMs: Long): Int
}

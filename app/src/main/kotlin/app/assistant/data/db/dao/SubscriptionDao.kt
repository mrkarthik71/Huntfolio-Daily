package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.assistant.data.db.entity.SubscriptionChargeEntity
import app.assistant.data.db.entity.SubscriptionEntity
import app.assistant.data.db.entity.SuggestionEntity
import app.assistant.data.db.entity.UsageCheckEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes the Phase 4 subscription tables (Requirement 37-41, 50). */
@Dao
interface SubscriptionDao {

    // --- subscription ---

    @Query("SELECT * FROM subscription ORDER BY next_due ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription WHERE status = :status ORDER BY next_due ASC")
    fun observeByStatus(status: String): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE status = 'ACTIVE'")
    suspend fun active(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE id = :id")
    suspend fun byId(id: Long): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Query("SELECT COUNT(*) FROM subscription WHERE merchant_id = :merchantId AND `interval` = :interval")
    suspend fun countForMerchantInterval(merchantId: Long, interval: String): Int

    // --- charges ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharge(charge: SubscriptionChargeEntity): Long

    @Query("SELECT COUNT(*) FROM subscription_charge WHERE transaction_id = :transactionId")
    suspend fun chargeCount(transactionId: Long): Int

    // --- usage checks ---

    @Insert
    suspend fun insertUsageCheck(check: UsageCheckEntity): Long

    @Update
    suspend fun updateUsageCheck(check: UsageCheckEntity)

    @Query("SELECT * FROM usage_check WHERE subscription_id = :subscriptionId AND answered_at IS NULL")
    suspend fun openUsageChecks(subscriptionId: Long): List<UsageCheckEntity>

    @Query("SELECT * FROM usage_check WHERE answered_at IS NULL")
    suspend fun allOpenUsageChecks(): List<UsageCheckEntity>

    @Query("DELETE FROM usage_check WHERE subscription_id = :subscriptionId AND answered_at IS NULL")
    suspend fun deleteOpenUsageChecks(subscriptionId: Long)

    // --- suggestions ---

    @Insert
    suspend fun insertSuggestion(suggestion: SuggestionEntity): Long

    @Update
    suspend fun updateSuggestion(suggestion: SuggestionEntity)

    @Query("SELECT * FROM suggestion ORDER BY shown_at DESC")
    fun observeSuggestions(): Flow<List<SuggestionEntity>>

    @Query("SELECT * FROM suggestion")
    suspend fun allSuggestions(): List<SuggestionEntity>

    @Query("SELECT * FROM suggestion WHERE id = :id")
    suspend fun suggestionById(id: Long): SuggestionEntity?

    /** Responded suggestions for a Prompt_Type (Requirement 50.3: accept rate per Prompt_Type). */
    @Query("SELECT COUNT(*) FROM suggestion WHERE prompt_type = :promptType AND response IS NOT NULL")
    suspend fun respondedCount(promptType: String): Int

    /** Accepted suggestions for a Prompt_Type. */
    @Query("SELECT COUNT(*) FROM suggestion WHERE prompt_type = :promptType AND response = 'ACCEPTED'")
    suspend fun acceptedCount(promptType: String): Int
}

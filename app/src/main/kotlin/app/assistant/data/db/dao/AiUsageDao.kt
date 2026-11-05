package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Append-and-accumulate access to `ai_usage` (design: Cap_Governor). [reserve] and [record] both
 * run in one transaction so a concurrent attempt can never read a stale total and exceed the cap.
 */
@Dao
interface AiUsageDao {
    @Query("SELECT spentPaise FROM ai_usage WHERE month = :month")
    suspend fun spent(month: String): Long?

    @Query("SELECT attempts FROM ai_usage WHERE month = :month")
    suspend fun attempts(month: String): Int?

    @Query("INSERT INTO ai_usage(month, spentPaise, attempts) VALUES(:month, :paise, :attempts) ON CONFLICT(month) DO UPDATE SET spentPaise = spentPaise + :paise, attempts = attempts + :attempts")
    suspend fun add(month: String, paise: Long, attempts: Int)

    /**
     * Adds [paise] to this month only if the new total would not exceed [capPaise]. Returns true
     * when the reservation was made, false when it would breach the cap. One transaction, so two
     * callers cannot both squeeze past the same remaining budget.
     */
    @Transaction
    suspend fun tryReserve(month: String, paise: Long, capPaise: Long): Boolean {
        val current = spent(month) ?: 0
        if (current + paise > capPaise) return false
        add(month, paise, attempts = 0)
        return true
    }
}

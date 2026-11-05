package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.assistant.data.db.entity.FocusSessionEntity
import app.assistant.data.db.entity.FollowUpEntity
import app.assistant.data.db.entity.HabitCheckEntity
import app.assistant.data.db.entity.HabitEntity
import app.assistant.data.db.entity.ImportantDateEntity
import app.assistant.data.db.entity.MoodLogEntity
import app.assistant.data.db.entity.SavingsEntryEntity
import app.assistant.data.db.entity.SavingsGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // one DAO for the eight small whole-day tables
interface LifeDao {
    @Query("SELECT * FROM habit ORDER BY active DESC, name")
    fun habits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit")
    suspend fun habitsNow(): List<HabitEntity>

    @Query("SELECT * FROM habit WHERE id = :id")
    suspend fun habit(id: Long): HabitEntity?

    @Upsert
    suspend fun upsertHabit(h: HabitEntity): Long

    @Query("DELETE FROM habit WHERE id = :id")
    suspend fun deleteHabit(id: Long)

    @Query("SELECT * FROM habit_check WHERE date BETWEEN :from AND :to")
    fun checks(from: String, to: String): Flow<List<HabitCheckEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheck(c: HabitCheckEntity)

    @Query("DELETE FROM habit_check WHERE habit_id = :habitId AND date = :date AND slot = :slot")
    suspend fun deleteCheck(habitId: Long, date: String, slot: String)

    @Query("SELECT * FROM important_date ORDER BY date")
    fun dates(): Flow<List<ImportantDateEntity>>

    @Query("SELECT * FROM important_date")
    suspend fun datesNow(): List<ImportantDateEntity>

    @Query("SELECT * FROM important_date WHERE id = :id")
    suspend fun date(id: Long): ImportantDateEntity?

    @Upsert
    suspend fun upsertDate(d: ImportantDateEntity): Long

    @Query("DELETE FROM important_date WHERE id = :id")
    suspend fun deleteDate(id: Long)

    @Query("SELECT * FROM savings_goal ORDER BY created_on")
    fun goals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_entry ORDER BY date")
    fun entries(): Flow<List<SavingsEntryEntity>>

    @Upsert
    suspend fun upsertGoal(g: SavingsGoalEntity): Long

    @Query("DELETE FROM savings_goal WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    @Insert
    suspend fun insertEntry(e: SavingsEntryEntity): Long

    @Query("SELECT * FROM follow_up ORDER BY done, nudge_on")
    fun followUps(): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_up WHERE done = 0")
    suspend fun openFollowUps(): List<FollowUpEntity>

    @Query("SELECT * FROM follow_up WHERE id = :id")
    suspend fun followUp(id: Long): FollowUpEntity?

    @Upsert
    suspend fun upsertFollowUp(f: FollowUpEntity): Long

    @Query("UPDATE follow_up SET done = :done WHERE id = :id")
    suspend fun setFollowUpDone(id: Long, done: Boolean)

    @Query("SELECT * FROM mood_log WHERE date BETWEEN :from AND :to ORDER BY date")
    fun moods(from: String, to: String): Flow<List<MoodLogEntity>>

    @Upsert
    suspend fun upsertMood(m: MoodLogEntity)

    @Query("SELECT * FROM focus_session WHERE started_at BETWEEN :from AND :to ORDER BY started_at")
    fun sessions(from: Long, to: Long): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_session WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    suspend fun running(): FocusSessionEntity?

    @Insert
    suspend fun insertSession(s: FocusSessionEntity): Long

    @Query("UPDATE focus_session SET ended_at = :at, completed = :completed WHERE id = :id")
    suspend fun endSession(id: Long, at: Long, completed: Boolean)
}

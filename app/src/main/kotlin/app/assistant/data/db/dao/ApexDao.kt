package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.assistant.data.db.entity.MilestoneEntity
import app.assistant.data.db.entity.ProactiveCardEntity
import app.assistant.data.db.entity.RoutineEntity
import app.assistant.data.db.entity.SmartReminderEntity
import app.assistant.data.db.entity.TaskVerificationEntity
import kotlinx.coroutines.flow.Flow

/** A goal-linked task row for progress maths. */
data class GoalWorkRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "goal_id") val goalId: Long,
    @androidx.room.ColumnInfo(name = "milestone_id") val milestoneId: Long?,
    val status: String,
    @androidx.room.ColumnInfo(name = "estimate_min") val estimateMinutes: Int?,
)

/** Planner tables: milestones, routines, smart reminders, proactive cards and task proofs. */
@Dao
interface ApexDao {
    @Query("SELECT * FROM milestone WHERE goal_id = :goalId ORDER BY position")
    fun milestones(goalId: Long): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestone ORDER BY goal_id, position")
    fun allMilestones(): Flow<List<MilestoneEntity>>

    @Query("SELECT id, goal_id, milestone_id, status, estimate_min FROM task WHERE goal_id IS NOT NULL AND status != 'DROPPED'")
    fun goalWork(): Flow<List<GoalWorkRow>>

    @Query("SELECT * FROM milestone WHERE id = :id")
    suspend fun milestone(id: Long): MilestoneEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM milestone WHERE goal_id = :goalId")
    suspend fun nextPosition(goalId: Long): Int

    @Insert
    suspend fun insertMilestone(m: MilestoneEntity): Long

    @Update
    suspend fun updateMilestone(m: MilestoneEntity)

    @Query("DELETE FROM milestone WHERE id = :id")
    suspend fun deleteMilestone(id: Long)

    @Query("UPDATE task SET priority = :priority, energy = :energy, milestone_id = :milestoneId, verify = :verify WHERE id = :taskId")
    suspend fun setTaskPlanning(taskId: Long, priority: Int, energy: String, milestoneId: Long?, verify: String)

    @Query("UPDATE goal SET deadline = :deadline, priority = :priority WHERE id = :goalId")
    suspend fun setGoalPlanning(goalId: Long, deadline: String?, priority: Int)

    @Query("SELECT * FROM routine ORDER BY start_minute")
    fun routines(): Flow<List<RoutineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(r: RoutineEntity): Long

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    @Query("SELECT * FROM smart_reminder ORDER BY kind, id")
    fun reminders(): Flow<List<SmartReminderEntity>>

    @Query("SELECT * FROM smart_reminder WHERE active = 1 AND kind = :kind")
    suspend fun activeReminders(kind: String): List<SmartReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReminder(r: SmartReminderEntity): Long

    @Query("UPDATE smart_reminder SET active = :active WHERE id = :id")
    suspend fun setReminderActive(id: Long, active: Boolean)

    @Query("UPDATE smart_reminder SET last_fired_at = :at WHERE id = :id")
    suspend fun markReminderFired(id: Long, at: Long)

    @Query("DELETE FROM smart_reminder WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM proactive_card WHERE for_date >= :from AND status = 'NEW' ORDER BY for_date, kind")
    fun openCards(from: String): Flow<List<ProactiveCardEntity>>

    /** One card per kind and date: a re-run of the agent keeps the User's answer. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCard(c: ProactiveCardEntity): Long

    @Query("UPDATE proactive_card SET status = :status WHERE id = :id")
    suspend fun setCardStatus(id: Long, status: String)

    @Query("DELETE FROM proactive_card WHERE for_date < :before")
    suspend fun deleteCardsBefore(before: String)

    @Insert
    suspend fun insertVerification(v: TaskVerificationEntity): Long

    @Query("SELECT * FROM task_verification WHERE task_id = :taskId ORDER BY created_at")
    suspend fun verifications(taskId: Long): List<TaskVerificationEntity>
}

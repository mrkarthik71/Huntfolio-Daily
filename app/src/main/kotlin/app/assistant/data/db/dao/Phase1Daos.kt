package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.assistant.data.db.entity.AlarmQueueEntity
import app.assistant.data.db.entity.DailyPlanEntity
import app.assistant.data.db.entity.GoalEntity
import app.assistant.data.db.entity.GoalProgressEntity
import app.assistant.data.db.entity.NotificationLogEntity
import app.assistant.data.db.entity.PlanPriorityEntity
import app.assistant.data.db.entity.ReportEntity
import app.assistant.data.db.entity.TaskEntity
import app.assistant.data.db.entity.TaskMoveEntity
import app.assistant.data.db.entity.TimeBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Query("SELECT * FROM goal WHERE archived_at IS NULL ORDER BY created_at, id")
    fun activeGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal WHERE archived_at IS NULL ORDER BY created_at, id")
    suspend fun activeGoalsNow(): List<GoalEntity>

    @Query("SELECT * FROM goal")
    suspend fun all(): List<GoalEntity>

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun get(id: Long): GoalEntity?

    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("UPDATE goal SET archived_at = :at WHERE id = :id")
    suspend fun archive(id: Long, at: Long)

    @Query("UPDATE goal SET parent_goal_id = :parent WHERE id = :id")
    suspend fun setParent(id: Long, parent: Long?)

    @Query("SELECT * FROM goal_progress")
    fun allProgress(): Flow<List<GoalProgressEntity>>

    @Query("SELECT * FROM goal_progress")
    suspend fun allProgressNow(): List<GoalProgressEntity>

    @Query("SELECT progress_hundredths FROM goal_progress WHERE goal_id = :goalId AND period_first_day = :first")
    suspend fun progressOf(goalId: Long, first: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(row: GoalProgressEntity)
}

data class DayCount(val date: String, val n: Int)

data class MoveCount(val taskId: Long, val title: String, val moves: Int)

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM task WHERE status = 'OPEN' ORDER BY created_at, id")
    fun openTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE status = 'OPEN' ORDER BY created_at, id")
    suspend fun openTasksNow(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE planned_date = :date ORDER BY created_at, id")
    fun plannedOn(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE planned_date = :date ORDER BY created_at, id")
    suspend fun plannedOnNow(date: String): List<TaskEntity>

    @Query("UPDATE task SET status = :status, completed_at = :at, completed_date = :date WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, at: Long?, date: String?)

    @Query("UPDATE task SET planned_date = :date WHERE id = :id")
    suspend fun setPlanned(id: Long, date: String)

    @Insert
    suspend fun insertMove(move: TaskMoveEntity)

    @Query("SELECT t.* FROM plan_priority p JOIN task t ON t.id = p.task_id WHERE p.date = :date ORDER BY p.rank")
    fun prioritiesFor(date: String): Flow<List<TaskEntity>>

    @Query("SELECT completed_date AS date, COUNT(*) AS n FROM task WHERE completed_date IS NOT NULL GROUP BY completed_date")
    fun doneByDay(): Flow<List<DayCount>>

    /** Priorities completed on the day they were planned for. */
    @Query(
        "SELECT p.date AS date, COUNT(*) AS n FROM plan_priority p JOIN task t ON t.id = p.task_id " +
            "WHERE t.status = 'DONE' AND t.completed_date = p.date GROUP BY p.date",
    )
    fun prioritiesDoneByDay(): Flow<List<DayCount>>

    @Query("SELECT date AS date, COUNT(*) AS n FROM plan_priority GROUP BY date")
    fun prioritiesTotalByDay(): Flow<List<DayCount>>

    @Query(
        "SELECT t.id AS taskId, t.title AS title, COUNT(*) AS moves FROM task_move m JOIN task t ON t.id = m.task_id " +
            "WHERE m.moved_on BETWEEN :first AND :last GROUP BY t.id",
    )
    suspend fun movesBetween(first: String, last: String): List<MoveCount>

    @Query("SELECT MIN(created_at) FROM task")
    suspend fun firstTaskCreatedAt(): Long?
}

@Dao
interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlan(plan: DailyPlanEntity): Long

    @Query("SELECT * FROM daily_plan WHERE date = :date")
    suspend fun plan(date: String): DailyPlanEntity?

    @Query("SELECT * FROM daily_plan WHERE date = :date")
    fun planFlow(date: String): Flow<DailyPlanEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPriority(row: PlanPriorityEntity)

    @Query("SELECT * FROM plan_priority WHERE date = :date ORDER BY rank")
    suspend fun prioritiesNow(date: String): List<PlanPriorityEntity>

    @Query("DELETE FROM plan_priority WHERE date = :date")
    suspend fun clearPriorities(date: String)

    @Query("DELETE FROM plan_priority WHERE task_id = :taskId AND date = :date")
    suspend fun removePriority(date: String, taskId: Long)

    @Query("DELETE FROM plan_priority WHERE task_id = :taskId")
    suspend fun removePrioritiesOfTask(taskId: Long)

    @Insert
    suspend fun insertBlock(block: TimeBlockEntity): Long

    @Query("SELECT * FROM time_block WHERE date = :date ORDER BY start_at")
    fun blocksFor(date: String): Flow<List<TimeBlockEntity>>

    @Query("SELECT * FROM time_block WHERE date = :date ORDER BY start_at")
    suspend fun blocksNow(date: String): List<TimeBlockEntity>

    @Query("DELETE FROM time_block WHERE task_id = :taskId AND date = :date")
    suspend fun removeBlock(date: String, taskId: Long)

    @Query("DELETE FROM time_block WHERE date = :date AND kind = 'PRIORITY'")
    suspend fun clearPriorityBlocks(date: String)

    @Query("DELETE FROM time_block WHERE task_id = :taskId")
    suspend fun removeBlocksOfTask(taskId: Long)

    @Query("UPDATE time_block SET start_at = :startAt, end_at = :endAt, conflicting = 0 WHERE id = :id")
    suspend fun moveBlock(id: Long, startAt: Long, endAt: Long)

    @Query("DELETE FROM time_block WHERE id = :id")
    suspend fun removeBlockById(id: Long)
}

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(report: ReportEntity): Long

    @Query("SELECT MAX(period_first_day) FROM report WHERE kind = :kind")
    suspend fun lastPeriod(kind: String): String?

    @Query("SELECT * FROM report WHERE kind = :kind ORDER BY period_first_day DESC LIMIT 1")
    fun latest(kind: String): Flow<ReportEntity?>
}

@Dao
interface AlarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AlarmQueueEntity): Long

    @Query("SELECT * FROM alarm_queue ORDER BY due_at, id")
    suspend fun all(): List<AlarmQueueEntity>

    @Query("SELECT * FROM alarm_queue WHERE due_at <= :upTo ORDER BY due_at, id")
    suspend fun due(upTo: Long): List<AlarmQueueEntity>

    @Query("SELECT * FROM alarm_queue ORDER BY due_at, id LIMIT 1")
    suspend fun earliest(): AlarmQueueEntity?

    @Query("DELETE FROM alarm_queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM alarm_queue WHERE type = :type")
    suspend fun deleteType(type: String)

    @Query("DELETE FROM alarm_queue WHERE type = :type AND ref_key = :refKey")
    suspend fun deleteRef(type: String, refKey: String)

    @Query("UPDATE alarm_queue SET due_at = :dueAt WHERE id = :id")
    suspend fun setDue(id: Long, dueAt: Long)
}

data class TypeCount(val type: String, val n: Int)

@Dao
interface NotificationLogDao {
    @Insert
    suspend fun insert(row: NotificationLogEntity): Long

    @Query("SELECT * FROM notification_log WHERE id = :id")
    suspend fun get(id: Long): NotificationLogEntity?

    @Query("SELECT COUNT(*) FROM notification_log WHERE day = :day AND counted = 1")
    suspend fun countedOn(day: String): Int

    @Query("SELECT type AS type, COUNT(*) AS n FROM notification_log WHERE day = :day AND type_counted = 1 GROUP BY type")
    suspend fun typeCountedOn(day: String): List<TypeCount>

    @Query("SELECT DISTINCT ref_key FROM notification_log WHERE type = :type")
    suspend fun refKeysOf(type: String): List<String>

    @Query("SELECT * FROM notification_log WHERE decision = 'INBOX' ORDER BY at DESC LIMIT 200")
    fun inbox(): Flow<List<NotificationLogEntity>>
}

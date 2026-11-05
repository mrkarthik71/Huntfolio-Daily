package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Dates are ISO yyyy-MM-dd text, instants are epoch milliseconds (design: Data Models rules).

@Entity(tableName = "goal", indices = [Index("parent_goal_id"), Index("archived_at")])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val period: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "target_hundredths") val targetHundredths: Long,
    val unit: String,
    @ColumnInfo(name = "parent_goal_id") val parentGoalId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
)

/** Progress per Period_Instance, so edits and archiving never touch it (Requirement 11.6). */
@Entity(
    tableName = "goal_progress",
    primaryKeys = ["goal_id", "period_first_day"],
    foreignKeys = [ForeignKey(GoalEntity::class, ["id"], ["goal_id"], onDelete = ForeignKey.CASCADE)],
)
data class GoalProgressEntity(
    @ColumnInfo(name = "goal_id") val goalId: Long,
    @ColumnInfo(name = "period_first_day") val periodFirstDay: String,
    @ColumnInfo(name = "progress_hundredths") val progressHundredths: Long,
)

@Entity(tableName = "task", indices = [Index("planned_date", "status"), Index("goal_id"), Index("due_date"), Index("completed_date")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: String,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "planned_date") val plannedDate: String?,
    @ColumnInfo(name = "estimate_min") val estimateMinutes: Int?,
    @ColumnInfo(name = "goal_id") val goalId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    /** Local date of completion, used by streaks and Goal progress. */
    @ColumnInfo(name = "completed_date") val completedDate: String?,
    @ColumnInfo(name = "source_ref") val sourceRef: String?,
)

@Entity(tableName = "task_move", indices = [Index("task_id"), Index("moved_on")])
data class TaskMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "from_date") val fromDate: String?,
    @ColumnInfo(name = "to_date") val toDate: String,
    @ColumnInfo(name = "moved_on") val movedOn: String,
)

@Entity(tableName = "daily_plan")
data class DailyPlanEntity(@PrimaryKey val date: String, @ColumnInfo(name = "created_at") val createdAt: Long)

@Entity(tableName = "plan_priority", primaryKeys = ["date", "rank"], indices = [Index("task_id")])
data class PlanPriorityEntity(
    val date: String,
    val rank: Int,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "user_set") val userSet: Boolean,
)

@Entity(tableName = "time_block", indices = [Index("date"), Index("task_id")])
data class TimeBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    @ColumnInfo(name = "task_id") val taskId: Long?,
    val kind: String,
    val conflicting: Boolean,
)

/** A report exists for a period; its content is recalculated from data on display (Requirement 35.4). */
@Entity(tableName = "report", primaryKeys = ["kind", "period_first_day"])
data class ReportEntity(
    val kind: String,
    @ColumnInfo(name = "period_first_day") val periodFirstDay: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One pending scheduled notification. [refKey] holds IDs only (ADR 0009). */
@Entity(tableName = "alarm_queue", indices = [Index("due_at"), Index(value = ["type", "ref_key"], unique = true)])
data class AlarmQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "ref_key") val refKey: String,
    /** AT_INSTANT or AT_LOCAL_TIME. */
    val rule: String,
    @ColumnInfo(name = "local_date") val localDate: String?,
    @ColumnInfo(name = "local_minute") val localMinute: Int?,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "is_snooze") val isSnooze: Boolean,
)

/** What the Notification_Governor decided; feeds the Inbox_List, Why_Explanation and daily counts. */
@Entity(tableName = "notification_log", indices = [Index("day", "type"), Index("decision")])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val type: String,
    @ColumnInfo(name = "ref_key") val refKey: String,
    /** SHOWN, SHOWN_UNCOUNTED, INBOX */
    val decision: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    val at: Long,
    /** Counts toward the Daily_Cap. */
    val counted: Boolean,
    /** Counts toward the type's Per_Type_Limit. */
    @ColumnInfo(name = "type_counted") val typeCounted: Boolean,
    /** Short text shown in the Inbox_List and on the Why screen (titles of Tasks and Goals only). */
    val summary: String,
)

package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Room v12: the whole-day features. Dates are ISO text, times "HH:mm", instants epoch millis,
// money in paise. Lists are comma-joined enum names or times.

@Entity(tableName = "habit")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val times: String,
    val days: String,
    val active: Boolean,
    @ColumnInfo(name = "created_on") val createdOn: String,
)

@Entity(
    tableName = "habit_check",
    primaryKeys = ["habit_id", "date", "slot"],
    indices = [Index("date")],
    foreignKeys = [ForeignKey(HabitEntity::class, ["id"], ["habit_id"], onDelete = ForeignKey.CASCADE)],
)
data class HabitCheckEntity(
    @ColumnInfo(name = "habit_id") val habitId: Long,
    val date: String,
    val slot: String,
)

@Entity(tableName = "important_date")
data class ImportantDateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String,
    val date: String,
    val yearly: Boolean,
    @ColumnInfo(name = "from_contacts") val fromContacts: Boolean,
)

@Entity(tableName = "savings_goal")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "target_paise") val targetPaise: Long,
    val deadline: String?,
    @ColumnInfo(name = "created_on") val createdOn: String,
)

@Entity(
    tableName = "savings_entry",
    indices = [Index("goal_id")],
    foreignKeys = [ForeignKey(SavingsGoalEntity::class, ["id"], ["goal_id"], onDelete = ForeignKey.CASCADE)],
)
data class SavingsEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val date: String,
)

@Entity(tableName = "follow_up")
data class FollowUpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val person: String,
    val about: String,
    @ColumnInfo(name = "nudge_on") val nudgeOn: String,
    val done: Boolean,
    @ColumnInfo(name = "created_on") val createdOn: String,
)

@Entity(tableName = "mood_log")
data class MoodLogEntity(
    @PrimaryKey val date: String,
    val mood: Int,
    val energy: Int,
)

@Entity(tableName = "focus_session", indices = [Index("started_at")])
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "task_id") val taskId: Long?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "planned_min") val plannedMinutes: Int,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    val completed: Boolean,
)

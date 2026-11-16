package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Apex planner tables (Room v10). Dates are ISO text, instants epoch milliseconds.

@Entity(
    tableName = "milestone",
    indices = [Index("goal_id")],
    foreignKeys = [ForeignKey(GoalEntity::class, ["id"], ["goal_id"], onDelete = ForeignKey.CASCADE)],
)
data class MilestoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    val title: String,
    val position: Int,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "estimate_min") val estimateMinutes: Int?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
)

@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val energy: String,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    @ColumnInfo(name = "days_mask") val daysMask: Int,
    @ColumnInfo(name = "start_minute") val startMinute: Int,
    @ColumnInfo(name = "duration_min") val durationMinutes: Int,
    @ColumnInfo(name = "flexible_min") val flexibleMinutes: Int,
    val active: Boolean,
)

@Entity(tableName = "smart_reminder", indices = [Index("kind", "active")])
data class SmartReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val message: String,
    val kind: String,
    @ColumnInfo(name = "fire_at") val fireAt: Long?,
    @ColumnInfo(name = "place_name") val placeName: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "radius_m") val radiusMeters: Int?,
    @ColumnInfo(name = "on_enter") val onEnter: Boolean,
    val device: String?,
    @ColumnInfo(name = "not_before_minute") val notBeforeMinute: Int?,
    val active: Boolean,
    @ColumnInfo(name = "task_id") val taskId: Long?,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long?,
)

@Entity(tableName = "proactive_card", indices = [Index(value = ["kind", "for_date"], unique = true), Index("for_date")])
data class ProactiveCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "for_date") val forDate: String,
    val title: String,
    val reason: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "task_verification",
    indices = [Index("task_id")],
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["task_id"], onDelete = ForeignKey.CASCADE)],
)
data class TaskVerificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    val kind: String,
    @ColumnInfo(name = "link_url") val linkUrl: String?,
    @ColumnInfo(name = "tracked_seconds") val trackedSeconds: Int?,
    @ColumnInfo(name = "photo_file") val photoFile: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Room v11: something the User asked Apex to remember, in their own words. Never message text from another app. */
@Entity(tableName = "memory_note", indices = [Index("created_at")])
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
)

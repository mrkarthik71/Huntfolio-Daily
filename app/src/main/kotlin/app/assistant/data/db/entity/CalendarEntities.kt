package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Phase 2 Calendar entities. Dates are ISO yyyy-MM-dd text, instants are epoch milliseconds.

/** A calendar the User has selected to read (Requirement 22.1). Absence of any row means "all". */
@Entity(tableName = "calendar_source")
data class CalendarSourceEntity(
    @PrimaryKey @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "account_name") val accountName: String,
    val selected: Boolean,
)

/**
 * One cached calendar occurrence (Requirement 22.2). A timed event sets [startAt]/[endAt] (epoch
 * millis) and leaves the all-day dates null; an all-day event sets [allDayStart]/[allDayEnd] (ISO
 * dates) and leaves the instants null. The cache is replaced wholesale on refresh.
 */
@Entity(
    tableName = "calendar_event",
    indices = [Index("start_at"), Index("all_day_start"), Index("calendar_id")],
)
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "event_id") val eventId: Long,
    val title: String,
    @ColumnInfo(name = "start_at") val startAt: Long?,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    @ColumnInfo(name = "all_day_start") val allDayStart: String?,
    @ColumnInfo(name = "all_day_end") val allDayEnd: String?,
)

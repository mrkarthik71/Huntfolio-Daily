package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.assistant.data.db.entity.CalendarEventEntity
import app.assistant.data.db.entity.CalendarSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_source ORDER BY display_name, calendar_id")
    fun sources(): Flow<List<CalendarSourceEntity>>

    @Query("SELECT calendar_id FROM calendar_source WHERE selected = 1")
    fun selectedIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<CalendarSourceEntity>)

    @Query("UPDATE calendar_source SET selected = :selected WHERE calendar_id = :id")
    suspend fun setSelected(id: Long, selected: Boolean)

    /** Timed events whose [start_at, end_at) touches the day's instant window, newest first by start. */
    @Query("SELECT * FROM calendar_event WHERE start_at IS NOT NULL AND start_at < :dayEnd AND end_at > :dayStart ORDER BY start_at")
    fun timedBetween(dayStart: Long, dayEnd: Long): Flow<List<CalendarEventEntity>>

    /** All-day events whose date span contains [date] (end exclusive). */
    @Query("SELECT * FROM calendar_event WHERE all_day_start IS NOT NULL AND all_day_start <= :date AND all_day_end > :date ORDER BY all_day_start, title")
    fun allDayOn(date: String): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_event WHERE start_at IS NOT NULL ORDER BY start_at")
    suspend fun allTimed(): List<CalendarEventEntity>

    @Query("DELETE FROM calendar_event")
    suspend fun clearEvents()

    @Insert
    suspend fun insertEvents(events: List<CalendarEventEntity>)

    /** Replaces the whole event cache in one transaction (Requirement 22.4). */
    @Transaction
    suspend fun replaceEvents(events: List<CalendarEventEntity>) {
        clearEvents()
        insertEvents(events)
    }
}

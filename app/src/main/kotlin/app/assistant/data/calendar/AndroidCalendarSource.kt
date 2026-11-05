package app.assistant.data.calendar

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.CalendarInfo
import app.assistant.domain.model.CalendarReadError
import app.assistant.domain.model.InstantRange
import app.assistant.domain.port.CalendarSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Reads the device calendar through `CalendarContract` (design: Calendar_Reader). Read-only: it
 * queries `Instances` (which expands recurring events) and never writes. Cancelled and declined
 * occurrences are filtered (Requirement 22.6); timed events are kept as instants and all-day
 * events as calendar dates, since the provider stores all-day events in UTC (Requirement 22.5).
 */
class AndroidCalendarSource @Inject constructor(@param:ApplicationContext private val context: Context) : CalendarSource {

    override suspend fun calendars(): Outcome<List<CalendarInfo>, CalendarReadError> {
        return try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
            )
            val out = mutableListOf<CalendarInfo>()
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(
                        id = c.getLong(0),
                        displayName = c.getString(1).orEmpty(),
                        accountName = c.getString(2).orEmpty(),
                    )
                }
            } ?: return Outcome.Err(CalendarReadError.PROVIDER_UNAVAILABLE)
            Outcome.Ok(out)
        } catch (_: SecurityException) {
            Outcome.Err(CalendarReadError.PERMISSION_DENIED)
        } catch (_: Exception) {
            Outcome.Err(CalendarReadError.UNKNOWN)
        }
    }

    override suspend fun instances(window: InstantRange, calendarIds: Set<Long>): Outcome<List<CalendarEventFact>, CalendarReadError> {
        return try {
            val projection = arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            )
            // Instances.query takes the window as a URI range; the provider expands occurrences.
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, window.start.toEpochMilli())
                ContentUris.appendId(it, window.end.toEpochMilli())
                it.build()
            }
            val out = mutableListOf<CalendarEventFact>()
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                readRows(c, calendarIds, out)
            } ?: return Outcome.Err(CalendarReadError.PROVIDER_UNAVAILABLE)
            Outcome.Ok(out)
        } catch (_: SecurityException) {
            Outcome.Err(CalendarReadError.PERMISSION_DENIED)
        } catch (_: Exception) {
            Outcome.Err(CalendarReadError.UNKNOWN)
        }
    }

    /** Reads every instance row into [out], applying the calendar filter. */
    private fun readRows(c: android.database.Cursor, calendarIds: Set<Long>, out: MutableList<CalendarEventFact>) {
        while (c.moveToNext()) {
            val calId = c.getLong(0)
            if (calendarIds.isNotEmpty() && calId !in calendarIds) continue
            out += rowToFact(c, calId)
        }
    }

    private fun rowToFact(c: android.database.Cursor, calId: Long): CalendarEventFact {
        val begin = c.getLong(3)
        val end = c.getLong(4)
        val allDay = c.getInt(5) == 1
        val status = if (c.isNull(6)) -1 else c.getInt(6)
        val selfStatus = if (c.isNull(7)) -1 else c.getInt(7)
        val cancelled = status == CalendarContract.Instances.STATUS_CANCELED
        val declined = selfStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        return if (allDay) {
            // All-day BEGIN/END are midnight UTC; a date has no zone.
            CalendarEventFact(
                calendarId = calId,
                eventId = c.getLong(1),
                title = c.getString(2).orEmpty(),
                timed = null,
                allDayStart = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate(),
                allDayEnd = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(),
                cancelled = cancelled,
                declined = declined,
            )
        } else {
            CalendarEventFact(
                calendarId = calId,
                eventId = c.getLong(1),
                title = c.getString(2).orEmpty(),
                timed = InstantRange(Instant.ofEpochMilli(begin), Instant.ofEpochMilli(maxOf(begin, end))),
                allDayStart = null,
                allDayEnd = null,
                cancelled = cancelled,
                declined = declined,
            )
        }
    }

    override val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }
}

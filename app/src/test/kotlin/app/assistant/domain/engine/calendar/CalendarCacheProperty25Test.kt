package app.assistant.domain.engine.calendar

import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.InstantRange
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Feature: personal-assistant-android, Property 25: Calendar cache equals the filtered provider set.
 * Validates: Requirements 22.2, 22.3, 22.4, 22.6.
 *
 * The cache is exactly the events that CalendarCachePolicy.keep accepts: inside the window, not
 * cancelled, not declined. No kept event is cancelled or declined, and every dropped event fails
 * at least one of those conditions.
 */
class CalendarCacheProperty25Test {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 3)
    private val window = CalendarCachePolicy.window(today, zone)
    private val base = today.atStartOfDay(zone).toInstant().epochSecond

    private val eventArb: Arb<CalendarEventFact> = Arb.bind(
        Arb.long(-2L..10L), // start offset in days from today
        Arb.int(1..72), // duration hours
        Arb.boolean(),
        Arb.boolean(),
    ) { dayOffset, durHours, cancelled, declined ->
        val start = Instant.ofEpochSecond(base + dayOffset * 86_400L)
        CalendarEventFact(
            calendarId = 1,
            eventId = dayOffset * 100 + durHours.toLong(),
            title = "e",
            timed = InstantRange(start, start.plusSeconds(durHours * 3_600L)),
            allDayStart = null,
            allDayEnd = null,
            cancelled = cancelled,
            declined = declined,
        )
    }

    @Test
    fun `cache is exactly the kept events`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.list(eventArb, 0..30)) { events ->
            val kept = events.filter { CalendarCachePolicy.keep(it, window, zone) }
            // Reference: the same three conditions spelled out independently.
            val reference = events.filter { e ->
                !e.cancelled && !e.declined && e.timed!!.overlaps(window)
            }
            assertEquals(reference, kept)
            assertTrue(kept.none { it.cancelled || it.declined })
            assertTrue(kept.all { it.timed!!.overlaps(window) })
        }
    }
}

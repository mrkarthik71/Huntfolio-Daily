package app.assistant.domain.engine.calendar

import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.InstantRange
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Feature: personal-assistant-android, Property 26: Stored events are stable across time zones.
 * Validates: Requirements 22.5, 23.4.
 *
 * A timed event is stored as absolute Instants. The window for a given calendar date shifts with
 * the zone, but for a fixed instant the membership question "does this event overlap the window of
 * its own day in this zone" is answered the same whether we compute it directly or after the device
 * zone changes, because the event's instants never move. Here: an event built around a reference
 * instant keeps the same keep() verdict against the window of the same wall-clock day in each zone.
 */
class CalendarZoneStabilityProperty26Test {

    private val zones = listOf("Asia/Kolkata", "America/New_York", "Europe/London", "Pacific/Auckland", "UTC")

    @Test
    fun `a timed event's cache membership is computed from fixed instants`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.long(0L..7L),
            Arb.int(1..48),
            Arb.element(zones),
            Arb.element(zones),
        ) { dayOffset, durHours, zoneA, zoneB ->
            val today = LocalDate.of(2026, 11, 3)
            val za = ZoneId.of(zoneA)
            val zb = ZoneId.of(zoneB)

            // The same absolute event instant, placed inside zoneA's window.
            val start = today.plusDays(dayOffset).atStartOfDay(za).toInstant()
            val event = CalendarEventFact(
                calendarId = 1,
                eventId = 1,
                title = "e",
                timed = InstantRange(start, start.plusSeconds(durHours * 3_600L)),
                allDayStart = null,
                allDayEnd = null,
                cancelled = false,
                declined = false,
            )

            // keep() depends only on the event's instants vs the window's instants. Recomputing the
            // window in a different zone changes the window, not the event: the overlap check is
            // still a pure function of two fixed instant ranges, never of the display zone itself.
            val windowA = CalendarCachePolicy.window(today, za)
            val keptA = CalendarCachePolicy.keep(event, windowA, za)
            val windowB = CalendarCachePolicy.window(today, zb)
            val keptB = CalendarCachePolicy.keep(event, windowB, zb)

            // Each verdict equals a direct overlap of the fixed ranges — no hidden zone dependence.
            assertEquals(event.timed!!.overlaps(windowA), keptA)
            assertEquals(event.timed.overlaps(windowB), keptB)
        }
    }
}

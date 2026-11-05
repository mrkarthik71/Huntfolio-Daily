// Feature: Phase 2 Calendar — event reminders. Property 28: Event reminder set matches timed events.
package app.assistant.domain.engine.calendar

import app.assistant.domain.model.CalendarEventFact
import app.assistant.domain.model.InstantRange
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private val arbEvent: Arb<CalendarEventFact> = arbitrary {
    val calendarId = Arb.long(1L..5L).bind()
    val eventId = Arb.long(1L..1000L).bind()
    val title = Arb.string(0..12).bind()
    val startOffsetMin = Arb.long(-500L..500L).bind()
    val allDay = Arb.boolean().bind()
    val cancelled = Arb.boolean().bind()
    val declined = Arb.boolean().bind()
    val base = Instant.EPOCH.plusSeconds(100_000)
    if (allDay) {
        val d = base.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        CalendarEventFact(calendarId, eventId, title, null, d, d.plusDays(1), cancelled, declined)
    } else {
        val start = base.plusSeconds(startOffsetMin * 60)
        CalendarEventFact(calendarId, eventId, title, InstantRange(start, start.plusSeconds(1800)), null, null, cancelled, declined)
    }
}

class EventReminderPlannerPropertyTest {

    @Test
    fun `Property 28 - reminder set matches timed events`() = runTest {
        checkAll(Arb.list(arbEvent, 0..30), Arb.long(0L..120L)) { events, leadMinutes ->
            val now = Instant.EPOCH.plusSeconds(100_000)
            val leadTime = Duration.ofMinutes(leadMinutes)
            val plans = EventReminderPlanner.plan(events, leadTime, now)

            val eligible = events.filter { it.timed != null && !it.cancelled && !it.declined && it.timed.start.isAfter(now) }
            val expectedIds = eligible.map { EventReminderPlanner.refKey(it, it.timed!!.start) }.toSet()

            // One-to-one with eligible timed events: all-day, cancelled, declined and past-start events have none.
            assert(plans.map { it.refKey }.toSet() == expectedIds)

            plans.forEach { plan ->
                val event = eligible.first { EventReminderPlanner.refKey(it, it.timed!!.start) == plan.refKey }
                val wanted = event.timed!!.start.minus(leadTime)
                val expectedDue = if (wanted.isBefore(now)) now else wanted
                assert(plan.dueAt == expectedDue)
                assert(plan.title == event.title)
            }
        }
    }

    @Test
    fun `a near event still due in the past is floored to the refresh time, not dropped`() = runTest {
        val now = Instant.EPOCH.plusSeconds(100_000)
        val floor = now.plusSeconds(30)
        val event = CalendarEventFact(1, 1, "Standup", InstantRange(now.plusSeconds(60), now.plusSeconds(1800)), null, null, false, false)
        val plans = EventReminderPlanner.plan(listOf(event), Duration.ofMinutes(15), now, floor)
        assert(plans.single().dueAt == floor)
    }

    @Test
    fun `all-day and cancelled and declined events get no reminder`() = runTest {
        val now = Instant.EPOCH.plusSeconds(100_000)
        val d = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val events = listOf(
            CalendarEventFact(1, 1, "All day", null, d, d.plusDays(1), false, false),
            CalendarEventFact(1, 2, "Cancelled", InstantRange(now.plusSeconds(600), now.plusSeconds(1200)), null, null, true, false),
            CalendarEventFact(1, 3, "Declined", InstantRange(now.plusSeconds(600), now.plusSeconds(1200)), null, null, false, true),
        )
        assert(EventReminderPlanner.plan(events, Duration.ofMinutes(15), now).isEmpty())
    }
}

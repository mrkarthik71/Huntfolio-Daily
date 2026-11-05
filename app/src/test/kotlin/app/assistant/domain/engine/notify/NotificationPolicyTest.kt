package app.assistant.domain.engine.notify

import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.NotificationType.BILL_DUE
import app.assistant.domain.model.NotificationType.EVENT_REMINDER
import app.assistant.domain.model.NotificationType.GOAL_NUDGE
import app.assistant.domain.model.NotificationType.MORNING_BRIEF
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Task 10.2 decision table, plus Properties 21 and 22 (Requirements 18.2 to 18.10, 19.5, 24.6). */
class NotificationPolicyTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 3)
    private fun at(h: Int, m: Int = 0): Instant = day.atTime(h, m).atZone(zone).toInstant()

    private val quiet = LocalTimeRange(LocalTime.of(22, 30), LocalTime.of(7, 0))
    private val cfg = GovernorSettings(
        enabled = NotificationType.entries.toSet(),
        quietHours = quiet,
        dailyCap = 3,
        perTypeLimit = mapOf(EVENT_REMINDER to 2),
        capExempt = setOf(EVENT_REMINDER),
        defaultPerTypeLimit = 2,
    )
    private val empty = GovernorState(day, 0, emptyMap())
    private fun cand(type: NotificationType = MORNING_BRIEF, created: Instant = at(9)) = Candidate(type, "k", created)

    @Test
    fun `decision table`() {
        data class Row(val name: String, val c: Candidate, val s: GovernorState, val cfg: GovernorSettings, val now: Instant, val expected: Decision)
        val rows = listOf(
            Row("type switched off drops", cand(), empty, cfg.copy(enabled = emptySet()), at(9), Decision.Drop),
            Row("plain show", cand(), empty, cfg, at(9), Decision.Show),
            Row("event already started goes to inbox", cand(EVENT_REMINDER).copy(eventStart = at(8)), empty, cfg, at(9), Decision.ToInbox(InboxReason.STALE)),
            Row("event at exactly now is stale", cand(EVENT_REMINDER).copy(eventStart = at(9)), empty, cfg, at(9), Decision.ToInbox(InboxReason.STALE)),
            Row("bill due yesterday is stale", cand(BILL_DUE).copy(dueDate = day.minusDays(1)), empty, cfg, at(9), Decision.ToInbox(InboxReason.STALE)),
            Row("bill due today is not stale", cand(BILL_DUE).copy(dueDate = day), empty, cfg, at(9), Decision.Show),
            Row(
                "late evening in quiet hours holds until 07:00 tomorrow",
                cand(),
                empty,
                cfg,
                at(23),
                Decision.Hold(day.plusDays(1).atTime(7, 0).atZone(zone).toInstant()),
            ),
            Row("early morning in quiet hours holds until 07:00 today", cand(), empty, cfg, at(6, 59), Decision.Hold(at(7))),
            Row("07:00 exactly is outside quiet hours", cand(), empty, cfg, at(7), Decision.Show),
            Row("quiet hours off", cand(), empty, cfg.copy(quietHours = null), at(23), Decision.Show),
            Row(
                "snooze repeat is shown uncounted even at the cap",
                cand().copy(isSnoozeRepeat = true),
                GovernorState(day, 3, mapOf(MORNING_BRIEF to 2)),
                cfg,
                at(9),
                Decision.ShowUncounted(countsForType = false),
            ),
            Row("daily cap reached goes to inbox", cand(GOAL_NUDGE), GovernorState(day, 3, emptyMap()), cfg, at(9), Decision.ToInbox(InboxReason.DAILY_CAP)),
            Row(
                "per-type limit reached goes to inbox before cap",
                cand(),
                GovernorState(day, 2, mapOf(MORNING_BRIEF to 2)),
                cfg,
                at(9),
                Decision.ToInbox(InboxReason.PER_TYPE_LIMIT),
            ),
            Row(
                "exempt type ignores the cap",
                cand(EVENT_REMINDER).copy(eventStart = at(10)),
                GovernorState(day, 3, emptyMap()),
                cfg,
                at(9),
                Decision.ShowUncounted(countsForType = true),
            ),
            Row(
                "exempt type still has its own limit",
                cand(EVENT_REMINDER).copy(eventStart = at(10)),
                GovernorState(day, 3, mapOf(EVENT_REMINDER to 2)),
                cfg,
                at(9),
                Decision.ToInbox(InboxReason.PER_TYPE_LIMIT),
            ),
            Row(
                "yesterday's counts do not apply today",
                cand(GOAL_NUDGE),
                GovernorState(day.minusDays(1), 3, mapOf(GOAL_NUDGE to 2)),
                cfg,
                at(9),
                Decision.Show,
            ),
        )
        for (r in rows) assertEquals(r.expected, NotificationPolicy.decide(r.c, r.s, r.cfg, r.now, zone), r.name)
    }

    @Test
    fun `quiet hours end is null outside the window`() {
        assertNull(NotificationPolicy.quietHoursEnd(at(12), zone, quiet))
        val daytime = LocalTimeRange(LocalTime.of(13, 0), LocalTime.of(14, 0))
        assertEquals(at(14), NotificationPolicy.quietHoursEnd(at(13, 30), zone, daytime))
    }

    /** Property 22: however candidates arrive, counted shows never exceed the cap or per-type limits. */
    @Test
    fun `daily cap and per-type limits are never exceeded`() = runTest {
        val types = listOf(MORNING_BRIEF, BILL_DUE, GOAL_NUDGE, EVENT_REMINDER)
        checkAll(
            PropertyDefaults.standard,
            Arb.int(1..20),
            Arb.int(1..20),
            Arb.list(Arb.element(types), 0..60),
            Arb.list(Arb.boolean(), 60..60),
        ) { cap, limit, arrivals, exemptFlags ->
            val settings = cfg.copy(
                quietHours = null,
                dailyCap = cap,
                perTypeLimit = types.associateWith { limit },
                capExempt = types.filterIndexed { i, _ -> exemptFlags[i] }.toSet(),
            )
            var state = empty
            for (t in arrivals) {
                val c = Candidate(t, "k", at(9), eventStart = if (t == EVENT_REMINDER) at(11) else null)
                val d = NotificationPolicy.decide(c, state, settings, at(10), zone)
                state = NotificationPolicy.apply(state, c, d, day)
            }
            assertTrue(state.shownToday <= cap)
            assertTrue(state.shownTodayByType.values.all { it <= limit })
        }
    }

    /** Property 21 (part): a decision depends only on its inputs, and a held item always gets a later time. */
    @Test
    fun `holds always end in the future and decisions are repeatable`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.long(0L until 24 * 60 * 60L)) { secondOfDay ->
            val now = day.atStartOfDay(zone).toInstant().plusSeconds(secondOfDay)
            val d1 = NotificationPolicy.decide(cand(), empty, cfg, now, zone)
            val d2 = NotificationPolicy.decide(cand(), empty, cfg, now, zone)
            assertEquals(d1, d2)
            if (d1 is Decision.Hold) assertTrue(d1.until.isAfter(now))
        }
    }
}

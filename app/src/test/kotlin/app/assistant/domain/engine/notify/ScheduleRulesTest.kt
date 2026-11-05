package app.assistant.domain.engine.notify

import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.GoalStatus
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.Quantity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Properties 23 and 24 (Requirements 19.4, 20.1, 20.5, 20.7). */
class ScheduleRulesTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 3)

    @Test
    fun `changed brief time is scheduled today if not passed, else tomorrow`() {
        val now = day.atTime(9, 0).atZone(ist).toInstant()
        assertEquals(ScheduleRule.AtLocalTime(day, LocalTime.of(21, 0)), ScheduleRules.nextDaily(LocalTime.of(21, 0), now, ist))
        assertEquals(ScheduleRule.AtLocalTime(day.plusDays(1), LocalTime.of(8, 0)), ScheduleRules.nextDaily(LocalTime.of(8, 0), now, ist))
        assertEquals(ScheduleRule.AtLocalTime(day, LocalTime.of(9, 0)), ScheduleRules.nextDaily(LocalTime.of(9, 0), now, ist))
    }

    @Test
    fun `local-time rules keep their clock time after a zone change, instants do not move`() {
        val rule = ScheduleRule.AtLocalTime(day, LocalTime.of(8, 0))
        val london = ZoneId.of("Europe/London")
        assertEquals(day.atTime(8, 0).atZone(london).toInstant(), ScheduleRules.resolve(rule, london))
        val fixed = ScheduleRule.AtInstant(Instant.parse("2026-11-03T05:00:00Z"))
        assertEquals(ScheduleRules.resolve(fixed, ist), ScheduleRules.resolve(fixed, london))
    }

    @Test
    fun `a brief missed while the phone was off rolls to the next day`() {
        val rule = ScheduleRule.AtLocalTime(day, LocalTime.of(8, 0))
        val after = day.atTime(10, 0).atZone(ist).toInstant()
        assertEquals(day.plusDays(1), ScheduleRules.rollForward(rule, after, ist).date)
    }

    @Test
    fun `goal nudge picks the most behind week goal, ties to the oldest`() {
        fun status(id: Long, behind: Int, period: GoalPeriod = GoalPeriod.WEEK) = GoalStatus(
            Goal(GoalId(id), "g$id", period, day, Quantity(100), "h", null, Instant.EPOCH.plusSeconds(id), null),
            PeriodInstance(period, day, day.plusDays(6)),
            Quantity(0),
            0,
            behind,
        )
        assertNull(BriefContent.goalNudge(listOf(status(1, 1), status(2, 5, GoalPeriod.MONTH))))
        // g2 and g3 tie at 4 days; g2 was created first, so it wins whatever the list order.
        assertEquals(GoalNudge("g2", 4), BriefContent.goalNudge(listOf(status(1, 2), status(3, 4), status(2, 4)).reversed()))
        assertEquals(MorningBrief(0, null), BriefContent.morningBrief(emptyList()))
        assertEquals(MorningBrief(2, "a"), BriefContent.morningBrief(listOf("a", "b")))
    }
}

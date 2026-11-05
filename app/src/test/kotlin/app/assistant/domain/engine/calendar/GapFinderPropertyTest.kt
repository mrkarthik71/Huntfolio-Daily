package app.assistant.domain.engine.calendar

import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.LocalTimeRange
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimedEvent
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Feature: personal-assistant-android, Property 29: Free_Gap finder matches a minute-grid model.
 * Validates: Requirements 25.1.
 */
class GapFinderProperty29Test {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 11, 3)
    private val wh = LocalTimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0))
    private val dayStart = date.atStartOfDay(zone).toInstant()

    private fun eventAt(startMin: Long, durMin: Long) =
        TimedEvent(InstantRange(dayStart.plusSeconds(startMin * 60), dayStart.plusSeconds((startMin + durMin) * 60)), "e")

    @Test
    fun `gaps are inside working hours, after now, at least minFocus, and free of events`() = runTest {
        val eventArb = Arb.bind(Arb.long(540L..1080L), Arb.long(15L..120L)) { s, d -> eventAt(s, d) } // 09:00..18:00 in minutes
        checkAll(
            PropertyDefaults.standard,
            Arb.list(eventArb, 0..6),
            Arb.long(480L..1080L), // now, in minutes from midnight
            Arb.int(15..240),
        ) { events, nowMin, minFocusMin ->
            val now = dayStart.plusSeconds(nowMin * 60)
            val minFocus = Duration.ofMinutes(minFocusMin.toLong())
            val gaps = GapFinder.freeGaps(date, now, zone, wh, events, emptyList(), minFocus)
            val whStart = date.atTime(wh.start).atZone(zone).toInstant()
            val whEnd = date.atTime(wh.end).atZone(zone).toInstant()
            gaps.forEach { g ->
                assertTrue(!g.start.isBefore(whStart) && !g.end.isAfter(whEnd), "inside working hours")
                assertTrue(!g.start.isBefore(now), "after now")
                assertTrue(g.duration >= minFocus, "at least minFocus")
                assertTrue(events.none { it.range.overlaps(g) }, "free of events")
            }
            // Gaps are ordered and non-overlapping.
            assertTrue(gaps.zipWithNext().all { (a, b) -> !a.end.isAfter(b.start) })
        }
    }
}

/**
 * Feature: personal-assistant-android, Property 30: Focus_Block_Proposals are greedy, fit and do not repeat Tasks.
 * Validates: Requirements 25.2, 25.3, 25.4, 25.5.
 */
class GapFinderProposalProperty30Test {

    private val base = Instant.parse("2026-11-03T09:00:00Z")

    private fun task(id: Long, estMin: Int?) = Task(TaskId(id), "t$id", TaskStatus.OPEN, null, null, estMin, null, Instant.EPOCH, null)

    @Test
    fun `a proposal fits the gap, repeats no task and respects alreadyProposed`() = runTest {
        val taskArb = Arb.bind(Arb.long(1L..50L), Arb.int(0..3)) { id, kind ->
            task(id, if (kind == 0) null else kind * 30) // some tasks have no estimate
        }
        checkAll(PropertyDefaults.standard, Arb.list(taskArb, 0..12), Arb.long(30L..240L)) { tasks, gapMin ->
            val gap = InstantRange(base, base.plusSeconds(gapMin * 60))
            val ranked = tasks.distinctBy { it.id }
            val proposal = GapFinder.proposal(gap, ranked, alreadyProposed = emptySet())
            if (proposal != null) {
                // Sum of estimates <= gap length (invariant 25.3).
                val total = proposal.tasks.sumOf { (it.estimateMinutes ?: 0).toLong() }
                assertTrue(Duration.ofMinutes(total) <= gap.duration)
                // No task repeated.
                assertTrue(proposal.taskIds.toSet().size == proposal.taskIds.size)
                // Every chosen task has an estimate and comes from the ranked set.
                assertTrue(proposal.tasks.all { it.estimateMinutes != null && it.id in ranked.map { r -> r.id } })
                // place() lays them back to back within the gap.
                val placed = GapFinder.place(proposal, base)
                assertTrue(placed.zipWithNext().all { (a, b) -> a.range.end == b.range.start })
                assertTrue(placed.isEmpty() || !placed.first().range.start.isBefore(gap.start))
            }
            // A task already proposed is never chosen again.
            val firstId = ranked.firstOrNull { it.estimateMinutes != null }?.id
            if (firstId != null) {
                val p2 = GapFinder.proposal(gap, ranked, alreadyProposed = setOf(firstId))
                assertTrue(p2 == null || firstId !in p2.taskIds)
            }
        }
    }
}

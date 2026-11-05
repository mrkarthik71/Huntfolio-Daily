package app.assistant.domain.engine.calendar

import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.model.TimeBlockKind
import app.assistant.domain.model.TimedEvent
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Feature: personal-assistant-android, Property 27: Today timeline ordering and formatting.
 * Validates: Requirements 15.1, 21.5, 23.1, 23.5.
 */
class CalendarTimelineProperty27Test {

    private val dayStart = Instant.parse("2026-11-03T00:00:00Z")
    private val dayEnd = Instant.parse("2026-11-04T00:00:00Z")

    private fun event(startSec: Long, durSec: Long, title: String) =
        TimedEvent(InstantRange(dayStart.plusSeconds(startSec), dayStart.plusSeconds(startSec + durSec)), title)

    @Test
    fun `timeline is sorted by effective start then end then title and keeps only overlapping events`() = runTest {
        val arb = Arb.bind(Arb.long(-7200L..90_000L), Arb.int(1..7200), Arb.int(0..3)) { s, d, t ->
            event(s, d.toLong(), "t$t")
        }
        checkAll(PropertyDefaults.standard, Arb.list(arb, 0..20)) { events ->
            val ordered = TodayTimeline.order(events, dayStart, dayEnd)
            // Every entry overlaps the day.
            assertTrue(ordered.all { it.event.range.start < dayEnd && it.event.range.end > dayStart && !it.event.range.isEmpty })
            // Sorted by (effective start, end, title).
            val keys = ordered.map {
                Triple(if (it.startsEarlier) dayStart else it.event.range.start, it.event.range.end, it.event.title)
            }
            val sorted = keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            assertEquals(sorted, keys)
            // startsEarlier exactly when the event began before the day.
            assertTrue(ordered.all { it.startsEarlier == it.event.range.start.isBefore(dayStart) })
        }
    }

    @Test
    fun `nextEvent is the earliest timed event starting at or after now`() = runTest {
        val arb = Arb.bind(Arb.long(0L..80_000L), Arb.int(1..3600), Arb.int(0..3)) { s, d, t ->
            event(s, d.toLong(), "t$t")
        }
        checkAll(PropertyDefaults.standard, Arb.list(arb, 0..15), Arb.long(0L..86_400L)) { events, nowSec ->
            val now = dayStart.plusSeconds(nowSec)
            val next = TodayTimeline.nextEvent(events, now, dayEnd)
            val reference = events
                .filterNot { it.range.isEmpty }
                .filter { !it.range.start.isBefore(now) && it.range.start.isBefore(dayEnd) }
                .minWithOrNull(compareBy({ it.range.start }, { it.range.end }, { it.title }))
            assertEquals(reference, next)
        }
    }
}

/**
 * Feature: personal-assistant-android, Property 31: Conflict detection and move suggestion.
 * Validates: Requirements 23.6.
 */
class CalendarConflictProperty31Test {

    private val base = Instant.parse("2026-11-03T09:00:00Z")

    private fun block(id: Long, startMin: Long, durMin: Long) =
        TimeBlock(TimeBlockId(id), null, InstantRange(base.plusSeconds(startMin * 60), base.plusSeconds((startMin + durMin) * 60)), TimeBlockKind.PRIORITY)

    private fun event(startMin: Long, durMin: Long) = TimedEvent(InstantRange(base.plusSeconds(startMin * 60), base.plusSeconds((startMin + durMin) * 60)), "e")

    @Test
    fun `a block conflicts exactly when it overlaps an event`() = runTest {
        val blockArb = Arb.bind(Arb.long(1L..200L), Arb.long(0L..300L), Arb.long(15L..120L)) { id, s, d -> block(id, s, d) }
        val eventArb = Arb.bind(Arb.long(0L..300L), Arb.long(15L..120L)) { s, d -> event(s, d) }
        checkAll(PropertyDefaults.standard, Arb.list(blockArb, 0..8), Arb.list(eventArb, 0..8)) { blocks, events ->
            val conflicting = ConflictDetector.conflicts(blocks, events)
            val eventRanges = events.map { it.range }
            val reference = blocks.filter { b -> eventRanges.any { it.overlaps(b.range) } }.mapNotNull { it.id }.toSet()
            assertEquals(reference, conflicting)
        }
    }

    @Test
    fun `suggestMove returns the earliest later gap that fits, else null`() = runTest {
        val b = block(1, 60, 30) // 30-minute block
        val gaps = listOf(
            InstantRange(base, base.plusSeconds(600)), // before block, too early
            InstantRange(base.plusSeconds(3600), base.plusSeconds(3600 + 1200)), // at/after start, 20min — too small
            InstantRange(base.plusSeconds(7200), base.plusSeconds(7200 + 3600)), // later, 60min — fits
        )
        val move = ConflictDetector.suggestMove(b, java.time.LocalDate.of(2026, 11, 3), gaps)
        assertEquals(base.plusSeconds(7200), move?.start)
        assertEquals(java.time.Duration.ofMinutes(30), move?.duration)
        // No fitting gap -> null.
        assertEquals(null, ConflictDetector.suggestMove(b, java.time.LocalDate.of(2026, 11, 3), listOf(gaps[0], gaps[1])))
    }
}

package app.assistant.domain.engine.goals

import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.engine.review.ReviewDraft
import app.assistant.domain.engine.review.ReviewEngine
import app.assistant.domain.engine.tasks.TaskField
import app.assistant.domain.engine.tasks.TaskInput
import app.assistant.domain.engine.tasks.TaskValidator
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.DaySummary
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.StreakRule
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Properties 6, 11, 12, 13 and 19 (Requirements 11, 12, 13, 16, 17). */
class GoalsTest {

    private val today = LocalDate.of(2026, 11, 3) // a Tuesday

    private fun goal(id: Long, period: GoalPeriod, target: Long = 500, start: LocalDate = LocalDate.of(2026, 1, 1), archived: Boolean = false) =
        Goal(GoalId(id), "g$id", period, start, Quantity(target), "hours", null, Instant.EPOCH.plusSeconds(id), if (archived) Instant.EPOCH else null)

    @Test
    fun `quantity parser accepts exactly two decimal places in range`() {
        assertEquals(Outcome.Ok(Quantity(1250)), QuantityParser.parse("12.5"))
        assertEquals(Outcome.Ok(Quantity(123450)), QuantityParser.parse("1,234.5"))
        assertEquals(Outcome.Ok(Quantity(1)), QuantityParser.parse("0.01"))
        assertEquals(Outcome.Ok(Quantity(99_999_999_999)), QuantityParser.parse("999,999,999.99"))
        assertEquals(Outcome.Err(ValidationError.OUT_OF_RANGE), QuantityParser.parse("0"))
        assertEquals(Outcome.Err(ValidationError.OUT_OF_RANGE), QuantityParser.parse("1000000000"))
        assertEquals(Outcome.Err(ValidationError.NOT_A_NUMBER), QuantityParser.parse("1.234"))
        assertEquals(Outcome.Err(ValidationError.NOT_A_NUMBER), QuantityParser.parse("-5"))
        assertEquals(Outcome.Err(ValidationError.NOT_A_NUMBER), QuantityParser.parse("1e3"))
        assertEquals(Outcome.Err(ValidationError.EMPTY), QuantityParser.parse("  "))
    }

    @Test
    fun `quantity format and parse round trip`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.long(1L..99_999_999_999L)) { h ->
            assertEquals(Outcome.Ok(Quantity(h)), QuantityParser.parse(QuantityParser.format(Quantity(h))))
        }
    }

    /** Property 6: validators accept exactly the valid region. */
    @Test
    fun `task and quick add validation accept exactly 1 to 200 trimmed characters`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.string(0..260), Arb.int(-5..1500).orNull(0.3)) { title, estimate ->
            val trimmed = title.trim()
            val errors = TaskValidator.validate(TaskInput(title, estimateText = estimate?.toString() ?: ""))
            assertEquals(trimmed.isNotEmpty() && trimmed.length <= 200, errors[TaskField.TITLE] == null)
            assertEquals(estimate == null || estimate in 1..1440, errors[TaskField.ESTIMATE] == null)
            val quick = TaskValidator.normaliseQuickAdd(title)
            val norm = title.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
            assertEquals(norm.isNotEmpty() && norm.length <= 200, quick is Outcome.Ok)
            if (quick is Outcome.Ok) assertTrue('\n' !in quick.value)
        }
    }

    @Test
    fun `goal validation marks each invalid field`() {
        val ok = GoalInput("Learn SQL", GoalPeriod.WEEK, today, "5", "hours")
        assertTrue(GoalValidator.validate(ok).isValid)
        val bad = GoalValidator.validate(GoalInput("  ", null, null, "0", "x".repeat(31)))
        assertEquals(setOf(GoalField.NAME, GoalField.PERIOD, GoalField.START_DATE, GoalField.TARGET, GoalField.UNIT), bad.errors.keys)
        assertEquals(ValidationError.TOO_LONG, GoalValidator.validate(ok.copy(name = "n".repeat(101)))[GoalField.NAME])
    }

    /** Property 11: only week→month and month→year links between active Goals. */
    @Test
    fun `goal link rule`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.element(GoalPeriod.entries), Arb.element(GoalPeriod.entries), Arb.boolean(), Arb.boolean()) { cp, pp, ca, pa ->
            val result = GoalValidator.validateLink(goal(1, cp, archived = ca), goal(2, pp, archived = pa))
            val expected = !ca && !pa && ((cp == GoalPeriod.WEEK && pp == GoalPeriod.MONTH) || (cp == GoalPeriod.MONTH && pp == GoalPeriod.YEAR))
            assertEquals(expected, result is Outcome.Ok)
        }
    }

    @Test
    fun `week instances end on the chosen last day`() {
        val sundayWeek = Periods.instanceContaining(GoalPeriod.WEEK, today, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 2), sundayWeek.first)
        assertEquals(LocalDate.of(2026, 11, 8), sundayWeek.last)
        val tuesdayWeek = Periods.instanceContaining(GoalPeriod.WEEK, today, DayOfWeek.TUESDAY)
        assertEquals(today, tuesdayWeek.last)
        assertEquals(LocalDate.of(2026, 11, 1), Periods.instanceContaining(GoalPeriod.MONTH, today, DayOfWeek.SUNDAY).first)
        assertEquals(LocalDate.of(2026, 12, 31), Periods.instanceContaining(GoalPeriod.YEAR, today, DayOfWeek.SUNDAY).last)
    }

    /** Property 12: Days_Behind equals the Glossary formula computed with exact rationals. */
    @Test
    fun `percent and days behind match the reference formula`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.long(1L..100_000L), Arb.long(0L..200_000L), Arb.int(0..6)) { target, progress, offset ->
            val g = goal(1, GoalPeriod.WEEK, target)
            val inst = Periods.instanceContaining(GoalPeriod.WEEK, today, DayOfWeek.SUNDAY)
            val day = inst.first.plusDays(offset.toLong())
            assertEquals(Math.floorDiv(progress * 100, target).toInt(), ProgressMath.percent(Quantity(progress), Quantity(target)))
            val total = 7L
            val elapsed = offset.toLong()
            // Expected − actual over target/total, in exact integer form.
            val reference = Math.floorDiv(elapsed * target - progress * total, target).coerceAtLeast(0)
            assertEquals(reference.toInt(), ProgressMath.daysBehind(g, inst, Quantity(progress), day))
        }
    }

    @Test
    fun `days behind example from the plan`() {
        // Weekly target 5 hours, 1.5 done, 4 full days elapsed of 7: expected 2.857, behind 1.357 h = 1.9 days → 1.
        val g = goal(1, GoalPeriod.WEEK, target = 500, start = LocalDate.of(2026, 1, 1))
        val inst = Periods.instanceContaining(GoalPeriod.WEEK, LocalDate.of(2026, 11, 6), DayOfWeek.SUNDAY)
        assertEquals(1, ProgressMath.daysBehind(g, inst, Quantity(150), LocalDate.of(2026, 11, 6)))
    }

    /** Property 13: streak equals a reference count. */
    @Test
    fun `streak matches a reference count`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.list(Arb.int(0..3), 0..40), Arb.element(StreakRule.entries)) { done, rule ->
            val history = done.withIndex().associate { (i, d) -> today.minusDays(i.toLong()) to DaySummary(3, d, d) }
            val meets = done.map { ProgressMath.meets(rule, DaySummary(3, it, it)) }
            val startsYesterday = meets.isEmpty() || !meets[0]
            val ref = meets.drop(if (startsYesterday) 1 else 0).takeWhile { it }.size
            assertEquals(ref, ProgressMath.streak(history, rule, today))
        }
    }

    /** Property 19: every listed Task is counted exactly once. */
    @Test
    fun `evening review counts add up to the listed tasks`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.list(Arb.element(TaskStatus.entries), 0..20),
            Arb.list(Arb.int(0..3), 0..20),
        ) { statuses, picks ->
            val tasks = statuses.mapIndexed { i, s -> Task(TaskId(i.toLong()), "t", s, null, today, null, null, Instant.EPOCH, null) }
            var draft = ReviewDraft(today)
            // 0 = no choice, 1..3 = DONE, MOVE_TO_TOMORROW, DROP
            tasks.forEachIndexed { i, t -> draft = draft.choose(t.id, picks.getOrElse(i) { 0 }.let { p -> ReviewChoice.entries.getOrNull(p - 1) }) }
            draft = draft.choose(TaskId(999), ReviewChoice.DONE) // not listed: ignored
            val s = ReviewEngine.summarise(tasks, draft)
            assertEquals(tasks.size, s.total)
        }
    }
}

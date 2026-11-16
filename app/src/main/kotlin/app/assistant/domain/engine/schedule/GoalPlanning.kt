package app.assistant.domain.engine.schedule

import app.assistant.domain.model.Goal
import app.assistant.domain.model.Milestone
import app.assistant.domain.port.GoalWork
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class MilestoneProgress(val milestone: Milestone, val tasksDone: Int, val tasksTotal: Int)

/** A goal's plan at a glance (Module A). Minutes, not hours, so nothing is a float until display. */
data class GoalPlanView(
    val minutesDone: Int,
    val minutesPlanned: Int,
    val percent: Int,
    val forecastFinish: LocalDate?,
    val atRisk: Boolean,
    val milestones: List<MilestoneProgress>,
    val nextMilestone: Milestone?,
)

/** A checkpoint the planner proposes; the User reviews and accepts the whole list. */
data class ProposedMilestone(val title: String, val dueDate: LocalDate, val estimateMinutes: Int?)

object GoalPlanning {
    /** Minutes a day the User can give a goal, by its priority (1 highest). */
    fun dailyPace(priority: Int): Int = when (priority.coerceIn(1, PRIORITY_MAX)) {
        1 -> 120
        2 -> 90
        3 -> 60
        4 -> 45
        else -> 30
    }

    /**
     * Progress maths: done and planned minutes over the goal's tasks (a task with no estimate counts
     * [DEFAULT_TASK_MIN]); the forecast finishes the remaining minutes at [dailyPace]; the goal is
     * at risk when that forecast lands after its deadline.
     */
    fun view(goal: Goal, milestones: List<Milestone>, work: List<GoalWork>, today: LocalDate, statusPercent: Int): GoalPlanView {
        val mine = work.filter { it.goal == goal.id }
        val planned = mine.sumOf { it.estimateMinutes ?: DEFAULT_TASK_MIN }
        val done = mine.filter { it.done }.sumOf { it.estimateMinutes ?: DEFAULT_TASK_MIN }
        val remaining = planned - done
        val forecast = if (remaining > 0) today.plusDays(((remaining + dailyPace(goal.priority) - 1) / dailyPace(goal.priority)).toLong()) else null
        // Task-counted goals follow their work; quantity goals (books, km) follow their logged progress.
        val percent = if (goal.countsTasks && planned > 0) done * PERCENT / planned else statusPercent
        val rows = milestones.sortedBy { it.position }.map { m ->
            val t = mine.filter { it.milestone == m.id }
            MilestoneProgress(m, t.count { it.done }, t.size)
        }
        return GoalPlanView(
            minutesDone = done,
            minutesPlanned = planned,
            percent = percent.coerceIn(0, PERCENT),
            forecastFinish = forecast,
            atRisk = goal.deadline != null && forecast != null && forecast.isAfter(goal.deadline),
            milestones = rows,
            nextMilestone = rows.firstOrNull { !it.milestone.done }?.milestone,
        )
    }

    /**
     * The rule-based "Plan it for me" (used when AI is off). A numeric goal gets evenly paced
     * checkpoints from [PacePlanner]; any other goal gets three dated steps across the time to its
     * deadline (or four weeks): set the outcome, do the work, review and finish.
     */
    fun propose(goal: Goal, today: LocalDate, progressSoFar: Long, stepTitles: StepTitles): List<ProposedMilestone> {
        val deadline = goal.deadline?.takeIf { it.isAfter(today) } ?: today.plusWeeks(DEFAULT_WEEKS)
        val target = goal.target.hundredths / HUNDREDTHS
        if (!goal.countsTasks && target > 1) {
            return PacePlanner.plan(today, deadline, target, progressSoFar / HUNDREDTHS).map { step ->
                ProposedMilestone(stepTitles.reach(step.cumulativeTarget, goal.unit), step.dueDate, null)
            }
        }
        val days = ChronoUnit.DAYS.between(today, deadline).coerceAtLeast(MIN_SPAN_DAYS)
        val pace = dailyPace(goal.priority)
        return listOf(
            ProposedMilestone(stepTitles.define, today.plusDays(days / DEFINE_SHARE), pace),
            ProposedMilestone(stepTitles.build, today.plusDays(days * BUILD_NUM / BUILD_DEN), pace * WORK_DAYS),
            ProposedMilestone(stepTitles.finish, deadline, pace),
        )
    }

    /** The localised step titles the UI passes in, so the engine stays free of resources. */
    interface StepTitles {
        val define: String
        val build: String
        val finish: String
        fun reach(amount: Long, unit: String): String
    }

    const val DEFAULT_TASK_MIN = 30
    private const val PERCENT = 100
    private const val PRIORITY_MAX = 5
    private const val HUNDREDTHS = 100
    private const val DEFAULT_WEEKS = 4L
    private const val MIN_SPAN_DAYS = 3L
    private const val DEFINE_SHARE = 5
    private const val BUILD_NUM = 4
    private const val BUILD_DEN = 5
    private const val WORK_DAYS = 5
}

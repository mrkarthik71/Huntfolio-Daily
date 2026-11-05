package app.assistant.domain.engine.review

import app.assistant.domain.engine.goals.Periods
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import java.time.DayOfWeek
import java.time.LocalDate

enum class ReviewChoice { DONE, MOVE_TO_TOMORROW, DROP }

/** The User's choices so far; nothing is written until the review is finished (Requirement 16.7). */
data class ReviewDraft(val reviewDate: LocalDate, val choices: Map<TaskId, ReviewChoice> = emptyMap()) {
    fun choose(task: TaskId, choice: ReviewChoice?): ReviewDraft = copy(choices = if (choice == null) choices - task else choices + (task to choice))
}

data class ReviewSummary(val done: Int, val moved: Int, val dropped: Int, val leftOpen: Int) {
    val total: Int get() = done + moved + dropped + leftOpen
}

object ReviewEngine {
    /**
     * Each listed Task counts exactly once (Requirement 16.4). A Task already done counts as done;
     * an open Task with no choice is left open. Choices for Tasks not listed are ignored.
     */
    fun summarise(listed: List<Task>, draft: ReviewDraft): ReviewSummary {
        var done = 0
        var moved = 0
        var dropped = 0
        var open = 0
        for (t in listed.distinctBy { it.id }) {
            when {
                t.status == TaskStatus.DONE -> done++
                t.status == TaskStatus.DROPPED -> dropped++
                else -> when (draft.choices[t.id]) {
                    ReviewChoice.DONE -> done++
                    ReviewChoice.MOVE_TO_TOMORROW -> moved++
                    ReviewChoice.DROP -> dropped++
                    null -> open++
                }
            }
        }
        return ReviewSummary(done, moved, dropped, open)
    }
}

/** Inputs for the weekly progress report, read from stored data for one week (Requirement 17.5). */
data class ProgressReportData(
    val weekGoals: List<WeekGoalRow>,
    /** Task_Move counts recorded during the week, per Task. */
    val movesByTask: Map<TaskId, Pair<String, Int>>,
)

data class WeekGoalRow(val name: String, val target: Quantity, val progress: Quantity, val unit: String)

data class WeeklyProgressReport(
    val week: PeriodInstance,
    val goals: List<WeekGoalRow>,
    /** Titles of Tasks moved 2 or more times during the week, with the move count. */
    val slipped: List<Pair<String, Int>>,
)

object ReportCalculator {
    /** A pure function of stored data, so a late report equals an on-time one (Requirement 17.6). */
    fun weeklyProgress(week: PeriodInstance, data: ProgressReportData): WeeklyProgressReport = WeeklyProgressReport(
        week = week,
        goals = data.weekGoals.sortedBy { it.name.lowercase() },
        slipped = data.movesByTask.values.filter { it.second >= 2 }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }),
    )

    /** Every week that ended after [since] and on or before [today] (exclusive of the current week). */
    fun endedWeeks(since: LocalDate?, today: LocalDate, lastDayOfWeek: DayOfWeek, firstEver: LocalDate): List<PeriodInstance> {
        val current = Periods.instanceContaining(GoalPeriod.WEEK, today, lastDayOfWeek)
        var last = current.first.minusDays(1)
        val stop = since ?: firstEver.minusDays(1)
        val out = ArrayList<PeriodInstance>()
        while (last.isAfter(stop) && !last.isBefore(firstEver)) {
            out += Periods.weekEndingOn(last)
            last = last.minusDays(7)
        }
        return out.reversed()
    }
}

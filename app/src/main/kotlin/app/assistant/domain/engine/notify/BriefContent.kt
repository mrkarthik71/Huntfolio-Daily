package app.assistant.domain.engine.notify

import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.GoalStatus

/** Morning brief content (Requirement 20.1): the Priority count and the first title. */
data class MorningBrief(val priorityCount: Int, val firstTitle: String?)

/** Goal nudge content (Requirement 20.5): one Goal and its Days_Behind. */
data class GoalNudge(val goalName: String, val daysBehind: Int)

object BriefContent {
    /** At most one goal nudge is shown per day (Requirement 20.5). */
    const val GOAL_NUDGE_DAILY_LIMIT = 1

    fun morningBrief(priorityTitles: List<String>): MorningBrief = MorningBrief(priorityTitles.size, priorityTitles.firstOrNull())

    /**
     * The active week Goal with the highest Days_Behind, only when it is 2 or more; ties go to the
     * earliest-created Goal (Requirement 20.5).
     */
    fun goalNudge(statuses: List<GoalStatus>): GoalNudge? = statuses
        .filter { it.goal.isActive && it.goal.period == GoalPeriod.WEEK && it.daysBehind >= 2 }
        .sortedWith(compareByDescending<GoalStatus> { it.daysBehind }.thenBy { it.goal.createdAt }.thenBy { it.goal.id.value })
        .firstOrNull()
        ?.let { GoalNudge(it.goal.name, it.daysBehind) }
}

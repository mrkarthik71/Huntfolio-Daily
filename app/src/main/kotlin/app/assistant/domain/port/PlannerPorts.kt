package app.assistant.domain.port

import app.assistant.domain.model.CardId
import app.assistant.domain.model.CardKind
import app.assistant.domain.model.CardStatus
import app.assistant.domain.model.EnergyTag
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.Milestone
import app.assistant.domain.model.MilestoneId
import app.assistant.domain.model.ProactiveCard
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.Routine
import app.assistant.domain.model.RoutineId
import app.assistant.domain.model.SmartReminder
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.VerifyKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** A new proactive card the agent wants to show for [forDate]; ignored when that kind already exists for that day. */
data class CardDraft(val kind: CardKind, val forDate: LocalDate, val title: String, val reason: String)

/** One goal-linked task, any status, for goal progress maths. */
data class GoalWork(val taskId: TaskId, val goal: GoalId, val milestone: MilestoneId?, val done: Boolean, val estimateMinutes: Int?)

/** The Apex planner store: milestones, task planning fields, routines, smart reminders, proactive cards, proofs. */
interface PlannerRepository {
    fun milestones(goal: GoalId): Flow<List<Milestone>>

    fun allMilestones(): Flow<List<Milestone>>

    /** Every task linked to a goal, done or not. */
    fun goalWork(): Flow<List<GoalWork>>

    suspend fun addMilestone(goal: GoalId, title: String, dueDate: LocalDate?, estimateMinutes: Int?): MilestoneId

    suspend fun setMilestoneDone(id: MilestoneId, done: Boolean, now: Instant)

    suspend fun deleteMilestone(id: MilestoneId)

    suspend fun setTaskPlanning(task: TaskId, priority: Int, energy: EnergyTag, milestone: MilestoneId?, verify: VerifyKind)

    suspend fun setGoalPlanning(goal: GoalId, deadline: LocalDate?, priority: Int)

    fun routines(): Flow<List<Routine>>

    suspend fun saveRoutine(routine: Routine): RoutineId

    suspend fun deleteRoutine(id: RoutineId)

    fun reminders(): Flow<List<SmartReminder>>

    suspend fun saveReminder(reminder: SmartReminder): ReminderId

    suspend fun setReminderActive(id: ReminderId, active: Boolean)

    suspend fun deleteReminder(id: ReminderId)

    fun openCards(from: LocalDate): Flow<List<ProactiveCard>>

    /** Inserts the drafts that are new for their kind and day; returns how many were added. */
    suspend fun offerCards(drafts: List<CardDraft>, now: Instant): Int

    suspend fun setCardStatus(id: CardId, status: CardStatus)

    suspend fun addProof(task: TaskId, kind: VerifyKind, link: String?, trackedSeconds: Int?, photoFile: String?, now: Instant)
}

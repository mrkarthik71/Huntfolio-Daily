package app.assistant.testing

import app.assistant.domain.model.CardId
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
import app.assistant.domain.port.CardDraft
import app.assistant.domain.port.PlannerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/** In-memory [PlannerRepository] for ViewModel and screen tests. */
class FakePlannerRepository(
    milestones: List<Milestone> = emptyList(),
    routines: List<Routine> = emptyList(),
    reminders: List<SmartReminder> = emptyList(),
    cards: List<ProactiveCard> = emptyList(),
) : PlannerRepository {
    val milestonesState = MutableStateFlow(milestones)
    val routinesState = MutableStateFlow(routines)
    val remindersState = MutableStateFlow(reminders)
    val cardsState = MutableStateFlow(cards)
    val planning = mutableMapOf<TaskId, Triple<Int, EnergyTag, VerifyKind>>()
    private var nextId = 500L

    override fun milestones(goal: GoalId): Flow<List<Milestone>> = milestonesState.map { l -> l.filter { it.goalId == goal }.sortedBy { it.position } }

    override fun allMilestones(): Flow<List<Milestone>> = milestonesState

    override suspend fun addMilestone(goal: GoalId, title: String, dueDate: LocalDate?, estimateMinutes: Int?): MilestoneId {
        val id = MilestoneId(nextId++)
        val position = milestonesState.value.count { it.goalId == goal }
        milestonesState.value = milestonesState.value + Milestone(id, goal, title, position, dueDate, estimateMinutes, null)
        return id
    }

    override suspend fun setMilestoneDone(id: MilestoneId, done: Boolean, now: Instant) {
        milestonesState.value = milestonesState.value.map { if (it.id == id) it.copy(completedAt = if (done) now else null) else it }
    }

    override suspend fun deleteMilestone(id: MilestoneId) {
        milestonesState.value = milestonesState.value.filterNot { it.id == id }
    }

    override suspend fun setTaskPlanning(task: TaskId, priority: Int, energy: EnergyTag, milestone: MilestoneId?, verify: VerifyKind) {
        planning[task] = Triple(priority, energy, verify)
    }

    override suspend fun setGoalPlanning(goal: GoalId, deadline: LocalDate?, priority: Int) = Unit

    override fun routines(): Flow<List<Routine>> = routinesState

    override suspend fun saveRoutine(routine: Routine): RoutineId {
        val id = if (routine.id.value == 0L) RoutineId(nextId++) else routine.id
        routinesState.value = routinesState.value.filterNot { it.id == id } + routine.copy(id = id)
        return id
    }

    override suspend fun deleteRoutine(id: RoutineId) {
        routinesState.value = routinesState.value.filterNot { it.id == id }
    }

    override fun reminders(): Flow<List<SmartReminder>> = remindersState

    override suspend fun saveReminder(reminder: SmartReminder): ReminderId {
        val id = if (reminder.id.value == 0L) ReminderId(nextId++) else reminder.id
        remindersState.value = remindersState.value.filterNot { it.id == id } + reminder.copy(id = id)
        return id
    }

    override suspend fun setReminderActive(id: ReminderId, active: Boolean) {
        remindersState.value = remindersState.value.map { if (it.id == id) it.copy(active = active) else it }
    }

    override suspend fun deleteReminder(id: ReminderId) {
        remindersState.value = remindersState.value.filterNot { it.id == id }
    }

    override fun openCards(from: LocalDate): Flow<List<ProactiveCard>> =
        cardsState.map { l -> l.filter { it.status == CardStatus.NEW && !it.forDate.isBefore(from) } }

    override suspend fun offerCards(drafts: List<CardDraft>, now: Instant): Int {
        val fresh = drafts.filter { d -> cardsState.value.none { it.kind == d.kind && it.forDate == d.forDate } }
        cardsState.value = cardsState.value + fresh.map { ProactiveCard(CardId(nextId++), it.kind, it.forDate, it.title, it.reason, CardStatus.NEW) }
        return fresh.size
    }

    override suspend fun setCardStatus(id: CardId, status: CardStatus) {
        cardsState.value = cardsState.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    override suspend fun addProof(task: TaskId, kind: VerifyKind, link: String?, trackedSeconds: Int?, photoFile: String?, now: Instant) = Unit
}

package app.assistant.data.repo

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.MilestoneEntity
import app.assistant.data.db.entity.ProactiveCardEntity
import app.assistant.data.db.entity.RoutineEntity
import app.assistant.data.db.entity.SmartReminderEntity
import app.assistant.data.db.entity.TaskVerificationEntity
import app.assistant.domain.model.CardId
import app.assistant.domain.model.CardKind
import app.assistant.domain.model.CardStatus
import app.assistant.domain.model.DeviceTrigger
import app.assistant.domain.model.EnergyTag
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.Milestone
import app.assistant.domain.model.MilestoneId
import app.assistant.domain.model.ProactiveCard
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.model.Routine
import app.assistant.domain.model.RoutineId
import app.assistant.domain.model.SmartReminder
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.VerifyKind
import app.assistant.domain.port.CardDraft
import app.assistant.domain.port.PlannerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Room implementation of [PlannerRepository]. */
class RoomPlannerRepository @Inject constructor(private val holder: DbProvider) : PlannerRepository {
    private val dao get() = holder.database().apexDao()

    override fun milestones(goal: GoalId): Flow<List<Milestone>> = dao.milestones(goal.value).map { l -> l.map { it.toDomain() } }

    override fun allMilestones(): Flow<List<Milestone>> = dao.allMilestones().map { l -> l.map { it.toDomain() } }

    override fun goalWork(): Flow<List<app.assistant.domain.port.GoalWork>> = dao.goalWork().map { l ->
        l.map { app.assistant.domain.port.GoalWork(TaskId(it.id), GoalId(it.goalId), it.milestoneId?.let(::MilestoneId), it.status == "DONE", it.estimateMinutes) }
    }

    override suspend fun addMilestone(goal: GoalId, title: String, dueDate: LocalDate?, estimateMinutes: Int?): MilestoneId {
        val position = dao.nextPosition(goal.value)
        return MilestoneId(dao.insertMilestone(MilestoneEntity(0, goal.value, title.trim().take(MAX_TITLE), position, dueDate?.toString(), estimateMinutes, null)))
    }

    override suspend fun setMilestoneDone(id: MilestoneId, done: Boolean, now: Instant) {
        val m = dao.milestone(id.value) ?: return
        dao.updateMilestone(m.copy(completedAt = if (done) now.toEpochMilli() else null))
    }

    override suspend fun deleteMilestone(id: MilestoneId) = dao.deleteMilestone(id.value)

    override suspend fun setTaskPlanning(task: TaskId, priority: Int, energy: EnergyTag, milestone: MilestoneId?, verify: VerifyKind) =
        dao.setTaskPlanning(task.value, priority.coerceIn(1, MAX_PRIORITY), energy.name, milestone?.value, verify.name)

    override suspend fun setGoalPlanning(goal: GoalId, deadline: LocalDate?, priority: Int) =
        dao.setGoalPlanning(goal.value, deadline?.toString(), priority.coerceIn(1, MAX_PRIORITY))

    override fun routines(): Flow<List<Routine>> = dao.routines().map { l -> l.map { it.toDomain() } }

    override suspend fun saveRoutine(routine: Routine): RoutineId = RoutineId(
        dao.upsertRoutine(
            RoutineEntity(
                id = routine.id.value,
                title = routine.title.trim().take(MAX_TITLE),
                energy = routine.energy.name,
                daysMask = routine.days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) },
                startMinute = routine.start.toSecondOfDay() / SECONDS_PER_MINUTE,
                durationMinutes = routine.durationMinutes.coerceIn(MIN_DURATION, MAX_DURATION),
                flexibleMinutes = routine.flexibleMinutes.coerceIn(0, MAX_DURATION),
                active = routine.active,
            ),
        ),
    )

    override suspend fun deleteRoutine(id: RoutineId) = dao.deleteRoutine(id.value)

    override fun reminders(): Flow<List<SmartReminder>> = dao.reminders().map { l -> l.map { it.toDomain() } }

    override suspend fun saveReminder(reminder: SmartReminder): ReminderId = ReminderId(
        dao.upsertReminder(
            SmartReminderEntity(
                id = reminder.id.value,
                message = reminder.message.trim().take(MAX_TITLE),
                kind = reminder.kind.name,
                fireAt = reminder.fireAt?.toEpochMilli(),
                placeName = reminder.placeName,
                latitude = reminder.latitude,
                longitude = reminder.longitude,
                radiusMeters = reminder.radiusMeters,
                onEnter = reminder.onEnter,
                device = reminder.device?.name,
                notBeforeMinute = reminder.notBefore?.let { it.toSecondOfDay() / SECONDS_PER_MINUTE },
                active = reminder.active,
                taskId = reminder.taskId?.value,
                lastFiredAt = null,
            ),
        ),
    )

    override suspend fun setReminderActive(id: ReminderId, active: Boolean) = dao.setReminderActive(id.value, active)

    override suspend fun deleteReminder(id: ReminderId) = dao.deleteReminder(id.value)

    override fun openCards(from: LocalDate): Flow<List<ProactiveCard>> = dao.openCards(from.toString()).map { l -> l.map { it.toDomain() } }

    override suspend fun offerCards(drafts: List<CardDraft>, now: Instant): Int = drafts.count { d ->
        dao.insertCard(ProactiveCardEntity(0, d.kind.name, d.forDate.toString(), d.title, d.reason, CardStatus.NEW.name, now.toEpochMilli())) > 0
    }

    override suspend fun setCardStatus(id: CardId, status: CardStatus) = dao.setCardStatus(id.value, status.name)

    override suspend fun addProof(task: TaskId, kind: VerifyKind, link: String?, trackedSeconds: Int?, photoFile: String?, now: Instant) {
        dao.insertVerification(TaskVerificationEntity(0, task.value, kind.name, link?.takeIf { it.startsWith("https://") }, trackedSeconds, photoFile, now.toEpochMilli()))
    }

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_PRIORITY = 5
        const val MIN_DURATION = 5
        const val MAX_DURATION = 480
        const val SECONDS_PER_MINUTE = 60
    }
}

private fun MilestoneEntity.toDomain() = Milestone(
    MilestoneId(id), GoalId(goalId), title, position, dueDate?.let(LocalDate::parse), estimateMinutes, completedAt?.let(Instant::ofEpochMilli),
)

private fun RoutineEntity.toDomain() = Routine(
    id = RoutineId(id),
    title = title,
    energy = runCatching { EnergyTag.valueOf(energy) }.getOrDefault(EnergyTag.LIGHT_ADMIN),
    days = DayOfWeek.entries.filter { daysMask and (1 shl (it.value - 1)) != 0 }.toSet(),
    start = LocalTime.ofSecondOfDay(startMinute * 60L),
    durationMinutes = durationMinutes,
    flexibleMinutes = flexibleMinutes,
    active = active,
)

private fun SmartReminderEntity.toDomain() = SmartReminder(
    id = ReminderId(id),
    message = message,
    kind = runCatching { ReminderKind.valueOf(kind) }.getOrDefault(ReminderKind.TIME),
    fireAt = fireAt?.let(Instant::ofEpochMilli),
    placeName = placeName,
    latitude = latitude,
    longitude = longitude,
    radiusMeters = radiusMeters,
    onEnter = onEnter,
    device = device?.let { d -> runCatching { DeviceTrigger.valueOf(d) }.getOrNull() },
    notBefore = notBeforeMinute?.let { LocalTime.ofSecondOfDay(it * 60L) },
    active = active,
    taskId = taskId?.let(::TaskId),
)

private fun ProactiveCardEntity.toDomain() = ProactiveCard(
    CardId(id),
    runCatching { CardKind.valueOf(kind) }.getOrDefault(CardKind.OVERLOAD),
    LocalDate.parse(forDate),
    title,
    reason,
    runCatching { CardStatus.valueOf(status) }.getOrDefault(CardStatus.NEW),
)

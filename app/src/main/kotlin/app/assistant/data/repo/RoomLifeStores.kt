package app.assistant.data.repo

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.FocusSessionEntity
import app.assistant.data.db.entity.FollowUpEntity
import app.assistant.data.db.entity.HabitCheckEntity
import app.assistant.data.db.entity.HabitEntity
import app.assistant.data.db.entity.ImportantDateEntity
import app.assistant.data.db.entity.MoodLogEntity
import app.assistant.data.db.entity.SavingsEntryEntity
import app.assistant.data.db.entity.SavingsGoalEntity
import app.assistant.domain.model.DateKind
import app.assistant.domain.model.FocusSession
import app.assistant.domain.model.FollowUp
import app.assistant.domain.model.Habit
import app.assistant.domain.model.HabitCheck
import app.assistant.domain.model.HabitKind
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.MoodEntry
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SavingsEntry
import app.assistant.domain.model.SavingsGoal
import app.assistant.domain.port.DateStore
import app.assistant.domain.port.FollowUpStore
import app.assistant.domain.port.HabitStore
import app.assistant.domain.port.ReflectionStore
import app.assistant.domain.port.SavingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

private const val MAX_TEXT = 200

/** Room's @Upsert returns -1 for an update: keep the existing id then. */
private fun idAfterUpsert(existing: Long, result: Long): Long = if (existing != 0L) existing else result

private fun HabitEntity.toDomain() = Habit(
    id,
    name,
    HabitKind.valueOf(kind),
    times.split(',').filter { it.isNotBlank() }.map(LocalTime::parse),
    days.split(',').filter { it.isNotBlank() }.map(DayOfWeek::valueOf).toSet(),
    active,
    LocalDate.parse(createdOn),
)

class RoomHabitStore @Inject constructor(private val holder: DbProvider) : HabitStore {
    private val dao get() = holder.database().lifeDao()

    override fun habits(): Flow<List<Habit>> = dao.habits().map { l -> l.map { it.toDomain() } }

    override fun checks(from: LocalDate, to: LocalDate): Flow<List<HabitCheck>> =
        dao.checks(from.toString(), to.toString()).map { l -> l.map { HabitCheck(it.habitId, LocalDate.parse(it.date), LocalTime.parse(it.slot)) } }

    override suspend fun habitsNow(): List<Habit> = dao.habitsNow().map { it.toDomain() }

    override suspend fun get(id: Long): Habit? = dao.habit(id)?.toDomain()

    override suspend fun save(habit: Habit): Long = idAfterUpsert(
        habit.id,
        dao.upsertHabit(
            HabitEntity(
                id = habit.id,
                name = habit.name.trim().take(MAX_TEXT),
                kind = habit.kind.name,
                times = habit.times.distinct().sorted().joinToString(","),
                days = habit.days.sorted().joinToString(",") { it.name },
                active = habit.active,
                createdOn = habit.createdOn.toString(),
            ),
        ),
    )

    override suspend fun delete(id: Long) = dao.deleteHabit(id)

    override suspend fun setChecked(habitId: Long, date: LocalDate, slot: LocalTime, done: Boolean) {
        if (done) dao.insertCheck(HabitCheckEntity(habitId, date.toString(), slot.toString())) else dao.deleteCheck(habitId, date.toString(), slot.toString())
    }
}

private fun ImportantDateEntity.toDomain() = ImportantDate(id, title, DateKind.valueOf(kind), LocalDate.parse(date), yearly, fromContacts)

class RoomDateStore @Inject constructor(private val holder: DbProvider) : DateStore {
    private val dao get() = holder.database().lifeDao()

    override fun dates(): Flow<List<ImportantDate>> = dao.dates().map { l -> l.map { it.toDomain() } }

    override suspend fun datesNow(): List<ImportantDate> = dao.datesNow().map { it.toDomain() }

    override suspend fun get(id: Long): ImportantDate? = dao.date(id)?.toDomain()

    override suspend fun save(date: ImportantDate): Long = idAfterUpsert(
        date.id,
        dao.upsertDate(ImportantDateEntity(date.id, date.title.trim().take(MAX_TEXT), date.kind.name, date.date.toString(), date.yearly, date.fromContacts)),
    )

    override suspend fun delete(id: Long) = dao.deleteDate(id)

    override suspend fun importContacts(dates: List<ImportantDate>): Int {
        val existing = dao.datesNow().map { it.title.lowercase() to it.date.substring(MONTH_DAY_START) }.toSet()
        var added = 0
        for (d in dates) {
            val key = d.title.trim().lowercase() to d.date.toString().substring(MONTH_DAY_START)
            if (key in existing) continue
            save(d.copy(id = 0, fromContacts = true))
            added++
        }
        return added
    }

    private companion object {
        /** "YYYY-MM-DD" from the month on, so a contact birthday without a year still matches. */
        const val MONTH_DAY_START = 5
    }
}

class RoomSavingsStore @Inject constructor(private val holder: DbProvider) : SavingsStore {
    private val dao get() = holder.database().lifeDao()

    override fun goals(): Flow<List<SavingsGoal>> = dao.goals().map { l ->
        l.map { SavingsGoal(it.id, it.name, Paise(it.targetPaise), it.deadline?.let(LocalDate::parse), LocalDate.parse(it.createdOn)) }
    }

    override fun entries(): Flow<List<SavingsEntry>> = dao.entries().map { l ->
        l.map { SavingsEntry(it.id, it.goalId, Paise(it.amountPaise), LocalDate.parse(it.date)) }
    }

    override suspend fun save(goal: SavingsGoal): Long = idAfterUpsert(
        goal.id,
        dao.upsertGoal(SavingsGoalEntity(goal.id, goal.name.trim().take(MAX_TEXT), goal.target.value, goal.deadline?.toString(), goal.createdOn.toString())),
    )

    override suspend fun delete(id: Long) = dao.deleteGoal(id)

    override suspend fun addEntry(goalId: Long, amount: Paise, date: LocalDate) {
        dao.insertEntry(SavingsEntryEntity(goalId = goalId, amountPaise = amount.value, date = date.toString()))
    }
}

private fun FollowUpEntity.toDomain() = FollowUp(id, person, about, LocalDate.parse(nudgeOn), done, LocalDate.parse(createdOn))

class RoomFollowUpStore @Inject constructor(private val holder: DbProvider) : FollowUpStore {
    private val dao get() = holder.database().lifeDao()

    override fun followUps(): Flow<List<FollowUp>> = dao.followUps().map { l -> l.map { it.toDomain() } }

    override suspend fun openNow(): List<FollowUp> = dao.openFollowUps().map { it.toDomain() }

    override suspend fun get(id: Long): FollowUp? = dao.followUp(id)?.toDomain()

    override suspend fun save(followUp: FollowUp): Long = idAfterUpsert(
        followUp.id,
        dao.upsertFollowUp(
            FollowUpEntity(
                followUp.id,
                followUp.person.trim().take(MAX_TEXT),
                followUp.about.trim().take(MAX_TEXT),
                followUp.nudgeOn.toString(),
                followUp.done,
                followUp.createdOn.toString(),
            ),
        ),
    )

    override suspend fun setDone(id: Long, done: Boolean) = dao.setFollowUpDone(id, done)
}

private fun FocusSessionEntity.toDomain() =
    FocusSession(id, title, taskId, Instant.ofEpochMilli(startedAt), plannedMinutes, endedAt?.let(Instant::ofEpochMilli), completed)

class RoomReflectionStore @Inject constructor(private val holder: DbProvider) : ReflectionStore {
    private val dao get() = holder.database().lifeDao()

    override fun moods(from: LocalDate, to: LocalDate): Flow<List<MoodEntry>> =
        dao.moods(from.toString(), to.toString()).map { l -> l.map { MoodEntry(LocalDate.parse(it.date), it.mood, it.energy) } }

    override suspend fun saveMood(entry: MoodEntry) {
        dao.upsertMood(MoodLogEntity(entry.date.toString(), entry.mood.coerceIn(MoodEntry.MOOD_RANGE), entry.energy.coerceIn(MoodEntry.ENERGY_RANGE)))
    }

    override fun sessions(from: Instant, to: Instant): Flow<List<FocusSession>> =
        dao.sessions(from.toEpochMilli(), to.toEpochMilli()).map { l -> l.map { it.toDomain() } }

    override suspend fun running(): FocusSession? = dao.running()?.toDomain()

    override suspend fun start(title: String, taskId: Long?, at: Instant, plannedMinutes: Int): Long {
        // Only one block runs at a time: starting a new one ends the old one, not completed.
        dao.running()?.let { dao.endSession(it.id, at.toEpochMilli(), completed = false) }
        return dao.insertSession(
            FocusSessionEntity(
                title = title.trim().take(MAX_TEXT),
                taskId = taskId,
                startedAt = at.toEpochMilli(),
                plannedMinutes = plannedMinutes,
                endedAt = null,
                completed = false,
            ),
        )
    }

    override suspend fun end(id: Long, at: Instant, completed: Boolean) = dao.endSession(id, at.toEpochMilli(), completed)
}

private fun app.assistant.data.db.entity.HoldingEntity.toDomain() = app.assistant.domain.model.Holding(
    id,
    name,
    app.assistant.domain.model.HoldingKind.valueOf(kind),
    Paise(valuePaise),
    Paise(monthlyPaise),
    dueDay,
    LocalDate.parse(updatedOn),
)

class RoomHoldingStore @Inject constructor(private val holder: DbProvider) : app.assistant.domain.port.HoldingStore {
    private val dao get() = holder.database().lifeDao()

    override fun holdings(): Flow<List<app.assistant.domain.model.Holding>> = dao.holdings().map { l -> l.map { it.toDomain() } }

    override suspend fun holdingsNow(): List<app.assistant.domain.model.Holding> = dao.holdingsNow().map { it.toDomain() }

    override suspend fun get(id: Long): app.assistant.domain.model.Holding? = dao.holding(id)?.toDomain()

    override suspend fun save(holding: app.assistant.domain.model.Holding): Long = idAfterUpsert(
        holding.id,
        dao.upsertHolding(
            app.assistant.data.db.entity.HoldingEntity(
                holding.id,
                holding.name.trim().take(MAX_TEXT),
                holding.kind.name,
                holding.value.value,
                holding.monthly.value,
                holding.dueDay?.coerceIn(1, app.assistant.domain.engine.life.NetWorthRules.MAX_DUE_DAY),
                holding.updatedOn.toString(),
            ),
        ),
    )

    override suspend fun delete(id: Long) = dao.deleteHolding(id)
}

class RoomVaultStore @Inject constructor(private val holder: DbProvider) : app.assistant.domain.port.VaultStore {
    private val dao get() = holder.database().lifeDao()

    private fun app.assistant.data.db.entity.VaultItemEntity.toDomain() =
        app.assistant.domain.model.VaultItem(id, title, dateId, fileName, mime, Instant.ofEpochMilli(createdAt))

    override fun items(): Flow<List<app.assistant.domain.model.VaultItem>> = dao.vaultItems().map { l -> l.map { it.toDomain() } }

    override suspend fun add(title: String, dateId: Long?, fileName: String, mime: String, at: Instant): Long = dao.insertVaultItem(
        app.assistant.data.db.entity.VaultItemEntity(
            title = title.trim().take(MAX_TEXT),
            dateId = dateId,
            fileName = fileName,
            mime = mime,
            createdAt = at.toEpochMilli(),
        ),
    )

    override suspend fun get(id: Long): app.assistant.domain.model.VaultItem? = dao.vaultItem(id)?.toDomain()

    override suspend fun delete(id: Long) = dao.deleteVaultItem(id)
}

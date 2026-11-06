package app.assistant.domain.port

import app.assistant.domain.model.FocusSession
import app.assistant.domain.model.FollowUp
import app.assistant.domain.model.Habit
import app.assistant.domain.model.HabitCheck
import app.assistant.domain.model.ImportantDate
import app.assistant.domain.model.MoodEntry
import app.assistant.domain.model.SavingsEntry
import app.assistant.domain.model.SavingsGoal
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

interface HabitStore {
    fun habits(): Flow<List<Habit>>

    fun checks(from: LocalDate, to: LocalDate): Flow<List<HabitCheck>>

    suspend fun habitsNow(): List<Habit>

    suspend fun get(id: Long): Habit?

    suspend fun save(habit: Habit): Long

    suspend fun delete(id: Long)

    suspend fun setChecked(habitId: Long, date: LocalDate, slot: LocalTime, done: Boolean)
}

interface DateStore {
    fun dates(): Flow<List<ImportantDate>>

    suspend fun datesNow(): List<ImportantDate>

    suspend fun get(id: Long): ImportantDate?

    suspend fun save(date: ImportantDate): Long

    suspend fun delete(id: Long)

    /** Adds contact birthdays not already present (matched by title and date). Returns how many were added. */
    suspend fun importContacts(dates: List<ImportantDate>): Int
}

interface SavingsStore {
    fun goals(): Flow<List<SavingsGoal>>

    fun entries(): Flow<List<SavingsEntry>>

    suspend fun save(goal: SavingsGoal): Long

    suspend fun delete(id: Long)

    suspend fun addEntry(goalId: Long, amount: app.assistant.domain.model.Paise, date: LocalDate)
}

interface FollowUpStore {
    fun followUps(): Flow<List<FollowUp>>

    suspend fun openNow(): List<FollowUp>

    suspend fun get(id: Long): FollowUp?

    suspend fun save(followUp: FollowUp): Long

    suspend fun setDone(id: Long, done: Boolean)
}

interface ReflectionStore {
    fun moods(from: LocalDate, to: LocalDate): Flow<List<MoodEntry>>

    suspend fun saveMood(entry: MoodEntry)

    fun sessions(from: Instant, to: Instant): Flow<List<FocusSession>>

    suspend fun running(): FocusSession?

    suspend fun start(title: String, taskId: Long?, at: Instant, plannedMinutes: Int): Long

    suspend fun end(id: Long, at: Instant, completed: Boolean)
}

/** Reads birthdays and anniversaries from the phone's contacts (needs the contacts permission the User grants). */
interface ContactDatesReader {
    suspend fun read(): List<ImportantDate>
}

/** Keeps the alarm queue in step with habits, important dates, follow-ups and a running focus block. */
fun interface LifeAlarms {
    suspend fun sync()
}

/** Read-only body signals from Health Connect, when the User connected it. Null means unknown. */
interface BodySignals {
    /** True when Health Connect is installed and the User granted the read permissions. */
    suspend fun connected(): Boolean

    /** Minutes slept in the night that ended this morning. */
    suspend fun lastNightSleepMinutes(): Int?

    suspend fun stepsToday(): Long?
}

interface HoldingStore {
    fun holdings(): kotlinx.coroutines.flow.Flow<List<app.assistant.domain.model.Holding>>

    suspend fun holdingsNow(): List<app.assistant.domain.model.Holding>

    suspend fun get(id: Long): app.assistant.domain.model.Holding?

    suspend fun save(holding: app.assistant.domain.model.Holding): Long

    suspend fun delete(id: Long)
}

/** The vault's index (titles and links); the files themselves live in [VaultFiles], encrypted. */
interface VaultStore {
    fun items(): kotlinx.coroutines.flow.Flow<List<app.assistant.domain.model.VaultItem>>

    suspend fun add(title: String, dateId: Long?, fileName: String, mime: String, at: Instant): Long

    suspend fun get(id: Long): app.assistant.domain.model.VaultItem?

    suspend fun delete(id: Long)
}

/** Encrypted file storage on the phone only (never in Android backups). */
interface VaultFiles {
    /** Encrypts and stores [bytes]; returns the stored file name. */
    suspend fun put(bytes: ByteArray): String

    suspend fun read(fileName: String): ByteArray?

    suspend fun remove(fileName: String)
}

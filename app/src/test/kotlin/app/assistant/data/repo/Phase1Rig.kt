package app.assistant.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.testing.FakeCalendarRepository
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeTimeSource

/** An in-memory Phase 1 database with the real Room repositories, for Robolectric property tests. */
internal class Phase1Rig {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val time = FakeTimeSource()
    val settings = FakeSettingsStore()
    private val provider = DbProvider { db }
    val goals = RoomGoalRepository(provider, settings, time)
    val tasks = RoomTaskRepository(provider, settings)
    val plans = RoomPlanRepository(provider, settings, goals, time, FakeCalendarRepository())
    val reviews = RoomReviewRepository(provider, settings)
    val reports = RoomReportRepository(provider, settings, time)

    /** Empties every Phase 1 table between property iterations. */
    fun reset() {
        val sql = db.openHelper.writableDatabase
        RESET_TABLES.forEach { sql.execSQL("DELETE FROM $it") }
    }

    fun close() = db.close()

    /** The first column of the first row of [sql] as an Int, such as a COUNT. */
    fun count(sql: String, vararg args: Any): Int = db.openHelper.writableDatabase.query(sql, args).use { c ->
        c.moveToFirst()
        c.getInt(0)
    }

    /** The first column of every row of [sql] as text. */
    fun strings(sql: String): List<String> = db.openHelper.writableDatabase.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    /** The sum of all stored Goal progress, in hundredths. */
    suspend fun progressTotal(): Long = db.goalDao().allProgressNow().sumOf { it.progressHundredths }

    private companion object {
        val RESET_TABLES = listOf("goal_progress", "task_move", "plan_priority", "time_block", "daily_plan", "report", "task", "goal")
    }
}

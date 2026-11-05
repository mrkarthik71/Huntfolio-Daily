package app.assistant.data.maintenance

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.AuditEntryEntity
import app.assistant.domain.model.AuditKind
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.RescheduleReason
import app.assistant.testing.FakeBillAlarmSync
import app.assistant.testing.FakeReportRepository
import app.assistant.testing.FakeSpendingReportRepository
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The maintenance-daily job: report catch-up, schedule realignment and the Audit_Log size check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MaintenanceDailyTest {
    private lateinit var db: AppDatabase
    private val scheduler = CountingScheduler()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val billAlarms = FakeBillAlarmSync()
    private val spending = FakeSpendingReportRepository()

    private fun maintenance(provider: DbProvider = DbProvider { db }, sync: FakeBillAlarmSync = billAlarms) =
        MaintenanceDaily(FakeReportRepository(), spending, sync, scheduler, provider, FakeTimeSource())

    @Test
    fun `run catches up reports syncs bill alarms and counts audit rows`() {
        runBlocking {
            db.auditDao().insert(AuditEntryEntity(0, 1L, AuditKind.READ.name, null, 0, true, null, null, null, null))
            val result = maintenance().run()
            assertTrue(result.reportsCaughtUp)
            assertEquals(1, result.auditEntryCount)
            assertFalse(result.auditLogLarge)
            assertEquals(1, spending.catchUps)
            // Syncing bill alarms realigns the schedule itself, so the plain refresh is not needed.
            assertEquals(1, billAlarms.syncs)
            assertEquals(0, scheduler.refreshes)
        }
    }

    @Test
    fun `a failing bill sync falls back to a plain schedule refresh`() {
        runBlocking {
            maintenance(sync = FakeBillAlarmSync(fails = true)).run()
            assertEquals(1, scheduler.refreshes)
        }
    }

    @Test
    fun `a closed store does not stop the other steps`() {
        runBlocking {
            val result = maintenance(DbProvider { error("store closed") }).run()
            assertEquals(0, result.auditEntryCount)
            assertEquals(1, spending.catchUps)
            assertEquals(1, billAlarms.syncs)
        }
    }

    private class CountingScheduler : NotificationScheduling {
        var refreshes = 0

        override suspend fun refresh() {
            refreshes++
        }

        override suspend fun rescheduleAll(reason: RescheduleReason) = Unit

        override suspend fun refreshEventReminders() = Unit

        override suspend fun onAlarmFired() = Unit

        override suspend fun snooze(logId: Long, minutes: Int) = Unit

        override fun hasExactAlarmAccess(): Boolean = true
    }
}

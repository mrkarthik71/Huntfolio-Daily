package app.assistant.notify

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.repo.RoomAlarmStore
import app.assistant.data.repo.RoomGoalRepository
import app.assistant.data.repo.RoomNotificationLog
import app.assistant.data.repo.RoomPlanRepository
import app.assistant.data.repo.RoomReportRepository
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.testing.FakeBillRepository
import app.assistant.testing.FakeCalendarRepository
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The bill-due notification (Requirements 34.5, 34.7): content read when it fires, made once, and silent for a paid Bill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BillDueNotificationTest {
    private lateinit var db: AppDatabase
    private lateinit var alarms: RoomAlarmStore
    private lateinit var log: RoomNotificationLog
    private lateinit var scheduler: AndroidNotificationScheduler
    private val time = FakeTimeSource()
    private val settings = FakeSettingsStore(AppSettings(enabledTypes = setOf(NotificationType.BILL_DUE)))
    private val dueDate: LocalDate = LocalDate.of(2026, 11, 4)

    private fun bill(id: Long, paid: Boolean) =
        BillOccurrence(BillId(id), "Electricity", Paise(123_400), dueDate, BillRecurrence.NONE, 4, null, paid, id, false)

    private fun build(bills: FakeBillRepository) {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val provider = DbProvider { db }
        alarms = RoomAlarmStore(provider)
        log = RoomNotificationLog(provider)
        val goals = RoomGoalRepository(provider, settings, time)
        scheduler = AndroidNotificationScheduler(
            context,
            alarms,
            log,
            settings,
            time,
            RoomPlanRepository(provider, settings, goals, time, FakeCalendarRepository()),
            goals,
            RoomReportRepository(provider, settings, time),
            FakeCalendarRepository(),
            Notifier(context),
            AlarmArmer(context, time),
            { bills },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun fire(id: Long) {
        runBlocking {
            alarms.put(NotificationType.BILL_DUE, "bill:$id", ScheduleRule.AtInstant(time.instant), time.instant, false)
            scheduler.onAlarmFired()
        }
    }

    @Test
    fun `a due Bill is announced with its name and amount and marked as notified`() {
        val bills = FakeBillRepository(listOf(bill(1, paid = false)))
        build(bills)
        fire(1)
        val entry = checkNotNull(runBlocking { log.get(1) })
        assertEquals(NotificationType.BILL_DUE, entry.type)
        assertTrue(entry.summary.contains("Electricity"))
        assertTrue(entry.summary.contains(MoneyFormatter.format(Paise(123_400))))
        assertEquals(listOf(BillId(1)), bills.notified)
        assertTrue(runBlocking { alarms.all() }.none { it.type == NotificationType.BILL_DUE })
    }

    @Test
    fun `a paid or missing Bill says nothing and leaves no alarm`() {
        val bills = FakeBillRepository(listOf(bill(1, paid = true)))
        build(bills)
        fire(1)
        fire(2)
        assertNull(runBlocking { log.get(1) })
        assertTrue(bills.notified.isEmpty())
        assertTrue(runBlocking { alarms.all() }.none { it.type == NotificationType.BILL_DUE })
    }
}

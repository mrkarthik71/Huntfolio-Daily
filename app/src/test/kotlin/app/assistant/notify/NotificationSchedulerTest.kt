package app.assistant.notify

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.repo.RoomAlarmStore
import app.assistant.data.repo.RoomGoalRepository
import app.assistant.data.repo.RoomNotificationLog
import app.assistant.data.repo.RoomPlanRepository
import app.assistant.data.repo.RoomReportRepository
import app.assistant.domain.model.AppSettings
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.LogDecision
import app.assistant.domain.port.LogEntry
import app.assistant.domain.port.RescheduleReason
import app.assistant.testing.FakeBillRepository
import app.assistant.testing.FakeCalendarRepository
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant

/** Scheduler, alarm arming and receiver mapping on an in-memory database (Requirements 18.9, 19.1, 19.3, 65.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationSchedulerTest {
    private lateinit var db: AppDatabase
    private lateinit var alarmManager: AlarmManager
    private lateinit var alarms: RoomAlarmStore
    private lateinit var log: RoomNotificationLog
    private lateinit var armer: AlarmArmer
    private lateinit var scheduler: AndroidNotificationScheduler
    private val time = FakeTimeSource()
    private val settings = FakeSettingsStore(AppSettings(enabledTypes = setOf(NotificationType.MORNING_BRIEF)))

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val provider = DbProvider { db }
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        alarms = RoomAlarmStore(provider)
        log = RoomNotificationLog(provider)
        armer = AlarmArmer(context, time)
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
            armer,
            { FakeBillRepository() },
            { app.assistant.domain.port.LifeAlarms {} },
            {
                LifeNotifications(
                    context,
                    app.assistant.data.repo.RoomHabitStore(provider),
                    app.assistant.data.repo.RoomDateStore(provider),
                    app.assistant.data.repo.RoomFollowUpStore(provider),
                    app.assistant.data.repo.RoomReflectionStore(provider),
                    time,
                    app.assistant.data.repo.RoomHoldingStore(provider),
                )
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun scheduled() = shadowOf(alarmManager).scheduledAlarms

    private fun dueOf(type: NotificationType): Instant = runBlocking { alarms.all().single { it.type == type && !it.isSnooze }.dueAt }

    private fun enable(vararg types: NotificationType) {
        runBlocking { settings.update { it.copy(enabledTypes = types.toSet()) } }
    }

    @Test
    fun `refresh arms one alarm for the earliest daily row`() {
        enable(NotificationType.MORNING_BRIEF, NotificationType.EVENING_REVIEW)
        runBlocking { scheduler.refresh() }
        // It is 09:00 in Kolkata: the evening review today is earlier than the morning brief tomorrow.
        val evening = dueOf(NotificationType.EVENING_REVIEW)
        assertEquals(Instant.parse("2026-11-03T15:30:00Z"), evening)
        assertEquals(1, scheduled().size)
        assertEquals(evening.toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `boot reschedules the alarm that the system dropped`() {
        runBlocking { scheduler.refresh() }
        val due = dueOf(NotificationType.MORNING_BRIEF)
        armer.arm(null)
        assertTrue(scheduled().isEmpty())
        runBlocking { scheduler.rescheduleAll(RescheduleReason.BOOT) }
        assertEquals(1, scheduled().size)
        assertEquals(due.toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `package replaced reschedules the alarm that the system dropped`() {
        runBlocking { scheduler.refresh() }
        val due = dueOf(NotificationType.MORNING_BRIEF)
        armer.arm(null)
        runBlocking { scheduler.rescheduleAll(RescheduleReason.PACKAGE_REPLACED) }
        assertEquals(due.toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `boot keeps a missed daily row and fires it at once`() {
        runBlocking { scheduler.refresh() }
        val due = dueOf(NotificationType.MORNING_BRIEF)
        // The phone was off through the brief time: after a reboot the row is kept and fires one second from now.
        time.instant = due.plus(Duration.ofMinutes(10))
        runBlocking { scheduler.rescheduleAll(RescheduleReason.BOOT) }
        assertEquals(due, dueOf(NotificationType.MORNING_BRIEF))
        assertEquals(time.instant.plusSeconds(1).toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `broadcast actions map to reschedule reasons`() {
        assertEquals(RescheduleReason.BOOT, rescheduleReasonFor(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RescheduleReason.TIME_CHANGED, rescheduleReasonFor("android.intent.action.TIME_SET"))
        assertEquals(RescheduleReason.ZONE_CHANGED, rescheduleReasonFor("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(RescheduleReason.PACKAGE_REPLACED, rescheduleReasonFor("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(
            RescheduleReason.EXACT_ALARM_CHANGED,
            rescheduleReasonFor("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
        )
        assertNull(rescheduleReasonFor("android.intent.action.SCREEN_ON"))
        assertNull(rescheduleReasonFor(null))
    }

    @Test
    fun `exact alarms are used when access is granted`() {
        runBlocking { scheduler.refresh() }
        val next = runBlocking { alarms.earliest() }
        assertTrue(scheduler.hasExactAlarmAccess())
        assertEquals(ArmResult.EXACT, armer.arm(next))
    }

    @Test
    fun `inexact alarm is used when exact alarms are denied`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        runBlocking { scheduler.refresh() }
        assertFalse(scheduler.hasExactAlarmAccess())
        val next = runBlocking { alarms.earliest() }
        assertNotNull(next)
        assertEquals(ArmResult.INEXACT, armer.arm(next))
        // The alarm is still armed for the row, only without the exact guarantee (Requirement 19.3).
        assertEquals(next!!.dueAt.toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `switching every type off removes all rows and cancels the alarm`() {
        enable(NotificationType.MORNING_BRIEF, NotificationType.EVENING_REVIEW, NotificationType.GOAL_NUDGE)
        runBlocking { scheduler.refresh() }
        assertEquals(3, runBlocking { alarms.all().size })
        assertEquals(1, scheduled().size)
        enable()
        runBlocking { scheduler.refresh() }
        assertTrue(runBlocking { alarms.all() }.isEmpty())
        assertTrue(scheduled().isEmpty())
    }

    @Test
    fun `switching one type off keeps the others and re-arms for the next row`() {
        enable(NotificationType.MORNING_BRIEF, NotificationType.EVENING_REVIEW)
        runBlocking { scheduler.refresh() }
        enable(NotificationType.MORNING_BRIEF)
        runBlocking { scheduler.refresh() }
        assertEquals(listOf(NotificationType.MORNING_BRIEF), runBlocking { alarms.all() }.map { it.type })
        assertEquals(dueOf(NotificationType.MORNING_BRIEF).toEpochMilli(), scheduled().single().triggerAtMs)
    }

    @Test
    fun `after an alarm fires the next row is armed`() {
        enable(NotificationType.MORNING_BRIEF, NotificationType.EVENING_REVIEW)
        // Exactly 08:00 in Kolkata: the brief is due now, the review at 21:00 follows.
        time.instant = Instant.parse("2026-11-03T02:30:00Z")
        runBlocking { scheduler.refresh() }
        runBlocking { scheduler.onAlarmFired() }
        val rows = runBlocking { alarms.all() }
        assertEquals(2, rows.size)
        assertEquals(Instant.parse("2026-11-03T15:30:00Z"), dueOf(NotificationType.EVENING_REVIEW))
        assertEquals(Instant.parse("2026-11-04T02:30:00Z"), dueOf(NotificationType.MORNING_BRIEF))
        assertEquals(1, scheduled().size)
        assertEquals(Instant.parse("2026-11-03T15:30:00Z").toEpochMilli(), scheduled().single().triggerAtMs)
        // The decision for the brief was written to the log, shown or sent to the Inbox_List.
        assertNotNull(runBlocking { log.get(1L) })
    }

    @Test
    fun `snooze arms an alarm after the chosen minutes and ignores an out of range value`() {
        runBlocking { scheduler.refresh() }
        val id = runBlocking {
            log.record(
                LogEntry(
                    0,
                    "2026-11-03",
                    NotificationType.MORNING_BRIEF,
                    "daily:2026-11-03",
                    LogDecision.SHOWN,
                    "daily.morning_brief",
                    time.instant,
                    true,
                    true,
                    "x",
                ),
            )
        }
        runBlocking { scheduler.snooze(id, 1) }
        assertTrue(runBlocking { alarms.all() }.none { it.isSnooze })
        runBlocking { scheduler.snooze(id, 10) }
        val snooze = runBlocking { alarms.all() }.single { it.isSnooze }
        assertEquals(time.instant.plus(Duration.ofMinutes(10)), snooze.dueAt)
        assertEquals(snooze.dueAt.toEpochMilli(), scheduled().single().triggerAtMs)
    }
}

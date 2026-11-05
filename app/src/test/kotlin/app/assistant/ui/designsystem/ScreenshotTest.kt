package app.assistant.ui.designsystem

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.DueUsageCheck
import app.assistant.testing.FakeBillRepository
import app.assistant.testing.FakeCalendarRepository
import app.assistant.testing.FakeGoalRepository
import app.assistant.testing.FakePlanRepository
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeSubscriptionRepository
import app.assistant.testing.FakeTaskRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.settings.SettingsScreen
import app.assistant.ui.settings.SettingsViewModel
import app.assistant.ui.subscriptions.SubscriptionsScreen
import app.assistant.ui.subscriptions.SubscriptionsViewModel
import app.assistant.ui.today.TodayScreen
import app.assistant.ui.today.TodayViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Renders the main screens in the Cred-style theme with synthetic data and writes PNGs to
 * app/build/screenshots/ for visual review. Uses Robolectric native graphics, so no emulator is
 * needed. The data is fake (no real user content) and nothing leaves the machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-night-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today: LocalDate = LocalDate.of(2026, 11, 3)
    private val outDir = File(System.getProperty("user.dir") ?: ".", "build/screenshots").apply { mkdirs() }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { AssistantTheme { content() } }
        compose.waitForIdle()
        // Draw the window's view tree straight into a bitmap (PixelCopy-based capture is not
        // available under Robolectric's native graphics).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Shell(selected: String, content: @Composable () -> Unit) {
        val items = listOf(
            CredBarItem("today", "Today", Icons.Filled.Home, Icons.Outlined.Home),
            CredBarItem("tasks", "Tasks", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
            CredBarItem("goals", "Goals", Icons.Filled.DateRange, Icons.Outlined.DateRange),
            CredBarItem("money", "Money", Icons.Filled.ShoppingCart, Icons.Outlined.ShoppingCart),
            CredBarItem("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
        )
        Scaffold(bottomBar = { CredBottomBar(items, selected, onSelect = {}) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }

    @Test
    fun today() {
        val tasks = FakeTaskRepository(
            listOf(
                Task(TaskId(1), "Pay the electricity bill", TaskStatus.OPEN, null, today, null, null, Instant.EPOCH, null),
                Task(TaskId(2), "Prepare the design review", TaskStatus.OPEN, null, today, null, null, Instant.EPOCH, null),
                Task(TaskId(3), "Call the bank about the card", TaskStatus.DONE, null, today, null, null, Instant.EPOCH, null),
                Task(TaskId(4), "Book a dentist appointment", TaskStatus.OPEN, null, today, null, null, Instant.EPOCH, null),
            ),
        )
        val vm = TodayViewModel(
            plans = FakePlanRepository(tasks, listOf(TaskId(1), TaskId(2), TaskId(3))),
            tasks = tasks,
            goals = FakeGoalRepository(),
            bills = FakeBillRepository(),
            time = FakeTimeSource(),
            calendar = FakeCalendarRepository(),
            settings = FakeSettingsStore(),
            advisor = app.assistant.domain.port.Advisor.Disabled,
            planner = app.assistant.testing.FakePlannerRepository(
                routines = listOf(
                    app.assistant.domain.model.Routine(app.assistant.domain.model.RoutineId(1), "Morning run", app.assistant.domain.model.EnergyTag.PHYSICAL_ROUTINE, java.time.DayOfWeek.entries.toSet(), java.time.LocalTime.of(7, 0), 45, 30, true),
                    app.assistant.domain.model.Routine(app.assistant.domain.model.RoutineId(2), "Draft the Q4 roadmap", app.assistant.domain.model.EnergyTag.DEEP_WORK, java.time.DayOfWeek.entries.toSet(), java.time.LocalTime.of(8, 30), 90, 0, true),
                    app.assistant.domain.model.Routine(app.assistant.domain.model.RoutineId(3), "Inbox and admin", app.assistant.domain.model.EnergyTag.LIGHT_ADMIN, java.time.DayOfWeek.entries.toSet(), java.time.LocalTime.of(13, 30), 45, 15, true),
                ),
                cards = listOf(
                    app.assistant.domain.model.ProactiveCard(
                        app.assistant.domain.model.CardId(1), app.assistant.domain.model.CardKind.OVERLOAD, today.plusDays(1),
                        "Tomorrow is overbooked", "9 h 20 m planned in 7 h 0 m of free time. Move something to a lighter day?", app.assistant.domain.model.CardStatus.NEW,
                    ),
                ),
            ),
        )
        capture("01-today") { Shell("today") { TodayScreen(onOpenReview = {}, viewModel = vm) } }
    }

    @Test
    fun subscriptions() {
        val repo = FakeSubscriptionRepository(
            today,
            proposals = listOf(
                FakeSubscriptionRepository.view(9, "CloudDrive", Paise(13_000), SubscriptionInterval.MONTHLY, today.plusDays(6), today, SubscriptionStatus.PROPOSED),
            ),
            active = listOf(
                FakeSubscriptionRepository.view(1, "StreamFlix", Paise(64_900), SubscriptionInterval.MONTHLY, today.plusDays(2), today),
                FakeSubscriptionRepository.view(2, "MusicBox", Paise(11_900), SubscriptionInterval.MONTHLY, today.plusDays(9), today),
                FakeSubscriptionRepository.view(3, "NewsDaily", Paise(99_900), SubscriptionInterval.YEARLY, today.plusDays(40), today),
            ),
            usage = listOf(DueUsageCheck(2, "MusicBox", Paise(11_900))),
        )
        val vm = SubscriptionsViewModel(repo, FakeTimeSource())
        capture("02-subscriptions") { Shell("money") { SubscriptionsScreen(viewModel = vm) } }
    }

    @Test
    fun settings() {
        val vm = SettingsViewModel(emptySet())
        capture("03-settings") {
            Shell("settings") {
                SettingsScreen(
                    onBack = {},
                    onOpenAuditLog = {},
                    onOpenNotifications = {},
                    onOpenCalendars = {},
                    onDeleteEverything = {},
                    viewModel = vm,
                    onOpenMoney = {},
                    onOpenAi = {},
                    onOpenMail = {},
                    onOpenTransfer = {},
                    onOpenHealth = {},
                )
            }
        }
    }

    @Test
    fun goals() {
        val g1 = app.assistant.domain.model.Goal(
            app.assistant.domain.model.GoalId(1), "Launch Huntfolio v2", app.assistant.domain.model.GoalPeriod.MONTH, today.minusDays(20),
            app.assistant.domain.model.Quantity(100), "tasks", null, Instant.EPOCH, null, deadline = today.plusDays(45), priority = 1,
        )
        val g2 = app.assistant.domain.model.Goal(
            app.assistant.domain.model.GoalId(2), "Read 12 books", app.assistant.domain.model.GoalPeriod.YEAR, today.minusDays(300),
            app.assistant.domain.model.Quantity(1200), "books", null, Instant.EPOCH, null, deadline = today.plusDays(10), priority = 3,
        )
        val instance = app.assistant.domain.model.PeriodInstance(app.assistant.domain.model.GoalPeriod.MONTH, today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1).minusDays(1))
        val statuses = listOf(
            app.assistant.domain.model.GoalStatus(g1, instance, app.assistant.domain.model.Quantity(62), 62, 0),
            app.assistant.domain.model.GoalStatus(g2, instance, app.assistant.domain.model.Quantity(700), 58, 3),
        )
        val mk = { id: Long, title: String, pos: Int, done: Boolean ->
            app.assistant.domain.model.Milestone(app.assistant.domain.model.MilestoneId(id), app.assistant.domain.model.GoalId(1), title, pos, today.plusDays(pos * 12L), null, if (done) Instant.EPOCH else null)
        }
        val planner = app.assistant.testing.FakePlannerRepository(milestones = listOf(mk(1, "Research and spec", 0, true), mk(2, "Build the new importer", 1, false), mk(3, "Beta with 20 users", 2, false)))
        planner.workState.value = (1..13).map { i ->
            app.assistant.domain.port.GoalWork(TaskId(i.toLong()), app.assistant.domain.model.GoalId(1), app.assistant.domain.model.MilestoneId(if (i <= 6) 1 else if (i <= 13) 2 else 3), i <= 10, 180)
        } + listOf(app.assistant.domain.port.GoalWork(TaskId(40), app.assistant.domain.model.GoalId(2), null, false, 900))
        val vm = app.assistant.ui.goals.GoalsViewModel(app.assistant.testing.FakeGoalRepository(statuses), app.assistant.testing.FakePlanRepository(FakeTaskRepository(), emptyList()), app.assistant.testing.FakeReportRepository(), FakeTimeSource(), planner)
        capture("04-goals") { Shell("goals") { app.assistant.ui.goals.GoalsScreen(viewModel = vm) } }
    }

    @Test
    fun reminders() {
        val planner = app.assistant.testing.FakePlannerRepository(
            reminders = listOf(
                app.assistant.domain.model.SmartReminder(app.assistant.domain.model.ReminderId(1), "Pick up the courier", app.assistant.domain.model.ReminderKind.PLACE, placeName = "Office", latitude = 12.9, longitude = 77.6, radiusMeters = 200, onEnter = true),
                app.assistant.domain.model.SmartReminder(app.assistant.domain.model.ReminderId(2), "Buy groceries for the week", app.assistant.domain.model.ReminderKind.PLACE, placeName = "Home", latitude = 12.9, longitude = 77.6, radiusMeters = 100, onEnter = false),
                app.assistant.domain.model.SmartReminder(app.assistant.domain.model.ReminderId(3), "Call Mom on the drive home", app.assistant.domain.model.ReminderKind.DEVICE, device = app.assistant.domain.model.DeviceTrigger.CAR_BLUETOOTH, notBefore = java.time.LocalTime.of(18, 0)),
                app.assistant.domain.model.SmartReminder(app.assistant.domain.model.ReminderId(4), "Plan tomorrow", app.assistant.domain.model.ReminderKind.DEVICE, device = app.assistant.domain.model.DeviceTrigger.CHARGING, notBefore = java.time.LocalTime.of(21, 0), active = false),
            ),
        )
        val scheduler = object : app.assistant.domain.port.ReminderScheduler {
            override suspend fun sync() = Unit
            override fun disarm(id: app.assistant.domain.model.ReminderId) = Unit
        }
        val vm = app.assistant.ui.reminders.RemindersViewModel(planner, scheduler, app.assistant.testing.FakeSettingsStore(), FakeTimeSource())
        capture("05-reminders") { Shell("settings") { app.assistant.ui.reminders.RemindersScreen(onBack = {}, viewModel = vm) } }
    }
}

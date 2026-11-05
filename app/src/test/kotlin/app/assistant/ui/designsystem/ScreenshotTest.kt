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
}

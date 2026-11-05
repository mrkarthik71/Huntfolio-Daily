package app.assistant.ui.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.assistant.R
import app.assistant.app.FeatureFlags
import app.assistant.ui.designsystem.CredBarItem
import app.assistant.ui.designsystem.CredBottomBar
import app.assistant.ui.goals.GoalsScreen
import app.assistant.ui.money.MoneyScreen
import app.assistant.ui.money.settings.MoneySettingsScreen
import app.assistant.ui.notifications.NotificationInboxScreen
import app.assistant.ui.notifications.NotificationSettingsScreen
import app.assistant.ui.review.ReviewScreen
import app.assistant.ui.settings.AuditLogScreen
import app.assistant.ui.settings.SettingsScreen
import app.assistant.ui.tasks.TasksScreen
import app.assistant.ui.today.TodayScreen

object Routes {
    const val TODAY = "today"
    const val TASKS = "tasks"
    const val GOALS = "goals"
    const val SETTINGS = "settings"
    const val AUDIT_LOG = "settings/audit"
    const val NOTIFICATIONS = "settings/notifications"
    const val NOTIFICATION_INBOX = "settings/notifications/inbox"
    const val CALENDAR_SETTINGS = "settings/calendars"
    const val REVIEW = "review/{date}"
    const val MONEY = "money"
    const val MONEY_SETTINGS = "settings/money"
    const val SUBSCRIPTIONS = "subscriptions"
    const val AI_SETTINGS = "settings/ai"
    const val MAIL = "settings/mail"
    const val TRANSFER = "settings/transfer"
    const val HEALTH = "settings/health"
    const val REMINDERS = "settings/reminders"
    const val TASK_PREFILL = "tasks/new?title={title}"

    fun taskPrefill(title: String) = "tasks/new?title=" + android.net.Uri.encode(title)

    fun review(date: String) = "review/$date"
}

private data class Tab(val route: String, val labelRes: Int, val selected: ImageVector, val unselected: ImageVector)

private val TABS = buildList {
    add(Tab(Routes.TODAY, R.string.nav_today, Icons.Filled.Home, Icons.Outlined.Home))
    add(Tab(Routes.TASKS, R.string.nav_tasks, Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle))
    add(Tab(Routes.GOALS, R.string.nav_goals, Icons.Filled.DateRange, Icons.Outlined.DateRange))
    // The Money tab exists only while the money phase is switched on (gradle.properties: phase.money).
    if (FeatureFlags.MONEY) add(Tab(Routes.MONEY, R.string.nav_money, Icons.Filled.ShoppingCart, Icons.Outlined.ShoppingCart))
    add(Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings))
}

/**
 * The unlocked app: a bottom bar over the four main tabs, with the review, notification and audit
 * destinations layered on top. [startRoute] comes from the launch intent (a notification tap).
 */
@Composable
fun AppNavHost(onDeleteEverything: () -> Unit, startRoute: String?) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in TABS.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                val selectedKey = TABS.firstOrNull { tab -> backStack?.destination?.hierarchy?.any { it.route == tab.route } == true }?.route
                CredBottomBar(
                    items = TABS.map { CredBarItem(it.route, stringResource(it.labelRes), it.selected, it.unselected) },
                    selectedKey = selectedKey,
                    onSelect = { route ->
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startRoute?.takeIf {
                it.startsWith("review/") || it == Routes.GOALS || it == Routes.TASKS || (FeatureFlags.MONEY && it == Routes.MONEY)
            } ?: Routes.TODAY,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(onOpenReview = { date -> nav.navigate(Routes.review(date)) })
            }
            composable(Routes.TASKS) { TasksScreen() }
            composable(
                Routes.TASK_PREFILL,
                arguments = listOf(androidx.navigation.navArgument("title") { type = androidx.navigation.NavType.StringType; defaultValue = "" }),
            ) { entry ->
                TasksScreen(prefillTitle = entry.arguments?.getString("title"))
            }
            composable(Routes.GOALS) { GoalsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.navigate(Routes.TODAY) { launchSingleTop = true } },
                    onOpenAuditLog = { nav.navigate(Routes.AUDIT_LOG) },
                    onOpenNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    onOpenCalendars = { nav.navigate(Routes.CALENDAR_SETTINGS) },
                    onOpenMoney = if (FeatureFlags.MONEY) ({ nav.navigate(Routes.MONEY_SETTINGS) }) else null,
                    onOpenAi = { nav.navigate(Routes.AI_SETTINGS) },
                    onOpenMail = { nav.navigate(Routes.MAIL) },
                    onOpenTransfer = { nav.navigate(Routes.TRANSFER) },
                    onOpenHealth = { nav.navigate(Routes.HEALTH) },
                    onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                    onDeleteEverything = onDeleteEverything,
                )
            }
            composable(Routes.AI_SETTINGS) {
                app.assistant.ui.settings.ai.AiSettingsScreen(viewModel = androidx.hilt.navigation.compose.hiltViewModel())
            }
            composable(Routes.MAIL) {
                app.assistant.ui.mail.MailScreen(
                    onBack = { nav.popBackStack() },
                    onCreateTask = { subject -> nav.navigate(Routes.taskPrefill(subject)) },
                    onCreateBill = { if (FeatureFlags.MONEY) nav.navigate(Routes.MONEY) },
                )
            }
            composable(Routes.TRANSFER) {
                app.assistant.ui.transfer.DataTransferScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.REMINDERS) {
                app.assistant.ui.reminders.RemindersScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.HEALTH) {
                app.assistant.ui.health.HealthScreen(onBack = { nav.popBackStack() })
            }
            if (FeatureFlags.MONEY) {
                composable(Routes.MONEY) { MoneyScreen(onOpenSettings = { nav.navigate(Routes.MONEY_SETTINGS) }) }
                composable(Routes.MONEY_SETTINGS) { MoneySettingsScreen(onBack = { nav.popBackStack() }) }
            }
            if (FeatureFlags.SUBSCRIPTIONS) {
                composable(Routes.SUBSCRIPTIONS) { app.assistant.ui.subscriptions.SubscriptionsScreen() }
            }
            composable(Routes.CALENDAR_SETTINGS) {
                app.assistant.ui.calendar.CalendarSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.AUDIT_LOG) { AuditLogScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) {
                NotificationSettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenInbox = { nav.navigate(Routes.NOTIFICATION_INBOX) },
                )
            }
            composable(Routes.NOTIFICATION_INBOX) { NotificationInboxScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.REVIEW) { entry ->
                val date = entry.arguments?.getString("date").orEmpty()
                ReviewScreen(dateText = date, onBack = { nav.popBackStack() })
            }
        }
    }
}

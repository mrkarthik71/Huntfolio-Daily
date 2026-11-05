package app.assistant.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.assistant.ui.designsystem.ApexIcons
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
    const val ASK = "ask"
    const val PLAN = "plan"
    const val INBOX = "inbox"
    const val FOCUS = "focus"
    const val YEAR = "year"
    const val SHARE = "share?text={text}"
    const val ASK_VOICE = "ask?voice={voice}"
    const val TASK_PREFILL = "tasks/new?title={title}"

    fun taskPrefill(title: String) = "tasks/new?title=" + android.net.Uri.encode(title)

    fun review(date: String) = "review/$date"
}

private data class Tab(val route: String, val labelRes: Int, val selected: ImageVector, val unselected: ImageVector)

private val TABS = buildList {
    add(Tab(Routes.TODAY, R.string.nav_today, ApexIcons.Today, ApexIcons.Today))
    add(Tab(Routes.PLAN, R.string.nav_plan, ApexIcons.Plan, ApexIcons.Plan))
    // The Money tab exists only while the money phase is switched on (gradle.properties: phase.money).
    if (FeatureFlags.MONEY) add(Tab(Routes.MONEY, R.string.nav_money, ApexIcons.Money, ApexIcons.Money))
    add(Tab(Routes.INBOX, R.string.nav_inbox, ApexIcons.Inbox, ApexIcons.Inbox))
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
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduced = app.assistant.ui.designsystem.LocalReducedMotion.current
    val transitions = androidx.compose.runtime.remember(reduced) { CredTransitions(TABS.map { it.route }.toSet(), reduced) }

    androidx.compose.runtime.LaunchedEffect(startRoute) {
        if (startRoute != null && startRoute.startsWith("share?")) runCatching { nav.navigate(startRoute) }
    }
    Scaffold(
        containerColor = app.assistant.ui.designsystem.AssistantColors.Ground,
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = showBar,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            ) {
                val selectedKey = TABS.firstOrNull { tab -> backStack?.destination?.hierarchy?.any { it.route == tab.route } == true }?.route
                CredBottomBar(
                    items = TABS.map { CredBarItem(it.route, stringResource(it.labelRes), it.selected, it.unselected) },
                    selectedKey = selectedKey,
                    center = {
                        app.assistant.ui.designsystem.SignalButton(ApexIcons.Mic, stringResource(R.string.ask_entry), onClick = { nav.navigate("ask?voice=true") })
                    },
                    onSelect = { route ->
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
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
            // Older deep links ("goals", "tasks") land on the Plan tab that now holds both.
            startDestination = startRoute?.let { if (it == Routes.GOALS || it == Routes.TASKS) Routes.PLAN else it }?.takeIf {
                it.startsWith("review/") || it == Routes.ASK || it == Routes.PLAN || it == Routes.INBOX || (FeatureFlags.MONEY && it == Routes.MONEY)
            } ?: Routes.TODAY,
            modifier = Modifier.fillMaxSize().padding(padding),
            enterTransition = { transitions.enter(this) },
            exitTransition = { transitions.exit(this) },
            popEnterTransition = { transitions.popEnter(this) },
            popExitTransition = { transitions.popExit(this) },
        ) {
            composable(Routes.TODAY) {
                TodayScreen(onOpenReview = { date -> nav.navigate(Routes.review(date)) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onOpenFocus = { nav.navigate(Routes.FOCUS) })
            }
            composable(Routes.TASKS) { TasksScreen() }
            composable(Routes.PLAN) { PlanHost() }
            composable(Routes.FOCUS) { app.assistant.ui.life.FocusScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.YEAR) { app.assistant.ui.life.YearScreen(onBack = { nav.popBackStack() }) }
            composable(
                Routes.SHARE,
                arguments = listOf(androidx.navigation.navArgument("text") { type = androidx.navigation.NavType.StringType; defaultValue = "" }),
            ) { entry ->
                app.assistant.ui.share.ShareScreen(entry.arguments?.getString("text").orEmpty(), onDone = { if (!nav.popBackStack()) nav.navigate(Routes.TODAY) })
            }
            composable(
                Routes.ASK_VOICE,
                arguments = listOf(androidx.navigation.navArgument("voice") { type = androidx.navigation.NavType.BoolType; defaultValue = false }),
            ) { entry ->
                app.assistant.ui.ask.AskScreen(onBack = { nav.popBackStack() }, startVoice = entry.arguments?.getBoolean("voice") == true)
            }
            composable(Routes.INBOX) {
                InboxHost(
                    onCreateTask = { subject -> nav.navigate(Routes.taskPrefill(subject)) },
                    onCreateBill = { if (FeatureFlags.MONEY) nav.navigate(Routes.MONEY) },
                )
            }
            composable(Routes.ASK) {
                app.assistant.ui.ask.AskScreen(
                    onBack = {
                        // Opened from the widget, Ask is the first screen: back goes to Today instead of closing the app.
                        if (nav.previousBackStackEntry == null) {
                            nav.navigate(Routes.TODAY) { popUpTo(Routes.ASK) { inclusive = true } }
                        } else {
                            nav.popBackStack()
                        }
                    },
                )
            }
            composable(
                Routes.TASK_PREFILL,
                arguments = listOf(
                    androidx.navigation.navArgument("title") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                TasksScreen(prefillTitle = entry.arguments?.getString("title"))
            }
            composable(Routes.GOALS) { GoalsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAuditLog = { nav.navigate(Routes.AUDIT_LOG) },
                    onOpenNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    onOpenCalendars = { nav.navigate(Routes.CALENDAR_SETTINGS) },
                    onOpenMoney = if (FeatureFlags.MONEY) ({ nav.navigate(Routes.MONEY_SETTINGS) }) else null,
                    onOpenAi = { nav.navigate(Routes.AI_SETTINGS) },
                    onOpenMail = { nav.navigate(Routes.MAIL) },
                    onOpenTransfer = { nav.navigate(Routes.TRANSFER) },
                    onOpenHealth = { nav.navigate(Routes.HEALTH) },
                    onOpenReminders = { nav.navigate(Routes.REMINDERS) },
                    onOpenYear = { nav.navigate(Routes.YEAR) },
                    onBack = { nav.popBackStack() },
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

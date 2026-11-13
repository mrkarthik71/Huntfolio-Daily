package app.assistant.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    const val WEEK_PLAN = "weekplan"
    const val VAULT = "vault"
    const val BODY = "body"
    const val CRASH = "crash"
    const val HORIZON = "horizon"
    const val NOTES = "notes"
    const val LISTS = "lists"
    const val JOURNAL = "journal"
    const val PEOPLE = "people"
    const val TRIPS = "trips"
    const val PLANS = "plans"
    const val WEEK_AHEAD = "weekahead"
    const val ORDERS = "orders"
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
@Suppress("CyclomaticComplexMethod") // the app's one navigation graph: one branch per destination
fun AppNavHost(onDeleteEverything: () -> Unit, startRoute: app.assistant.ui.StartRoute?) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in TABS.map { it.route }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduced = app.assistant.ui.designsystem.LocalReducedMotion.current
    val transitions = androidx.compose.runtime.remember(reduced) { CredTransitions(TABS.map { it.route }.toSet(), reduced) }

    // Notification taps, the quick-settings tile and the widget open their screen. Tabs replace the stack;
    // anything else is pushed over Today. A route the graph does not know is ignored.
    androidx.compose.runtime.LaunchedEffect(startRoute) {
        // Older deep links ("goals", "tasks") land on the Plan tab that now holds both.
        val route = startRoute?.route?.let { if (it == Routes.GOALS || it == Routes.TASKS) Routes.PLAN else it } ?: return@LaunchedEffect
        if (route == Routes.MONEY && !FeatureFlags.MONEY) return@LaunchedEffect
        if (!isOpenableRoute(route)) return@LaunchedEffect
        runCatching {
            if (route in TABS.map { it.route }) {
                nav.navigate(route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } else {
                nav.navigate(route) { launchSingleTop = true }
            }
        }
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
                        app.assistant.ui.designsystem.SignalButton(ApexIcons.Mic, stringResource(R.string.ask_entry), onClick = {
                            nav.navigate("ask?voice=true")
                        })
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
            // Always Today: a launch route is opened on top of it (see the effect above), so a second
            // notification tap never rebuilds the graph.
            startDestination = Routes.TODAY,
            modifier = Modifier.fillMaxSize().padding(padding),
            enterTransition = { transitions.enter(this) },
            exitTransition = { transitions.exit(this) },
            popEnterTransition = { transitions.popEnter(this) },
            popExitTransition = { transitions.popExit(this) },
        ) {
            composable(Routes.TODAY) {
                TodayScreen(onOpenReview = { date ->
                    nav.navigate(Routes.review(date))
                }, onOpenSettings = {
                    nav.navigate(Routes.SETTINGS)
                }, onOpenFocus = { nav.navigate(Routes.FOCUS) }, bodyTiles = {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(12f)),
                    ) {
                        app.assistant.ui.designsystem.PillButton(
                            stringResource(R.string.weekahead_button),
                            onClick = { nav.navigate(Routes.WEEK_AHEAD) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        app.assistant.ui.today.HorizonTodayPanel(onOpenPlanner = { nav.navigate(Routes.HORIZON) }, onOpenPlans = { nav.navigate(Routes.PLANS) })
                        app.assistant.ui.settings.ConnectSourcesPanel(onOpen = { source -> sourceRoute(source)?.let(nav::navigate) })
                        app.assistant.ui.health.BodyTiles()
                    }
                })
            }
            composable(Routes.TASKS) { TasksScreen() }
            composable(Routes.PLAN) { PlanHost(onOpenWeek = { nav.navigate(Routes.WEEK_PLAN) }) }
            composable(Routes.WEEK_PLAN) { app.assistant.ui.life.WeekPlanScreen(onBack = { if (!nav.popBackStack()) nav.navigate(Routes.TODAY) }) }
            composable(Routes.FOCUS) { app.assistant.ui.life.FocusScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.YEAR) { app.assistant.ui.life.YearScreen(onBack = { nav.popBackStack() }) }
            composable(
                Routes.SHARE,
                arguments = listOf(
                    androidx.navigation.navArgument("text") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                app.assistant.ui.share.ShareScreen(entry.arguments?.getString("text").orEmpty(), onDone = {
                    if (!nav.popBackStack()) nav.navigate(Routes.TODAY)
                })
            }
            composable(
                Routes.ASK_VOICE,
                arguments = listOf(
                    androidx.navigation.navArgument("voice") {
                        type = androidx.navigation.NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                app.assistant.ui.ask.AskScreen(onBack = { nav.popBackStack() }, startVoice = entry.arguments?.getBoolean("voice") == true)
            }
            composable(Routes.INBOX) {
                InboxHost(
                    onCreateTask = { subject -> nav.navigate(Routes.taskPrefill(subject)) },
                    onCreateBill = { if (FeatureFlags.MONEY) nav.navigate(Routes.MONEY) },
                    onOpenVault = { nav.navigate(Routes.VAULT) },
                    onOpen = { route -> nav.navigate(route) },
                )
            }
            composable(Routes.BODY) { app.assistant.ui.health.BodyConnectScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTES) { app.assistant.ui.life.NotesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LISTS) {
                app.assistant.ui.life.ListsScreen(onBack = { nav.popBackStack() }, onOpenReminders = { nav.navigate(Routes.REMINDERS) })
            }
            composable(Routes.JOURNAL) { app.assistant.ui.life.JournalScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.WEEK_AHEAD) { app.assistant.ui.today.WeekAheadScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PLANS) {
                app.assistant.ui.today.PlansScreen(
                    onBack = { nav.popBackStack() },
                    onNewPlan = { nav.navigate(Routes.HORIZON) },
                    onWeek = { nav.navigate(Routes.WEEK_PLAN) },
                )
            }
            composable(Routes.TRIPS) { app.assistant.ui.life.TripsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ORDERS) { app.assistant.ui.life.OrdersScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.PEOPLE) { app.assistant.ui.life.PeopleScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HORIZON) { app.assistant.ui.today.HorizonPlanScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CRASH) { app.assistant.ui.settings.CrashReportScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.VAULT) { app.assistant.ui.life.VaultScreen(onBack = { nav.popBackStack() }) }
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
                    onOpenVault = { nav.navigate(Routes.VAULT) },
                    onOpenBody = { nav.navigate(Routes.BODY) },
                    onOpenCrash = { nav.navigate(Routes.CRASH) },
                    onOpenSource = { source -> sourceRoute(source)?.let { route -> { nav.navigate(route) } } },
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

/** The page that connects each data source, or null when it has none. */
private fun sourceRoute(source: app.assistant.domain.model.DataSource): String? = when (source) {
    app.assistant.domain.model.DataSource.SMS,
    app.assistant.domain.model.DataSource.APP_NOTIFICATIONS,
    -> if (FeatureFlags.MONEY) Routes.MONEY_SETTINGS else null
    app.assistant.domain.model.DataSource.CALENDAR -> Routes.CALENDAR_SETTINGS
    app.assistant.domain.model.DataSource.MAILBOX -> Routes.MAIL
    app.assistant.domain.model.DataSource.AI_PROVIDER -> Routes.AI_SETTINGS
    else -> null
}

/**
 * Routes another app may open through the launch intent. MainActivity is exported, so this is an
 * allow-list: only screens that change nothing by themselves (share and the task form still wait for
 * the User to save).
 */
internal fun isOpenableRoute(route: String): Boolean {
    val base = route.substringBefore('?').substringBefore('/')
    return base in OPENABLE && route.length <= MAX_ROUTE
}

private val OPENABLE = setOf(
    "today", "tasks", "plan", "money", "inbox", "ask", "share", "review", "settings", "goals", "subscriptions",
    "weekplan", "weekahead", "plans", "horizon", "trips", "orders", "people", "notes", "lists", "journal", "focus", "year", "vault", "body",
)
private const val MAX_ROUTE = 2_000

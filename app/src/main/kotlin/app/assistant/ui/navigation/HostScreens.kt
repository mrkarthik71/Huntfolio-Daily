package app.assistant.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.ui.designsystem.CredPillTabs
import app.assistant.ui.designsystem.consoleGround

/** A tab that holds two or three related screens behind one segmented control. */
@Composable
private fun SegmentedHost(labels: List<String>, action: (@Composable () -> Unit)? = null, content: @Composable (Int) -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().consoleGround().statusBarsPadding()) {
        CredPillTabs(labels, index, onSelect = { index = it }, modifier = Modifier.fillMaxWidth())
        if (action != null) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { action() }
        }
        AnimatedContent(index, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "segment") { i ->
            Box(Modifier.fillMaxSize()) { content(i) }
        }
    }
}

/** Plan: goals and their milestones, and the task list, in one tab. */
@Composable
fun PlanHost(onOpenWeek: () -> Unit = {}) {
    SegmentedHost(
        listOf(stringResource(R.string.nav_goals), stringResource(R.string.nav_tasks), stringResource(R.string.habits_tab)),
        action = { app.assistant.ui.designsystem.PillButton(stringResource(R.string.week_button), onClick = onOpenWeek) },
    ) { i ->
        when (i) {
            0 -> app.assistant.ui.goals.GoalsScreen()
            1 -> app.assistant.ui.tasks.TasksScreen()
            else -> app.assistant.ui.life.HabitsScreen()
        }
    }
}

/** Inbox: smart reminders and the mail that needs action, in one tab. */
@Composable
fun InboxHost(onCreateTask: (String) -> Unit, onCreateBill: (String) -> Unit, onOpenVault: () -> Unit = {}, onOpen: (String) -> Unit = {}) {
    SegmentedHost(
        listOf(
            stringResource(R.string.reminders_title),
            stringResource(R.string.dates_tab),
            stringResource(R.string.waiting_tab),
            stringResource(R.string.mail_title),
        ),
        action = {
            // Shortcuts to the personal screens, in one scrolling row.
            androidx.compose.foundation.layout.Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                PERSONAL_SHORTCUTS.forEach { (label, route) ->
                    app.assistant.ui.designsystem.PillButton(
                        stringResource(label),
                        onClick = { onOpen(route) },
                        container = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        content = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    )
                }
                app.assistant.ui.designsystem.PillButton(stringResource(R.string.vault_button), onClick = onOpenVault)
            }
        },
    ) { i ->
        when (i) {
            0 -> app.assistant.ui.reminders.RemindersScreen(onBack = null)
            1 -> app.assistant.ui.life.DatesScreen()
            2 -> app.assistant.ui.life.WaitingScreen()
            else -> app.assistant.ui.mail.MailScreen(onBack = null, onCreateTask = onCreateTask, onCreateBill = onCreateBill)
        }
    }
}

/** Inbox shortcuts: label and route. */
private val PERSONAL_SHORTCUTS = listOf(
    R.string.notes_button to Routes.NOTES,
    R.string.lists_button to Routes.LISTS,
    R.string.journal_button to Routes.JOURNAL,
    R.string.people_button to Routes.PEOPLE,
    R.string.trips_button to Routes.TRIPS,
    R.string.orders_button to Routes.ORDERS,
)

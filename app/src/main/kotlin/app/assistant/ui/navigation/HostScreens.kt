package app.assistant.ui.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
fun InboxHost(onCreateTask: (String) -> Unit, onCreateBill: (String) -> Unit) {
    SegmentedHost(
        listOf(
            stringResource(R.string.reminders_title),
            stringResource(R.string.dates_tab),
            stringResource(R.string.waiting_tab),
            stringResource(R.string.mail_title),
        ),
    ) { i ->
        when (i) {
            0 -> app.assistant.ui.reminders.RemindersScreen(onBack = null)
            1 -> app.assistant.ui.life.DatesScreen()
            2 -> app.assistant.ui.life.WaitingScreen()
            else -> app.assistant.ui.mail.MailScreen(onBack = null, onCreateTask = onCreateTask, onCreateBill = onCreateBill)
        }
    }
}

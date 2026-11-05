package app.assistant.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.review.ReviewChoice
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskStatus
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredTopBar
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(dateText: String, onBack: () -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    LaunchedEffect(dateText) {
        runCatching { LocalDate.parse(dateText) }.getOrNull()?.let(viewModel::setDate)
    }
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CredTopBar(
                title = {
                    Column {
                        Text(stringResource(R.string.review_screen_title))
                        ui.date?.let { Text(stringResource(R.string.review_for_date, formatDate(it)), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!ui.loading && ui.tasks.isEmpty()) {
                app.assistant.ui.designsystem.EmptyState(stringResource(R.string.review_empty))
                return@Column
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.tasks, key = { it.id.value }) { task ->
                    ReviewRow(
                        task = task,
                        choice = ui.draft?.choices?.get(task.id),
                        onChoose = { choice -> viewModel.choose(task.id, choice) },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.review_summary, ui.summary.done, ui.summary.moved, ui.summary.dropped, ui.summary.leftOpen),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { viewModel.finish(onBack) }, enabled = !ui.finished, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_finish))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewRow(task: Task, choice: ReviewChoice?, onChoose: (ReviewChoice?) -> Unit) {
    app.assistant.ui.designsystem.CredSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            when (task.status) {
                TaskStatus.DONE -> Text(stringResource(R.string.review_status_done), style = MaterialTheme.typography.labelMedium)
                TaskStatus.DROPPED -> Text(stringResource(R.string.review_status_dropped), style = MaterialTheme.typography.labelMedium)
                TaskStatus.OPEN -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(R.string.review_choice_done, choice == ReviewChoice.DONE) { toggle(choice, ReviewChoice.DONE, onChoose) }
                    ChoiceChip(R.string.review_choice_move, choice == ReviewChoice.MOVE_TO_TOMORROW) { toggle(choice, ReviewChoice.MOVE_TO_TOMORROW, onChoose) }
                    ChoiceChip(R.string.review_choice_drop, choice == ReviewChoice.DROP) { toggle(choice, ReviewChoice.DROP, onChoose) }
                }
            }
        }
    }
}

private fun toggle(current: ReviewChoice?, tapped: ReviewChoice, onChoose: (ReviewChoice?) -> Unit) {
    onChoose(if (current == tapped) null else tapped)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

package app.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.domain.engine.calendar.FocusBlockProposal
import app.assistant.domain.model.InstantRange
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.SectionHeader
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

private fun InstantRange.startText(zone: ZoneId) = start.atZone(zone).format(clock)

private fun InstantRange.endText(zone: ZoneId) = end.atZone(zone).format(clock)

/**
 * Today's events, focus-time proposals and clashes (Requirements 23, 25). Nothing here changes the
 * plan on its own: a proposal or a move is applied only when the User taps its button.
 */
internal fun LazyListScope.calendarSection(ui: TodayUi, viewModel: TodayViewModel) {
    if (ui.loading) return
    item { SectionHeader(stringResource(R.string.today_events)) }
    val refreshed = ui.calendarRefreshedAt
    if (refreshed == null) {
        item { CalendarNote(stringResource(R.string.today_calendar_not_connected)) }
    } else {
        item {
            CalendarNote(stringResource(R.string.today_calendar_refreshed, refreshed.atZone(ui.zone).format(dateTime)))
        }
        if (ui.allDay.isEmpty() && ui.timeline.isEmpty()) item { CalendarNote(stringResource(R.string.today_no_events)) }
        items(ui.allDay, key = { "ad$it" }) { title ->
            CredCard { Text(stringResource(R.string.today_all_day, title), style = MaterialTheme.typography.bodyLarge) }
        }
        items(ui.timeline, key = { "ev${it.event.range.start}${it.event.title}" }) { entry ->
            val range = entry.event.range
            val whenText = if (entry.startsEarlier) {
                stringResource(R.string.today_event_time_earlier, range.start.atZone(ui.zone).format(dateTime), range.endText(ui.zone))
            } else {
                stringResource(R.string.today_event_time, range.startText(ui.zone), range.endText(ui.zone))
            }
            CredCard {
                Text(entry.event.title, style = MaterialTheme.typography.bodyLarge)
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (ui.conflicts.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.today_conflicts)) }
        items(ui.conflicts, key = { "cf${it.block.id?.value}" }) { row ->
            CredCard {
                val name = row.title.ifEmpty { stringResource(R.string.today_untitled_block) }
                Text(
                    stringResource(R.string.today_conflict_row, name, row.block.range.startText(ui.zone), row.block.range.endText(ui.zone)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.suggestion?.let { to ->
                        TextButton(onClick = { viewModel.acceptMove(row) }) {
                            Text(stringResource(R.string.today_conflict_move, to.startText(ui.zone)))
                        }
                    }
                    TextButton(onClick = { viewModel.removeConflicting(row) }) { Text(stringResource(R.string.today_conflict_remove)) }
                }
            }
        }
    }
    if (ui.proposals.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.today_focus_title)) }
        items(ui.proposals, key = { "fp${it.gap.start}" }) { p -> ProposalCard(p, ui.zone, viewModel) }
    }
}

@Composable
private fun ProposalCard(p: FocusBlockProposal, zone: ZoneId, viewModel: TodayViewModel) {
    CredCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.today_focus_proposal, p.gap.startText(zone), p.gap.endText(zone), p.tasks.joinToString { it.title }),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.acceptProposal(p) }) { Text(stringResource(R.string.today_focus_accept)) }
                TextButton(onClick = { viewModel.dismissProposal(p) }) { Text(stringResource(R.string.today_focus_dismiss)) }
            }
        }
    }
}

@Composable
private fun CalendarNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
    )
}

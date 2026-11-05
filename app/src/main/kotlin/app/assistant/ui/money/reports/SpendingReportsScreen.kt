package app.assistant.ui.money.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.assistant.R
import app.assistant.domain.engine.reports.MonthlyCategoryLine
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.Paise
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.CredCard
import app.assistant.ui.designsystem.EmptyState
import app.assistant.ui.designsystem.SectionHeader
import app.assistant.ui.money.budgets.moneyMonthLabel
import kotlin.math.abs

/** The spending reports tab of the Money screen: monthly and weekly totals by category (Requirement 35). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingReportsScreen(modifier: Modifier = Modifier, viewModel: SpendingReportsViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val mode = ui.selection.mode
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "modes") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ReportMode.MONTHLY,
                    onClick = { viewModel.setMode(ReportMode.MONTHLY) },
                    label = { Text(stringResource(R.string.money_report_monthly)) },
                )
                FilterChip(
                    selected = mode == ReportMode.WEEKLY,
                    onClick = { viewModel.setMode(ReportMode.WEEKLY) },
                    label = { Text(stringResource(R.string.money_report_weekly)) },
                )
            }
        }
        item(key = "period") {
            val label = when (mode) {
                ReportMode.MONTHLY -> moneyMonthLabel(ui.selection.month)
                ReportMode.WEEKLY -> stringResource(
                    R.string.money_report_week_label,
                    formatDate(ui.selection.week.first),
                    formatDate(ui.selection.week.last),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = viewModel::previous) { Text(stringResource(R.string.money_report_prev)) }
                Text(label, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::next, enabled = ui.canGoNext) { Text(stringResource(R.string.money_report_next)) }
            }
        }
        if (!ui.loading) {
            if (ui.isEmpty) {
                item(key = "empty") { EmptyState(stringResource(R.string.money_report_empty)) }
            }
            ui.monthly?.takeUnless { ui.isEmpty }?.let { monthlyItems(it) }
            ui.weekly?.takeUnless { ui.isEmpty }?.let { weeklyItems(it) }
        }
    }
}

private fun LazyListScope.monthlyItems(report: MonthlySpendingReport) {
    item(key = "total") { TotalCard(report.total, report.reversals) }
    item(key = "h-categories") { SectionHeader(stringResource(R.string.money_report_categories)) }
    items(report.lines, key = { "line-${it.category.value}" }) { line ->
        AmountCard(name = line.name, amount = line.amount, detail = changeText(line))
    }
}

private fun LazyListScope.weeklyItems(report: WeeklySpendingReport) {
    item(key = "total") { TotalCard(report.total, report.reversals) }
    item(key = "h-top") { SectionHeader(stringResource(R.string.money_report_top_categories)) }
    items(report.top, key = { "top-${it.category.value}" }) { spend ->
        AmountCard(name = spend.name, amount = spend.amount, detail = null)
    }
    if (report.other.value > 0L) {
        item(key = "other") { AmountCard(name = stringResource(R.string.money_report_other), amount = report.other, detail = null) }
    }
}

@Composable
private fun TotalCard(total: Paise, reversals: Paise) {
    CredCard {
        Text(stringResource(R.string.money_report_total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(MoneyFormatter.format(total), style = MaterialTheme.typography.headlineMedium)
        if (reversals.value > 0L) {
            Text(
                stringResource(R.string.money_report_reversals, MoneyFormatter.format(reversals)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AmountCard(name: String, amount: Paise, detail: String?) {
    CredCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(MoneyFormatter.format(amount), style = MaterialTheme.typography.titleMedium)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The change against the month before, in words (Requirement 35.1). The percentage is left out when the month before had no spend. */
@Composable
private fun changeText(line: MonthlyCategoryLine): String {
    val size = MoneyFormatter.format(line.change.magnitude())
    val percent = line.percent?.let { abs(it) }
    return when {
        line.change.value == 0L -> stringResource(R.string.money_report_same)
        line.change.value > 0L ->
            if (percent == null) stringResource(R.string.money_report_up_no_percent, size) else stringResource(R.string.money_report_up, size, percent)
        else ->
            if (percent == null) stringResource(R.string.money_report_down_no_percent, size) else stringResource(R.string.money_report_down, size, percent)
    }
}

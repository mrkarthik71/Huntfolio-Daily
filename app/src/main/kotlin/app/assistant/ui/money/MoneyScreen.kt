package app.assistant.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.R
import app.assistant.ui.money.bills.BillsScreen
import app.assistant.ui.money.budgets.BudgetsScreen
import app.assistant.ui.money.reports.SpendingReportsScreen
import app.assistant.ui.money.reviewqueue.ReviewQueueCountBadge
import app.assistant.ui.money.reviewqueue.ReviewQueueScreen
import app.assistant.ui.money.transactions.TransactionsScreen

private enum class MoneyTab(val labelRes: Int) {
    TRANSACTIONS(R.string.money_tab_transactions),
    BUDGETS(R.string.money_tab_budgets),
    BILLS(R.string.money_tab_bills),
    REPORTS(R.string.money_tab_reports),
    REVIEW(R.string.money_tab_review),
}

/** The Money tab: its own sections over the Transactions, Budgets, Bills, Reports and Review_Queue screens, with a link to the money settings. */
@Composable
fun MoneyScreen(onOpenSettings: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f), edgePadding = 8.dp) {
                MoneyTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(tab.labelRes))
                                if (tab == MoneyTab.REVIEW) ReviewQueueCountBadge()
                            }
                        },
                    )
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.money_host_open_settings))
            }
        }
        val content = Modifier.fillMaxSize()
        when (MoneyTab.entries[selected]) {
            MoneyTab.TRANSACTIONS -> TransactionsScreen(content)
            MoneyTab.BUDGETS -> BudgetsScreen(content)
            MoneyTab.BILLS -> BillsScreen(content)
            MoneyTab.REPORTS -> SpendingReportsScreen(content)
            MoneyTab.REVIEW -> ReviewQueueScreen(content)
        }
    }
}

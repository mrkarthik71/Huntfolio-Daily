package app.assistant.ui.money

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.assistant.app.FeatureFlags
import app.assistant.ui.designsystem.CredPillTabs
import app.assistant.ui.designsystem.ScreenTitle
import app.assistant.ui.money.bills.BillsScreen
import app.assistant.ui.money.budgets.BudgetsScreen
import app.assistant.ui.money.reports.SpendingReportsScreen
import app.assistant.ui.money.reviewqueue.ReviewQueueCountBadge
import app.assistant.ui.money.reviewqueue.ReviewQueueScreen
import app.assistant.ui.money.transactions.TransactionsScreen
import app.assistant.ui.subscriptions.SubscriptionsScreen

private enum class MoneyTab(val labelRes: Int) {
    TRANSACTIONS(R.string.money_tab_transactions),
    BUDGETS(R.string.money_tab_budgets),
    BILLS(R.string.money_tab_bills),
    SUBSCRIPTIONS(R.string.money_tab_subscriptions),
    REPORTS(R.string.money_tab_reports),
    REVIEW(R.string.money_tab_review),
    SAVINGS(R.string.savings_tab),
}

private val tabs: List<MoneyTab> = MoneyTab.entries.filter { it != MoneyTab.SUBSCRIPTIONS || FeatureFlags.SUBSCRIPTIONS }

/**
 * The Money tab: a large title with a settings shortcut, Cred-style pill tabs, and the
 * Transactions, Budgets, Bills, Subscriptions, Reports and Review_Queue sections beneath.
 */
@Composable
fun MoneyScreen(onOpenSettings: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(stringResource(R.string.nav_money), Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.money_host_open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CredPillTabs(
            labels = tabs.map { stringResource(it.labelRes) },
            selectedIndex = selected.coerceIn(0, tabs.lastIndex),
            onSelect = { selected = it },
            trailing = { index -> if (tabs[index] == MoneyTab.REVIEW) ReviewQueueCountBadge() },
        )
        val content = Modifier.fillMaxSize()
        when (tabs[selected.coerceIn(0, tabs.lastIndex)]) {
            MoneyTab.TRANSACTIONS -> TransactionsScreen(content)
            MoneyTab.BUDGETS -> BudgetsScreen(content)
            MoneyTab.BILLS -> BillsScreen(content)
            MoneyTab.SUBSCRIPTIONS -> SubscriptionsScreen(content)
            MoneyTab.REPORTS -> SpendingReportsScreen(content)
            MoneyTab.REVIEW -> ReviewQueueScreen(content)
            MoneyTab.SAVINGS -> app.assistant.ui.life.SavingsScreen(content)
        }
    }
}

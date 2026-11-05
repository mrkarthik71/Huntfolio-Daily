package app.assistant.app.di

import android.content.Context
import app.assistant.R
import app.assistant.domain.engine.ask.AskEngine
import app.assistant.domain.engine.life.BudgetAlertRules
import app.assistant.domain.format.MoneyFormatter
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.BudgetRepository
import app.assistant.domain.port.ImmediateAlert
import app.assistant.domain.port.LifeAlarms
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.PersistedMessage
import app.assistant.domain.port.PostCommitHook
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import app.assistant.notify.LifeAlarmPlanner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises a budget alert the moment a stored debit takes its category past 80% or 100% of the
 * month's Budget. Once per category, month and threshold; only while the User has budget alerts on.
 */
@Singleton
@Suppress("LongParameterList") // reads the payment, the budget, the settings and the log before offering
class BudgetAlertHook @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val transactions: TransactionRepository,
    private val budgets: BudgetRepository,
    private val settings: SettingsStore,
    private val log: NotificationLog,
    private val notifications: NotificationScheduling,
    private val time: TimeSource,
) : PostCommitHook {
    override suspend fun onPersisted(event: PersistedMessage) {
        if (!event.created || NotificationType.BUDGET_ALERT !in settings.current().enabledTypes) return
        val txn = transactions.get(event.transaction) ?: return
        if (txn.type != TxnType.DEBIT || txn.isReversal) return
        val zone = time.zone()
        val month = YearMonth.from(txn.at.atZone(zone))
        if (month != YearMonth.from(time.now().atZone(zone))) return
        val line = budgets.lines(month).first().firstOrNull { it.budget.category == txn.category } ?: return
        val after = line.status.spent
        val before = Paise((after.value - txn.amount.value).coerceAtLeast(0))
        val threshold = BudgetAlertRules.crossed(line.budget.amount, before, after) ?: return
        val ref = "budget:${txn.category.value}:$month:$threshold"
        if (ref in log.handledRefKeys(NotificationType.BUDGET_ALERT)) return
        val name = AskEngine.categoryName(txn.category)
        val title = if (threshold >= FULL) context.getString(R.string.budget_alert_over, name) else context.getString(R.string.budget_alert_near, name, threshold)
        val text = context.getString(R.string.budget_alert_text, MoneyFormatter.format(after), MoneyFormatter.format(line.budget.amount))
        notifications.offerNow(ImmediateAlert(NotificationType.BUDGET_ALERT, ref, title, text, "money"))
    }

    private companion object {
        const val FULL = 100
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LifeBindingsModule {
    @Binds
    abstract fun lifeAlarms(impl: LifeAlarmPlanner): LifeAlarms

    @Binds
    @IntoSet
    abstract fun budgetAlertHook(impl: BudgetAlertHook): PostCommitHook
}

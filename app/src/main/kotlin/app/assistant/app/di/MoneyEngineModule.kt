package app.assistant.app.di

import app.assistant.data.repo.RoomBillAlarmSync
import app.assistant.data.repo.RoomBillRepository
import app.assistant.data.repo.RoomBudgetRepository
import app.assistant.data.repo.RoomQualityStore
import app.assistant.data.repo.RoomSpendingReportRepository
import app.assistant.domain.engine.quality.DefaultQualityMonitor
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.engine.quality.QualityStore
import app.assistant.domain.port.BillAlarmSync
import app.assistant.domain.port.BillRepository
import app.assistant.domain.port.BudgetRepository
import app.assistant.domain.port.SpendingReportRepository
import app.assistant.domain.port.TimeSource
import app.assistant.ingest.pipeline.PersistedMessage
import app.assistant.ingest.pipeline.PostCommitHook
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proposes marking a Bill paid when a stored debit Transaction matches it (Requirement 34.3). It lives here, not in the
 * data layer, because the data layer may not use the ingest layer's hook type.
 */
@Singleton
class BillPaidMatcherHook @Inject constructor(private val bills: BillRepository) : PostCommitHook {
    override suspend fun onPersisted(event: PersistedMessage) {
        bills.proposePaid(event.transaction)
    }
}

/** Phase 3 Money engines (task 22): Budgets, Bills, spending reports and the Quality_Monitor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MoneyEngineBindingsModule {
    @Binds
    abstract fun budgetRepository(impl: RoomBudgetRepository): BudgetRepository

    @Binds
    abstract fun billRepository(impl: RoomBillRepository): BillRepository

    @Binds
    abstract fun billAlarmSync(impl: RoomBillAlarmSync): BillAlarmSync

    @Binds
    abstract fun spendingReportRepository(impl: RoomSpendingReportRepository): SpendingReportRepository

    @Binds
    abstract fun qualityStore(impl: RoomQualityStore): QualityStore

    @Binds
    @IntoSet
    abstract fun billPaidMatcher(impl: BillPaidMatcherHook): PostCommitHook
}

@Module
@InstallIn(SingletonComponent::class)
object MoneyEngineProvidesModule {
    @Provides
    @Singleton
    fun qualityMonitor(store: QualityStore, time: TimeSource): QualityMonitor = DefaultQualityMonitor(store, time)
}

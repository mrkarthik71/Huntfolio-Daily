package app.assistant.app.di

import app.assistant.data.audit.RoomAuditLog
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.DbProvider
import app.assistant.data.money.FixedDuplicateWindow
import app.assistant.data.repo.RoomAlarmStore
import app.assistant.data.repo.RoomAtomicWork
import app.assistant.data.repo.RoomBillProposals
import app.assistant.data.repo.RoomGoalRepository
import app.assistant.data.repo.RoomIngestLedger
import app.assistant.data.repo.RoomMoneyAppList
import app.assistant.data.repo.RoomMoneyAppStore
import app.assistant.data.repo.RoomNotificationLog
import app.assistant.data.repo.RoomPlanRepository
import app.assistant.data.repo.RoomReportRepository
import app.assistant.data.repo.RoomReviewQueueRepository
import app.assistant.data.repo.RoomReviewRepository
import app.assistant.data.repo.RoomSenderList
import app.assistant.data.repo.RoomTaskRepository
import app.assistant.data.repo.TrackedTransactionRepository
import app.assistant.data.settings.SettingsRepository
import app.assistant.data.wipe.AndroidDataWiper
import app.assistant.data.wipe.AndroidSourceDisconnect
import app.assistant.data.wipe.SourceCleanup
import app.assistant.data.wipe.WipeStep
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.AtomicWork
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.BillProposals
import app.assistant.domain.port.DataWiper
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.EncryptedStoreGate
import app.assistant.domain.port.GoalRepository
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.MoneyAppStore
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.PlanRepository
import app.assistant.domain.port.PostCommitHook
import app.assistant.domain.port.ReportRepository
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.ReviewRepository
import app.assistant.domain.port.SenderList
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.SourceDisconnect
import app.assistant.domain.port.SourceStatusProvider
import app.assistant.domain.port.TaskRepository
import app.assistant.domain.port.TransactionRepository
import app.assistant.notify.AndroidNotificationScheduler
import app.assistant.ui.permission.AndroidPermissionManager
import app.assistant.ui.permission.PermissionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun storeGate(impl: DatabaseHolder): EncryptedStoreGate

    @Binds
    abstract fun dbProvider(impl: DatabaseHolder): DbProvider

    @Binds
    abstract fun goalRepository(impl: RoomGoalRepository): GoalRepository

    @Binds
    abstract fun taskRepository(impl: RoomTaskRepository): TaskRepository

    @Binds
    abstract fun plannerRepository(impl: app.assistant.data.repo.RoomPlannerRepository): app.assistant.domain.port.PlannerRepository

    @Binds
    abstract fun habitStore(impl: app.assistant.data.repo.RoomHabitStore): app.assistant.domain.port.HabitStore

    @Binds
    abstract fun dateStore(impl: app.assistant.data.repo.RoomDateStore): app.assistant.domain.port.DateStore

    @Binds
    abstract fun savingsStore(impl: app.assistant.data.repo.RoomSavingsStore): app.assistant.domain.port.SavingsStore

    @Binds
    abstract fun followUpStore(impl: app.assistant.data.repo.RoomFollowUpStore): app.assistant.domain.port.FollowUpStore

    @Binds
    abstract fun reflectionStore(impl: app.assistant.data.repo.RoomReflectionStore): app.assistant.domain.port.ReflectionStore

    @Binds
    abstract fun contactDates(impl: app.assistant.data.contacts.AndroidContactDatesReader): app.assistant.domain.port.ContactDatesReader

    @Binds
    abstract fun memoryRepository(impl: app.assistant.data.repo.RoomMemoryRepository): app.assistant.domain.port.MemoryRepository

    @Binds
    abstract fun transactionRepository(impl: TrackedTransactionRepository): TransactionRepository

    @Binds
    abstract fun reviewQueueRepository(impl: RoomReviewQueueRepository): ReviewQueueRepository

    @Binds
    abstract fun senderList(impl: RoomSenderList): SenderList

    @Binds
    abstract fun moneyAppList(impl: RoomMoneyAppList): MoneyAppList

    @Binds
    abstract fun moneyAppStore(impl: RoomMoneyAppStore): MoneyAppStore

    @Binds
    abstract fun ingestLedger(impl: RoomIngestLedger): IngestLedger

    @Binds
    abstract fun billProposals(impl: RoomBillProposals): BillProposals

    @Binds
    abstract fun atomicWork(impl: RoomAtomicWork): AtomicWork

    @Binds
    abstract fun duplicateWindow(impl: FixedDuplicateWindow): DuplicateWindowProvider

    @Binds
    abstract fun planRepository(impl: RoomPlanRepository): PlanRepository

    @Binds
    abstract fun reviewRepository(impl: RoomReviewRepository): ReviewRepository

    @Binds
    abstract fun reportRepository(impl: RoomReportRepository): ReportRepository

    @Binds
    abstract fun alarmStore(impl: RoomAlarmStore): AlarmStore

    @Binds
    abstract fun notificationLog(impl: RoomNotificationLog): NotificationLog

    @Binds
    abstract fun notificationScheduling(impl: AndroidNotificationScheduler): NotificationScheduling

    @Binds
    abstract fun settingsStore(impl: SettingsRepository): SettingsStore

    @Binds
    abstract fun readerStateStore(impl: app.assistant.data.reader.ReaderStateRepository): app.assistant.domain.port.ReaderStateStore

    @Binds
    abstract fun messageIngestor(impl: app.assistant.ingest.pipeline.IngestPipeline): app.assistant.ingest.pipeline.MessageIngestor

    @Binds
    abstract fun subscriptionRepository(impl: app.assistant.data.repo.SubscriptionRepositoryImpl): app.assistant.domain.port.SubscriptionRepository

    @dagger.multibindings.IntoSet
    @Binds
    abstract fun subscriptionDetectHook(impl: app.assistant.data.repo.SubscriptionDetectHook): PostCommitHook

    @Binds
    abstract fun auditLog(impl: RoomAuditLog): AuditLog

    @Binds
    abstract fun dataWiper(impl: AndroidDataWiper): DataWiper

    @Binds
    abstract fun sourceDisconnect(impl: AndroidSourceDisconnect): SourceDisconnect

    @Binds
    abstract fun permissionManager(impl: AndroidPermissionManager): PermissionManager

    /** Later phases add wipe steps (alarms, work, widget), per-source cleanups and source status. */
    @Multibinds
    abstract fun wipeSteps(): Set<WipeStep>

    @Multibinds
    abstract fun sourceCleanups(): Set<SourceCleanup>

    @Multibinds
    abstract fun sourceStatus(): Set<SourceStatusProvider>

    /** Later tasks add the Bill paid matcher, Subscription_Detector and Quality_Monitor here. */
    @Multibinds
    abstract fun postCommitHooks(): Set<PostCommitHook>
}

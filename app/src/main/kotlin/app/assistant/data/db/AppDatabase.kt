package app.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import app.assistant.data.db.dao.AiUsageDao
import app.assistant.data.db.dao.AlarmDao
import app.assistant.data.db.dao.AuditDao
import app.assistant.data.db.dao.BillDao
import app.assistant.data.db.dao.BillProposalDao
import app.assistant.data.db.dao.BudgetDao
import app.assistant.data.db.dao.CalendarDao
import app.assistant.data.db.dao.CategoryDao
import app.assistant.data.db.dao.CustomSenderDao
import app.assistant.data.db.dao.DiagnosticDao
import app.assistant.data.db.dao.GoalDao
import app.assistant.data.db.dao.ImportRecordDao
import app.assistant.data.db.dao.JobRunDao
import app.assistant.data.db.dao.LedgerDao
import app.assistant.data.db.dao.MailDao
import app.assistant.data.db.dao.MerchantCatalogDao
import app.assistant.data.db.dao.MoneyAppDao
import app.assistant.data.db.dao.MoneySeedDao
import app.assistant.data.db.dao.NotificationLogDao
import app.assistant.data.db.dao.PlanDao
import app.assistant.data.db.dao.QualityDao
import app.assistant.data.db.dao.ReportDao
import app.assistant.data.db.dao.ReviewQueueDao
import app.assistant.data.db.dao.SecretDao
import app.assistant.data.db.dao.SenderDao
import app.assistant.data.db.dao.SubscriptionDao
import app.assistant.data.db.dao.TaskDao
import app.assistant.data.db.dao.TransactionDao
import app.assistant.data.db.entity.AiUsageEntity
import app.assistant.data.db.entity.AlarmQueueEntity
import app.assistant.data.db.entity.AuditEntryEntity
import app.assistant.data.db.entity.BillEntity
import app.assistant.data.db.entity.BillPaidProposalEntity
import app.assistant.data.db.entity.BillProposalEntity
import app.assistant.data.db.entity.BudgetEntity
import app.assistant.data.db.entity.CalendarEventEntity
import app.assistant.data.db.entity.CalendarSourceEntity
import app.assistant.data.db.entity.CategoryCorrectionEntity
import app.assistant.data.db.entity.CategoryEntity
import app.assistant.data.db.entity.DailyPlanEntity
import app.assistant.data.db.entity.DiagnosticEventEntity
import app.assistant.data.db.entity.ForcedMergeEntity
import app.assistant.data.db.entity.GoalEntity
import app.assistant.data.db.entity.GoalProgressEntity
import app.assistant.data.db.entity.ImportRecordEntity
import app.assistant.data.db.entity.InboxItemEntity
import app.assistant.data.db.entity.JobRunEntity
import app.assistant.data.db.entity.MailCursorEntity
import app.assistant.data.db.entity.MerchantAliasEntity
import app.assistant.data.db.entity.MerchantEntity
import app.assistant.data.db.entity.MoneyAppEntity
import app.assistant.data.db.entity.NotificationLogEntity
import app.assistant.data.db.entity.ParsedFactEntity
import app.assistant.data.db.entity.PlanPriorityEntity
import app.assistant.data.db.entity.ProcessedRefEntity
import app.assistant.data.db.entity.QualityCounterEntity
import app.assistant.data.db.entity.ReportEntity
import app.assistant.data.db.entity.ReviewItemEntity
import app.assistant.data.db.entity.SecretEntity
import app.assistant.data.db.entity.SenderEntryEntity
import app.assistant.data.db.entity.SenderPenaltyEntity
import app.assistant.data.db.entity.ShortlistRuleEntity
import app.assistant.data.db.entity.SubscriptionChargeEntity
import app.assistant.data.db.entity.SubscriptionEntity
import app.assistant.data.db.entity.SuggestionEntity
import app.assistant.data.db.entity.TaskEntity
import app.assistant.data.db.entity.TaskMoveEntity
import app.assistant.data.db.entity.TimeBlockEntity
import app.assistant.data.db.entity.TombstoneEntity
import app.assistant.data.db.entity.TransactionEntity
import app.assistant.data.db.entity.UsageCheckEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The Encrypted_Store. Every phase that adds entities bumps [VERSION], adds a hand-written
 * migration to [ALL_MIGRATIONS] and commits the exported schema in `app/schemas/`.
 * Destructive fallback is never used (Requirement 65.6).
 */
@Database(
    entities = [
        app.assistant.data.db.entity.MilestoneEntity::class,
        app.assistant.data.db.entity.RoutineEntity::class,
        app.assistant.data.db.entity.SmartReminderEntity::class,
        app.assistant.data.db.entity.ProactiveCardEntity::class,
        app.assistant.data.db.entity.TaskVerificationEntity::class,
        app.assistant.data.db.entity.MemoryNoteEntity::class,
        AuditEntryEntity::class,
        SecretEntity::class,
        DiagnosticEventEntity::class,
        GoalEntity::class,
        GoalProgressEntity::class,
        TaskEntity::class,
        TaskMoveEntity::class,
        DailyPlanEntity::class,
        PlanPriorityEntity::class,
        TimeBlockEntity::class,
        ReportEntity::class,
        AlarmQueueEntity::class,
        NotificationLogEntity::class,
        CalendarSourceEntity::class,
        CalendarEventEntity::class,
        ParsedFactEntity::class,
        TransactionEntity::class,
        ForcedMergeEntity::class,
        TombstoneEntity::class,
        ProcessedRefEntity::class,
        ReviewItemEntity::class,
        MerchantEntity::class,
        MerchantAliasEntity::class,
        CategoryEntity::class,
        CategoryCorrectionEntity::class,
        BudgetEntity::class,
        BillEntity::class,
        BillProposalEntity::class,
        BillPaidProposalEntity::class,
        SenderEntryEntity::class,
        MoneyAppEntity::class,
        QualityCounterEntity::class,
        SubscriptionEntity::class,
        SubscriptionChargeEntity::class,
        UsageCheckEntity::class,
        SuggestionEntity::class,
        AiUsageEntity::class,
        InboxItemEntity::class,
        ShortlistRuleEntity::class,
        SenderPenaltyEntity::class,
        MailCursorEntity::class,
        ImportRecordEntity::class,
        JobRunEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao

    abstract fun secretDao(): SecretDao

    abstract fun diagnosticDao(): DiagnosticDao

    abstract fun goalDao(): GoalDao

    abstract fun taskDao(): TaskDao

    abstract fun planDao(): PlanDao

    abstract fun reportDao(): ReportDao

    abstract fun alarmDao(): AlarmDao

    abstract fun notificationLogDao(): NotificationLogDao

    abstract fun calendarDao(): CalendarDao

    abstract fun moneySeedDao(): MoneySeedDao

    abstract fun transactionDao(): TransactionDao

    abstract fun merchantCatalogDao(): MerchantCatalogDao

    abstract fun reviewQueueDao(): ReviewQueueDao

    abstract fun senderDao(): SenderDao

    abstract fun customSenderDao(): CustomSenderDao

    abstract fun moneyAppDao(): MoneyAppDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun billProposalDao(): BillProposalDao

    abstract fun budgetDao(): BudgetDao

    abstract fun categoryDao(): CategoryDao

    abstract fun billDao(): BillDao

    abstract fun qualityDao(): QualityDao

    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun aiUsageDao(): AiUsageDao

    abstract fun mailDao(): MailDao

    abstract fun importRecordDao(): ImportRecordDao

    abstract fun jobRunDao(): JobRunDao

    abstract fun apexDao(): app.assistant.data.db.dao.ApexDao

    companion object {
        const val VERSION = 11
        const val FILE_NAME = "assistant.db"
    }
}

/** Every migration, in order. Version 1 is the first schema, so the list starts empty. */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    )

object DatabaseFactory {
    @Volatile
    private var libraryLoaded = false

    private fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }

    /**
     * Opens the database with a copy of the 32-byte key from the Key_Manager. The factory keeps
     * this array for as long as the database is open, because SQLCipher needs the key again
     * when it opens further connections, so the caller must not clear it.
     */
    fun open(context: Context, key: ByteArray, fileName: String = AppDatabase.FILE_NAME): AppDatabase {
        loadLibrary()
        return Room.databaseBuilder(context, AppDatabase::class.java, fileName)
            .openHelperFactory(SupportOpenHelperFactory(key))
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    fun databaseFileExists(context: Context): Boolean = context.getDatabasePath(AppDatabase.FILE_NAME).exists()
}

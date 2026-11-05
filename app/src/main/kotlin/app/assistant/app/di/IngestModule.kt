package app.assistant.app.di

import android.content.Context
import app.assistant.data.money.AssetParserRegistry
import app.assistant.data.money.InstructionPatternsJson
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.engine.redact.RedactionPatterns
import app.assistant.domain.error.Outcome
import app.assistant.domain.port.AtomicWork
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.BillProposals
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.SenderList
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import app.assistant.ingest.pipeline.BillProposalStage
import app.assistant.ingest.pipeline.FailedPaymentStage
import app.assistant.ingest.pipeline.IngestPipeline
import app.assistant.ingest.pipeline.OtpStage
import app.assistant.ingest.pipeline.ParseStage
import app.assistant.ingest.pipeline.PersistStage
import app.assistant.ingest.pipeline.PostCommitHook
import app.assistant.ingest.pipeline.ProcessedRefStage
import app.assistant.ingest.pipeline.RedactionPatternsProvider
import app.assistant.ingest.pipeline.ReviewQueueStage
import app.assistant.ingest.pipeline.SenderFilterStage
import app.assistant.ingest.pipeline.SourceRefStage
import app.assistant.parse.money.ParserRegistry
import app.assistant.security.keys.KeyManager
import app.assistant.security.log.SafeLog
import app.assistant.security.refs.SourceRefFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Assembles the Money_Message ingest pipeline (task 20.6). The adapters that feed it arrive with the SMS and notification readers. */
@Module
@InstallIn(SingletonComponent::class)
object IngestModule {
    @Provides
    @Singleton
    fun parserRegistry(@ApplicationContext context: Context): ParserRegistry =
        AssetParserRegistry(AssetParserRegistry.FILES) { context.assets.open("parser_registry/$it") }

    @Provides
    @Singleton
    fun sourceRefFactory(keys: KeyManager): SourceRefFactory = SourceRefFactory(keys)

    /** Built once from the shipped list. If the list is refused, the provider returns null and nothing is ever shown unredacted. */
    @Provides
    @Singleton
    fun redactionPatterns(@ApplicationContext context: Context): RedactionPatternsProvider {
        val patterns = lazy {
            val json = context.assets.open("instruction_patterns.json").bufferedReader().use { it.readText() }
            InstructionPatternsJson.parse(json).getOrNull()?.let { (RedactionPatterns.of(it) as? Outcome.Ok)?.value }
        }
        return RedactionPatternsProvider { patterns.value }
    }

    @Suppress("LongParameterList")
    @Provides
    @Singleton
    fun ingestPipeline(
        senders: SenderList,
        apps: MoneyAppList,
        refs: SourceRefFactory,
        ledger: IngestLedger,
        registry: ParserRegistry,
        patterns: RedactionPatternsProvider,
        queue: ReviewQueueRepository,
        bills: BillProposals,
        transactions: TransactionRepository,
        work: AtomicWork,
        hooks: Set<@JvmSuppressWildcards PostCommitHook>,
        log: SafeLog,
        audit: AuditLog,
        time: TimeSource,
        quality: QualityMonitor,
    ): IngestPipeline = IngestPipeline(
        stages = listOf(
            SenderFilterStage(senders, apps),
            SourceRefStage(refs),
            ProcessedRefStage(ledger),
            ParseStage(registry, time),
            OtpStage(ledger, time),
            FailedPaymentStage(ledger, time),
            ReviewQueueStage(queue, patterns, ledger, work, log, time),
            BillProposalStage(bills, registry, ledger, work, time),
            PersistStage(transactions, ledger, work, hooks, log, time),
        ),
        log = log,
        audit = audit,
        time = time,
        quality = quality,
    )
}

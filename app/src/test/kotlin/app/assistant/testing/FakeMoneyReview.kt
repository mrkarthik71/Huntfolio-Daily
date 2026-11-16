package app.assistant.testing

import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.engine.quality.ParseOutcomeKind
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.TransactionValidator
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.AddSenderResult
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.InstalledApp
import app.assistant.domain.port.InstalledApps
import app.assistant.domain.port.MoneyAppRules
import app.assistant.domain.port.MoneyAppStore
import app.assistant.domain.port.MoneyReaderControls
import app.assistant.domain.port.MoneyReaderState
import app.assistant.domain.port.ReviewAddResult
import app.assistant.domain.port.ReviewItem
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.SenderRow
import app.assistant.domain.port.SenderRules
import app.assistant.domain.port.SenderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A Review_Queue over a list. A resolve is checked with the real Requirement 32.6 rules, so an invalid save keeps the item. */
class FakeReviewQueueRepository(
    initial: List<ReviewItem> = emptyList(),
    private val today: LocalDate = LocalDate.of(2026, 11, 3),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : ReviewQueueRepository {
    private val all = MutableStateFlow(initial)
    val resolved = mutableListOf<Pair<ReviewItemId, ManualTransactionInput>>()
    val dismissed = mutableListOf<ReviewItemId>()

    override suspend fun add(
        ref: SourceRef,
        redactedText: String,
        origin: MessageOrigin,
        receivedAt: Instant,
        fallbackAttempted: Boolean,
        proposalJson: String?,
    ): ReviewAddResult = error("not used in tests")

    override suspend fun get(id: ReviewItemId): ReviewItem? = all.value.firstOrNull { it.id == id }

    override fun openItemsNewestFirst(): Flow<List<ReviewItem>> = all.map { list -> list.sortedByDescending { it.receivedAt } }

    override fun openCount(): Flow<Int> = all.map { it.size }

    override suspend fun resolve(id: ReviewItemId, input: ManualTransactionInput): Outcome<TransactionId, EditFailure> {
        if (all.value.none { it.id == id }) return Outcome.Err(EditFailure.NotFound)
        val errors = TransactionValidator.validateNew(input, today, zone)
        if (!errors.isValid) return Outcome.Err(EditFailure.Invalid(errors))
        resolved += id to input
        all.update { list -> list.filterNot { it.id == id } }
        return Outcome.Ok(TransactionId(resolved.size.toLong()))
    }

    override suspend fun dismiss(id: ReviewItemId) {
        dismissed += id
        all.update { list -> list.filterNot { it.id == id } }
    }

    val fallbackAttempts = mutableListOf<Pair<ReviewItemId, String?>>()

    override suspend fun markFallbackAttempted(id: ReviewItemId, proposalJson: String?) {
        fallbackAttempts += id to proposalJson
    }
}

fun reviewItem(id: Long, text: String, origin: String = "AX-HDFCBK", receivedAt: Instant = Instant.parse("2026-11-03T03:30:00Z")): ReviewItem =
    ReviewItem(ReviewItemId(id), SourceRef("sms:v1:abc$id"), text, MessageOrigin(origin), receivedAt, fallbackAttempted = false, proposalJson = null)

/** Reader controls that only record the calls, so a test can see whether enabling happened. */
class FakeMoneyReaderControls(
    sms: MoneyReaderState = MoneyReaderState.OFF,
    history: HistoryImportState = HistoryImportState.None,
    notifications: MoneyReaderState = MoneyReaderState.OFF,
) : MoneyReaderControls {
    private val smsFlow = MutableStateFlow(sms)
    private val historyFlow = MutableStateFlow(history)
    private val notificationFlow = MutableStateFlow(notifications)

    var smsEnables = 0
    var smsDisables = 0
    var imports = 0
    var declines = 0
    var notificationEnables = 0
    var settingsOpened = 0
    var refreshes = 0

    override val smsState: StateFlow<MoneyReaderState> = smsFlow.asStateFlow()
    override val historyImport: StateFlow<HistoryImportState> = historyFlow.asStateFlow()
    override val notificationState: StateFlow<MoneyReaderState> = notificationFlow.asStateFlow()

    override suspend fun enableSms() {
        smsEnables++
        smsFlow.value = MoneyReaderState.ACTIVE
    }

    override suspend fun disableSms() {
        smsDisables++
        smsFlow.value = MoneyReaderState.OFF
    }

    override suspend fun runHistoryImport() {
        imports++
    }

    override suspend fun declineHistoryImport() {
        declines++
        historyFlow.value = HistoryImportState.None
    }

    override suspend fun enableNotifications() {
        notificationEnables++
        notificationFlow.value = MoneyReaderState.NEEDS_PERMISSION
    }

    override suspend fun disableNotifications() {
        notificationFlow.value = MoneyReaderState.OFF
    }

    override fun openNotificationAccessSettings() {
        settingsOpened++
    }

    override suspend fun refreshNotifications() {
        refreshes++
    }

    /** Pretends the User granted access in Android's settings. */
    fun grantNotificationAccess() {
        notificationFlow.value = MoneyReaderState.ACTIVE
    }

    fun setHistory(state: HistoryImportState) {
        historyFlow.value = state
    }
}

/** A Bank_Sender_List over a list, with the real header rules. */
class FakeSenderStore(builtin: List<String> = listOf("HDFCBK")) : SenderStore {
    private val rows = MutableStateFlow(builtin.map { SenderRow(SenderEntry(it), builtin = true) })

    override fun senders(): Flow<List<SenderRow>> = rows

    override suspend fun addCustom(rawHeader: String): AddSenderResult {
        val core = SenderRules.normalise(rawHeader) ?: return AddSenderResult.INVALID
        if (rows.value.any { it.entry.coreHeader == core }) return AddSenderResult.ALREADY_LISTED
        rows.update { it + SenderRow(SenderEntry(core), builtin = false) }
        return AddSenderResult.ADDED
    }

    override suspend fun removeCustom(coreHeader: String) {
        rows.update { list -> list.filterNot { !it.builtin && it.entry.coreHeader == coreHeader } }
    }
}

class FakeMoneyAppStore(initial: Set<String> = emptySet()) : MoneyAppStore {
    private val listed = MutableStateFlow(initial)

    val listedNow: Set<String> get() = listed.value

    override fun apps(): Flow<Set<String>> = listed

    override suspend fun add(packageName: String): AddAppResult {
        if (!MoneyAppRules.isValidPackage(packageName)) return AddAppResult.INVALID
        if (packageName in listed.value) return AddAppResult.ALREADY_LISTED
        listed.update { it + packageName }
        return AddAppResult.ADDED
    }

    override suspend fun remove(packageName: String) {
        listed.update { it - packageName }
    }
}

class FakeInstalledApps(private val apps: List<InstalledApp> = emptyList()) : InstalledApps {
    override suspend fun launcherApps(): List<InstalledApp> = apps

    override suspend fun label(packageName: String): String? = apps.firstOrNull { it.packageName == packageName }?.label
}

/** A Quality_Monitor that answers the accuracy per origin from a map; origins not in the map have no data. */
class FakeAccuracyMonitor(private val values: Map<String, AccuracyValue> = emptyMap()) : QualityMonitor {
    override suspend fun recordParse(origin: MessageOrigin, day: LocalDate, outcome: ParseOutcomeKind) = Unit

    override suspend fun recordCorrection(sourceRef: SourceRef) = Unit

    override fun accuracy(origin: MessageOrigin, today: LocalDate): Flow<AccuracyValue> = MutableStateFlow(values[origin.value] ?: AccuracyValue.NoData)
}

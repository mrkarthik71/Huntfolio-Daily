package app.assistant.ui.money.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.AddSenderResult
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.InstalledApp
import app.assistant.domain.port.InstalledApps
import app.assistant.domain.port.MoneyAppStore
import app.assistant.domain.port.MoneyReaderControls
import app.assistant.domain.port.MoneyReaderState
import app.assistant.domain.port.SenderRow
import app.assistant.domain.port.SenderStore
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One app row: installed apps and any listed app that is no longer installed. */
data class MoneyAppRow(val packageName: String, val label: String, val listed: Boolean)

/** The parse accuracy of one Message_Origin (Requirement 36.2). */
data class AccuracyRow(val origin: String, val value: AccuracyValue)

/** What the readers report. */
data class ReaderUi(val sms: MoneyReaderState, val historyImport: HistoryImportState, val notifications: MoneyReaderState)

/** Messages from the last add attempts; cleared when the User edits the field. */
data class AddMessages(val sender: AddSenderResult? = null, val app: AddAppResult? = null)

data class MoneySettingsUi(
    val loading: Boolean = true,
    val readers: ReaderUi = ReaderUi(MoneyReaderState.NOT_AVAILABLE, HistoryImportState.None, MoneyReaderState.OFF),
    val senders: List<SenderRow> = emptyList(),
    val apps: List<MoneyAppRow> = emptyList(),
    val accuracy: List<AccuracyRow> = emptyList(),
    val messages: AddMessages = AddMessages(),
)

@HiltViewModel
class MoneySettingsViewModel @Inject constructor(
    private val controls: MoneyReaderControls,
    private val senderStore: SenderStore,
    private val appStore: MoneyAppStore,
    private val installed: InstalledApps,
    private val quality: QualityMonitor,
    private val time: TimeSource,
) : ViewModel() {

    private val messages = MutableStateFlow(AddMessages())
    private val launcherApps = MutableStateFlow<List<InstalledApp>?>(null)

    private val readers: Flow<ReaderUi> = combine(controls.smsState, controls.historyImport, controls.notificationState) { sms, history, notifications ->
        ReaderUi(sms, history, notifications)
    }

    private val appRows: Flow<List<MoneyAppRow>> = combine(appStore.apps(), launcherApps) { listed, launchable ->
        val known = launchable.orEmpty()
        val knownPackages = known.map { it.packageName }.toSet()
        val installedRows = known.map { MoneyAppRow(it.packageName, it.label, it.packageName in listed) }
        val missingRows = listed.filter { it !in knownPackages }.sorted().map { MoneyAppRow(it, installed.label(it) ?: it, listed = true) }
        missingRows + installedRows
    }

    /** Every sender core header and listed app package, each with its accuracy over the last 30 days. */
    private val accuracy: Flow<List<AccuracyRow>> = combine(senderStore.senders(), appStore.apps()) { senders, apps ->
        (senders.map { it.entry.coreHeader } + apps.sorted()).distinct()
    }.flatMapLatest { origins ->
        if (origins.isEmpty()) {
            flowOf(emptyList())
        } else {
            val today = time.today()
            combine(origins.map { origin -> quality.accuracy(MessageOrigin(origin), today).map { AccuracyRow(origin, it) } }) { it.toList() }
        }
    }

    val state: StateFlow<MoneySettingsUi> = combine(
        combine(readers, messages) { r, m -> r to m },
        combine(senderStore.senders(), appRows) { s, a -> s to a },
        accuracy,
    ) { (readerUi, messageUi), (senders, apps), accuracyRows ->
        MoneySettingsUi(false, readerUi, senders, apps, accuracyRows, messageUi)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MoneySettingsUi())

    init {
        viewModelScope.launch { launcherApps.value = installed.launcherApps() }
    }

    /** Called only after the User has read the rationale and confirmed it (Requirement 26.1). */
    fun enableSms() {
        viewModelScope.launch { controls.enableSms() }
    }

    fun disableSms() {
        viewModelScope.launch { controls.disableSms() }
    }

    fun runHistoryImport() {
        viewModelScope.launch { controls.runHistoryImport() }
    }

    fun declineHistoryImport() {
        viewModelScope.launch { controls.declineHistoryImport() }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { if (enabled) controls.enableNotifications() else controls.disableNotifications() }
    }

    fun openNotificationAccessSettings() = controls.openNotificationAccessSettings()

    /** Reads notification access again; call it when the User returns from Android's settings (Requirement 27.5). */
    fun refreshNotifications() {
        viewModelScope.launch { controls.refreshNotifications() }
    }

    fun addSender(raw: String) {
        viewModelScope.launch {
            val result = senderStore.addCustom(raw)
            messages.update { it.copy(sender = result.takeUnless { r -> r == AddSenderResult.ADDED }) }
        }
    }

    fun removeSender(coreHeader: String) {
        viewModelScope.launch { senderStore.removeCustom(coreHeader) }
    }

    fun clearSenderMessage() {
        messages.update { it.copy(sender = null) }
    }

    fun setApp(packageName: String, listed: Boolean) {
        viewModelScope.launch {
            if (listed) {
                val result = appStore.add(packageName)
                messages.update { it.copy(app = result.takeIf { r -> r == AddAppResult.INVALID }) }
            } else {
                appStore.remove(packageName)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

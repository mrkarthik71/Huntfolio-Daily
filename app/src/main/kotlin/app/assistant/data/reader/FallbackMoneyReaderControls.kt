package app.assistant.data.reader

import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.MoneyReaderControls
import app.assistant.domain.port.MoneyReaderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader controls until the real readers exist. SMS reading is not part of this build, so it always reports
 * NOT_AVAILABLE. Notification access cannot be read here, so switching it on reports that permission is still needed.
 */
@Singleton
class FallbackMoneyReaderControls @Inject constructor() : MoneyReaderControls {
    private val sms = MutableStateFlow(MoneyReaderState.NOT_AVAILABLE)
    private val history = MutableStateFlow<HistoryImportState>(HistoryImportState.None)
    private val notifications = MutableStateFlow(MoneyReaderState.OFF)

    override val smsState: StateFlow<MoneyReaderState> = sms.asStateFlow()
    override val historyImport: StateFlow<HistoryImportState> = history.asStateFlow()
    override val notificationState: StateFlow<MoneyReaderState> = notifications.asStateFlow()

    override suspend fun enableSms() = Unit

    override suspend fun disableSms() = Unit

    override suspend fun runHistoryImport() = Unit

    override suspend fun declineHistoryImport() = Unit

    override suspend fun enableNotifications() {
        notifications.update { MoneyReaderState.NEEDS_PERMISSION }
    }

    override suspend fun disableNotifications() {
        notifications.update { MoneyReaderState.OFF }
    }

    override fun openNotificationAccessSettings() = Unit

    override suspend fun refreshNotifications() = Unit
}

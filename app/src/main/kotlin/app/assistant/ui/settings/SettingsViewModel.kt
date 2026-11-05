package app.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.assistant.BuildConfig
import app.assistant.domain.model.AuditEntry
import app.assistant.domain.model.DataSource
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.SourceStatusProvider
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

data class SourceRow(val source: DataSource, val connected: Boolean)

data class SettingsUiState(
    val sources: List<SourceRow> = emptyList(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(providers: Set<@JvmSuppressWildcards SourceStatusProvider>) : ViewModel() {

    val state: StateFlow<SettingsUiState> = sourceRows(providers, BuildConfig.SMS_ENABLED)
        .map { rows -> SettingsUiState(sources = rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    companion object {
        /**
         * Every Data_Source shown on Settings (Requirement 8.1), in a fixed order. A source with no
         * provider yet (its phase is not built) is shown as not connected. The Sms_Free_Build
         * omits SMS (Requirement 67.4).
         */
        fun listedSources(smsEnabled: Boolean): List<DataSource> = listOfNotNull(
            DataSource.SMS.takeIf { smsEnabled },
            DataSource.APP_NOTIFICATIONS,
            DataSource.CALENDAR,
            DataSource.MAILBOX,
            DataSource.AI_PROVIDER,
            DataSource.HUNTFOLIO,
        )

        fun sourceRows(providers: Set<SourceStatusProvider>, smsEnabled: Boolean): Flow<List<SourceRow>> {
            val sources = listedSources(smsEnabled)
            val flows = sources.map { s ->
                providers.firstOrNull { it.source == s }?.connected ?: flowOf(false)
            }
            return combine(flows) { values -> sources.zip(values.toList()) { s, c -> SourceRow(s, c) } }
        }
    }
}

@HiltViewModel
class AuditLogViewModel @Inject constructor(audit: AuditLog, private val time: TimeSource) : ViewModel() {
    val entries: StateFlow<List<AuditEntry>?> = audit.newest(LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Zone for displaying entry times; read fresh so it follows device changes. */
    fun zone(): ZoneId = time.zone()

    private companion object {
        const val LIMIT = 500
    }
}

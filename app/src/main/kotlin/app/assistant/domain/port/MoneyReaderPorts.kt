package app.assistant.domain.port

import app.assistant.domain.model.SenderEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// Ports for the Android-specific parts of the money settings (task 23.2). The screens use only these; the SMS reader,
// the notification reader and the installed-app lookup bind to them in their own tasks.

/** How one reader is doing, for the money settings (Requirements 26.2, 27.4, 27.5). */
enum class MoneyReaderState {
    /** This build cannot read at all (the SMS-free build has no SMS reader). */
    NOT_AVAILABLE,

    /** The User has not switched it on. */
    OFF,

    /** Switched on, but the Android permission or access has not been given. */
    NEEDS_PERMISSION,

    /** Reading. */
    ACTIVE,

    /** Switched on, but Android has stopped delivering, so the User must turn access back on. */
    DISCONNECTED,
}

/** The numbers shown when the one-time SMS_History_Import finishes (Requirement 26.5). */
data class HistoryImportSummary(val smsRead: Int, val transactionsCreated: Int, val reviewItems: Int)

/** Where the one-time SMS_History_Import is (Requirement 26.2). */
sealed interface HistoryImportState {
    /** Nothing to offer: reading is off, or the offer was already answered. */
    data object None : HistoryImportState

    /** The offer is waiting for the User. */
    data object Offered : HistoryImportState

    data object Running : HistoryImportState

    data class Done(val summary: HistoryImportSummary) : HistoryImportState
}

/**
 * The switches the money settings drive. Enabling asks Android for whatever permission is needed, and the caller shows the
 * rationale before it calls (Requirement 26.1). Nothing here reads message content.
 */
interface MoneyReaderControls {
    val smsState: StateFlow<MoneyReaderState>

    val historyImport: StateFlow<HistoryImportState>

    val notificationState: StateFlow<MoneyReaderState>

    suspend fun enableSms()

    suspend fun disableSms()

    /** Reads the last 3 calendar months of Bank_Sender_List SMS once (Requirement 26.2). */
    suspend fun runHistoryImport()

    suspend fun declineHistoryImport()

    suspend fun enableNotifications()

    suspend fun disableNotifications()

    /** Opens Android's notification access screen. */
    fun openNotificationAccessSettings()

    /** Reads access again after the User comes back from Android's settings: on only when access was granted (Requirement 27.5). */
    suspend fun refreshNotifications()
}

/** One installed app the User can put on the Money_App_List. */
data class InstalledApp(val packageName: String, val label: String)

/** The apps on the phone (Requirement 27.2). */
interface InstalledApps {
    /** Apps that have a launcher entry, sorted by label. */
    suspend fun launcherApps(): List<InstalledApp>

    /** The label of [packageName], or null when it is not installed. */
    suspend fun label(packageName: String): String?
}

/** A Bank_Sender_List row for display; [builtin] rows ship with the app and cannot be removed. */
data class SenderRow(val entry: SenderEntry, val builtin: Boolean)

enum class AddSenderResult { ADDED, ALREADY_LISTED, INVALID }

/** The Bank_Sender_List the User edits (Requirement 26.4). */
interface SenderStore {
    fun senders(): Flow<List<SenderRow>>

    suspend fun addCustom(rawHeader: String): AddSenderResult

    /** Removes a custom entry only; a built-in one stays. */
    suspend fun removeCustom(coreHeader: String)
}

/** What a custom sender header may look like: 2 to 20 letters or digits, kept in capitals. */
object SenderRules {
    private const val MIN_LENGTH = 2
    private const val MAX_LENGTH = 20
    private val VALID = Regex("^[A-Za-z0-9]{$MIN_LENGTH,$MAX_LENGTH}$")

    /** Optional DLT-style operator prefix (`AX-`) and suffix (`-S`) stored with a custom entry, as the shipped entries have. */
    const val CUSTOM_PREFIX = "??-"
    const val CUSTOM_SUFFIX = "-?"

    /** The header in capitals, or null when [raw] is not a valid core header. */
    fun normalise(raw: String): String? = raw.trim().takeIf { VALID.matches(it) }?.uppercase(Locale.ROOT)
}

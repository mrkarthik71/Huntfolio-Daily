package app.assistant.domain.port

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.SenderEntry
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Where the Duplicate_Window comes from. A fixed 15 minutes until the setting exists (design: Duplicate_Window, configurable). */
fun interface DuplicateWindowProvider {
    suspend fun window(): Duration
}

/** The Transaction a stored message ended up in. */
data class IngestResult(
    val transaction: TransactionId,
    /** True when this message started a new Transaction; false when it joined an existing one (Requirements 29.1, 29.2). */
    val created: Boolean,
    /** True when this Source_Ref was already stored, so nothing new was added (Requirement 29.3). */
    val alreadyStored: Boolean,
)

enum class IngestError { TOMBSTONED }

/** Stored as the Bank_Format of a message when the caller does not know it (tests, manual imports). */
const val FORMAT_UNKNOWN = "unknown"

sealed interface EditFailure {
    data class Invalid(val errors: FieldErrors<EditedField>) : EditFailure

    data object NotFound : EditFailure
}

enum class CategoryChangeError { NOT_FOUND, UNKNOWN_CATEGORY }

/** The offer to apply a corrected category to earlier Transactions of the same Merchant (Requirement 31.4). */
data class CategoryOffer(
    val merchant: MerchantId,
    val category: CategoryId,
    /** The changed Transaction; only Transactions before it in (time, id) order are offered. */
    val anchor: TransactionId,
    val anchorAt: Instant,
    /** How many earlier Transactions would change. Always more than zero: no offer is made otherwise. */
    val count: Int,
)

/** Stored Transactions and every change to them (Requirements 29, 31, 32). */
interface TransactionRepository {
    /** Newest first by (time, id). */
    fun transactions(): Flow<List<Transaction>>

    suspend fun get(id: TransactionId): Transaction?

    /**
     * Stores [fact] and regroups the payments it touches: the message joins the Transaction for the same payment or
     * starts a new one, in any arrival order. Storing the same Source_Ref again changes nothing (Requirement 29.3).
     * A tombstoned Source_Ref creates nothing (Requirement 32.7).
     */
    suspend fun ingest(fact: ParsedFact, formatId: String = FORMAT_UNKNOWN): Outcome<IngestResult, IngestError>

    /** Stores a Transaction with source MANUAL and no Source_Ref, outside duplicate detection (Requirement 32.3). */
    suspend fun addManual(input: ManualTransactionInput): Outcome<TransactionId, FieldErrors<EditedField>>

    /** Stores the given fields and marks them as edited by the User; an invalid edit changes nothing (Requirements 32.1, 32.6). */
    suspend fun edit(id: TransactionId, edit: TransactionEdit): Outcome<Unit, EditFailure>

    /** Keeps the lower id, with the Source_Refs of both and the selected values; the other is removed (Requirements 32.4, 32.8). */
    suspend fun merge(first: TransactionId, second: TransactionId, selection: MergeSelection): Outcome<TransactionId, MergeError>

    /** Removes the Transaction and tombstones its Source_Refs so the same messages never recreate it (Requirement 32.5, 32.7). */
    suspend fun delete(id: TransactionId)

    /**
     * Sets the category, records the correction for the Merchant and returns the offer to apply it to earlier
     * Transactions, or null when there is nothing to offer (Requirements 31.3, 31.4).
     */
    suspend fun changeCategory(id: TransactionId, category: CategoryId): Outcome<CategoryOffer?, CategoryChangeError>

    /**
     * Changes the category of every earlier Transaction of the Merchant except those the User set individually
     * (Requirement 31.6). Returns how many changed.
     */
    suspend fun applyCategoryToEarlier(offer: CategoryOffer): Int
}

/** A message the parsers could not read, waiting for the User. It holds redacted text only (Requirements 30.1, 30.2). */
data class ReviewItem(
    val id: ReviewItemId,
    val sourceRef: SourceRef,
    val redactedText: String,
    val origin: MessageOrigin,
    val receivedAt: Instant,
    /** True when the SMS fallback parser already tried this message (Phase 5, Requirement 49). */
    val fallbackAttempted: Boolean,
    /** The AI_Provider's proposed fields for the User to confirm or edit, as JSON (Requirement 30.5). */
    val proposalJson: String?,
)

enum class ReviewAddResult {
    ADDED,

    /** The Source_Ref already has an open item, so no second one is made (Requirement 30.1). */
    ALREADY_OPEN,

    /** The Source_Ref was dismissed or belonged to a deleted Transaction, so it never comes back (Requirements 30.4, 32.7). */
    TOMBSTONED,
}

/** The Review_Queue (Requirement 30). */
interface ReviewQueueRepository {
    /**
     * Adds one item for [ref], holding [redactedText], which the caller must already have run through the Redactor
     * (Requirement 4.3): this class stores what it is given and never sees the original message.
     * Only SMS and app-notification Source_Refs belong here.
     */
    suspend fun add(
        ref: SourceRef,
        redactedText: String,
        origin: MessageOrigin,
        receivedAt: Instant,
        fallbackAttempted: Boolean = false,
        proposalJson: String? = null,
    ): ReviewAddResult

    suspend fun get(id: ReviewItemId): ReviewItem?

    /** Open items, newest Received_Time first (Requirement 30.8). */
    fun openItemsNewestFirst(): Flow<List<ReviewItem>>

    /** The number of open items, for the Money_Screen (Requirement 30.7). */
    fun openCount(): Flow<Int>

    /**
     * Creates the Transaction for the item's Source_Ref through the Deduplicator, with the entered fields marked as
     * edited by the User, and deletes the item and its text (Requirement 30.3). Invalid input changes nothing and
     * keeps the item (Requirement 30.6).
     */
    suspend fun resolve(id: ReviewItemId, input: ManualTransactionInput): Outcome<TransactionId, EditFailure>

    /** Deletes the item and its text and tombstones the Source_Ref (Requirement 30.4). */
    suspend fun dismiss(id: ReviewItemId)

    /**
     * Marks an item as having had the SMS fallback parser run against it, exactly once (Requirement
     * 49.1, 49.4): [proposalJson] holds the accepted proposal for the user to confirm, or null when
     * the attempt produced nothing. Once attempted, the item is never retried.
     */
    suspend fun markFallbackAttempted(id: ReviewItemId, proposalJson: String?)
}

// Ingest pipeline ports (task 20.6). The pipeline lives in the ingest layer, which may not use data, so it
// reaches the stores through these.

/** The enabled Bank_Sender_List entries: the shipped ones and the User's additions (Requirement 26.4). */
fun interface SenderList {
    suspend fun entries(): List<SenderEntry>
}

/** The Money_App_List: apps whose notifications may be read (Requirement 27). */
fun interface MoneyAppList {
    suspend fun contains(packageName: String): Boolean
}

enum class RefState {
    /** Nothing is known about this Source_Ref yet. */
    NEW,

    /** Already handled once, whatever the outcome (Requirement 29.3). */
    PROCESSED,

    /** The User deleted or dismissed what this message made, so it must create nothing (Requirements 30.4, 32.7). */
    TOMBSTONED,
}

/**
 * Remembers which Source_Refs the pipeline has handled. A row holds the Source_Ref, the outcome name and the time:
 * never message content. An OTP is recorded the same way, so a catch-up read does not reparse it.
 */
interface IngestLedger {
    suspend fun state(ref: SourceRef): RefState

    suspend fun markProcessed(ref: SourceRef, outcome: String, at: Instant)
}

/** Bill proposals made from bill messages (Requirement 34.2, 34.8). */
fun interface BillProposals {
    /**
     * Proposes a Bill for [merchant] with [amount] and [dueDate] (null when the message had none) unless a Bill or an
     * open proposal with the same Merchant, amount and due date already exists. Returns true when a proposal was made.
     */
    suspend fun propose(merchant: String?, amount: Paise, dueDate: LocalDate?): Boolean
}

/** Runs [block] as one all-or-nothing unit of storage work, so a message is never stored without being recorded as handled. */
interface AtomicWork {
    suspend fun <T> run(block: suspend () -> T): T
}

/** What happened when the User tried to add an app to the Money_App_List. */
enum class AddAppResult { ADDED, ALREADY_LISTED, INVALID }

/** Whether [name] can be an Android application id: dot-separated segments, each starting with a letter, at least two. */
object MoneyAppRules {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private const val MAX_LENGTH = 255

    fun isValidPackage(name: String): Boolean = name.length <= MAX_LENGTH && PACKAGE.matches(name)
}

/**
 * The Money_App_List the User edits on the Settings_Screen (Requirement 27.2). Removing an app stops its notifications being
 * read from then on and leaves the Transactions already made from it (Requirement 27.7).
 */
interface MoneyAppStore {
    fun apps(): Flow<Set<String>>

    suspend fun add(packageName: String): AddAppResult

    suspend fun remove(packageName: String)
}

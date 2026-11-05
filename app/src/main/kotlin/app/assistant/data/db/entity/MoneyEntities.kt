package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Phase 3 Money entities (design: Phase 3 entities). Money is integer paise, instants are epoch
// milliseconds, dates are ISO yyyy-MM-dd text. No table holds Message_Text; the single exception
// is review_item.redacted_text (Requirement 30.2), which is deleted on resolve or dismiss.

/** What one message said, keyed by its Source_Ref. One Transaction can own several of these (Requirements 29.1, 32.4). */
@Entity(tableName = "parsed_fact", indices = [Index("type", "amount_paise", "at"), Index("transaction_id")])
data class ParsedFactEntity(
    @PrimaryKey @ColumnInfo(name = "source_ref") val sourceRef: String,
    val origin: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    val type: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val at: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val reference: String?,
    val account4: String?,
    @ColumnInfo(name = "merchant_or_vpa") val merchantOrVpa: String?,
    @ColumnInfo(name = "balance_paise") val balancePaise: Long?,
    @ColumnInfo(name = "is_reversal") val isReversal: Boolean,
    @ColumnInfo(name = "format_id") val formatId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
)

/** A Transaction. It has no source_ref column: the link is parsed_fact.transaction_id. */
@Entity(tableName = "txn", indices = [Index("at"), Index("category_id"), Index("merchant_id")])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val at: Long,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    /** SMS, APP, MANUAL or MIXED. */
    val source: String,
    val reference: String?,
    val account4: String?,
    @ColumnInfo(name = "balance_paise") val balancePaise: Long?,
    @ColumnInfo(name = "is_reversal") val isReversal: Boolean,
    /** Bitmask of the fields the User edited (Requirement 32.1). */
    @ColumnInfo(name = "edited_fields") val editedFields: Int,
    /** True when the User set the category individually, so a correction never overrides it (Requirement 31.6). */
    @ColumnInfo(name = "category_user_set") val categoryUserSet: Boolean,
    /** When the User last edited a field (epoch milliseconds), or null if never; the newest edit wins when Transactions collapse (Requirement 29.6). */
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
)

/** A Source_Ref the User forced into a merge group (Requirement 32.4). */
@Entity(tableName = "forced_merge", primaryKeys = ["source_ref"])
data class ForcedMergeEntity(@ColumnInfo(name = "source_ref") val sourceRef: String, @ColumnInfo(name = "group_id") val groupId: Long)

/** A Source_Ref that must never be ingested again (Requirements 30.4, 32.7). [reason] is TXN_DELETED or REVIEW_DISMISSED. */
@Entity(tableName = "tombstone")
data class TombstoneEntity(@PrimaryKey @ColumnInfo(name = "source_ref") val sourceRef: String, val reason: String, val at: Long)

/** The idempotency record for a Source_Ref that was already handled (Requirement 29.3). */
@Entity(tableName = "processed_source_ref")
data class ProcessedRefEntity(@PrimaryKey @ColumnInfo(name = "source_ref") val sourceRef: String, val outcome: String, val at: Long)

/** A message the parsers could not read, held with redacted text only (Requirement 30). One row per Source_Ref. */
@Entity(tableName = "review_item", indices = [Index(value = ["source_ref"], unique = true), Index("received_at")])
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    @ColumnInfo(name = "redacted_text") val redactedText: String,
    val origin: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "fallback_attempted") val fallbackAttempted: Boolean,
    @ColumnInfo(name = "proposal_json") val proposalJson: String?,
)

/** A payee. [nameNorm] is the lower-cased trimmed name used for matching (Requirement 31.2). */
@Entity(tableName = "merchant", indices = [Index(value = ["name_norm"], unique = true)])
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long?,
    val builtin: Boolean,
)

/** Another name or VPA a Merchant is known by, normalised the same way as [MerchantEntity.nameNorm]. */
@Entity(tableName = "merchant_alias", indices = [Index(value = ["alias_norm"], unique = true)])
data class MerchantAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_id") val merchantId: Long,
    @ColumnInfo(name = "alias_norm") val aliasNorm: String,
)

/** A category. The built-in ones have the fixed ids given by their position in BuiltinCategories.NAMES plus one. */
@Entity(tableName = "category")
data class CategoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val builtin: Boolean)

/** One User correction of a Merchant's category; the latest wins (Requirement 31.1). */
@Entity(tableName = "category_correction", indices = [Index("merchant_id", "at")])
data class CategoryCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_id") val merchantId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val at: Long,
)

/** A monthly spending limit for one category. */
@Entity(tableName = "budget")
data class BudgetEntity(@PrimaryKey @ColumnInfo(name = "category_id") val categoryId: Long, @ColumnInfo(name = "amount_paise") val amountPaise: Long)

/** A bill to pay. [recurrence] is NONE, MONTHLY or YEARLY; occurrences of one recurring bill share [seriesId]. */
@Entity(tableName = "bill", indices = [Index("due_date", "paid")])
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "due_date") val dueDate: String,
    val recurrence: String,
    @ColumnInfo(name = "anchor_day") val anchorDay: Int,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    val paid: Boolean,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    val notified: Boolean,
)

/** A bill found in a message, waiting for the User to accept or dismiss it. */
@Entity(tableName = "bill_proposal", indices = [Index(value = ["merchant_id", "amount_paise", "due_date"], unique = true)])
data class BillProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_id") val merchantId: Long?,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    val status: String,
)

/** A suggestion that a Transaction pays a Bill, waiting for the User. */
@Entity(tableName = "bill_paid_proposal", primaryKeys = ["transaction_id", "bill_id"])
data class BillPaidProposalEntity(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "bill_id") val billId: Long,
    val status: String,
)

/** One Bank_Sender_List entry, shipped (builtin) or added by the User. */
@Entity(tableName = "sender_entry")
data class SenderEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val core: String,
    @ColumnInfo(name = "prefix_pattern") val prefixPattern: String?,
    @ColumnInfo(name = "suffix_pattern") val suffixPattern: String?,
    val builtin: Boolean,
    val enabled: Boolean,
)

/** An app whose notifications the User allowed the Notification_Reader to read (Money_App_List). */
@Entity(tableName = "money_app")
data class MoneyAppEntity(@PrimaryKey @ColumnInfo(name = "package_name") val packageName: String)

/** A per-origin, per-day count for the Quality_Monitor. [metric] is PARSED, REVIEW, CORRECTED and so on. Holds counts only. */
@Entity(tableName = "quality_counter", primaryKeys = ["origin", "day", "metric"])
data class QualityCounterEntity(val origin: String, val day: String, val metric: String, val count: Int)

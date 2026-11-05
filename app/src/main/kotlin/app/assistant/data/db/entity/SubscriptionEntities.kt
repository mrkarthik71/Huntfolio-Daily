package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Phase 4 Subscription entities (design: Phase 4 entities). Money is integer paise, dates are ISO
// yyyy-MM-dd text, instants are epoch milliseconds. No table holds Message_Text.

/**
 * A repeating charge (Glossary: Subscription). status is PROPOSED, ACTIVE, REJECTED or ENDED. [anchorDay] drives the
 * Due_Date_Rule. A Merchant+interval has at most one row in any status, so a rejected one is remembered (Requirement 37.3).
 */
@Entity(tableName = "subscription", indices = [Index(value = ["merchant_id", "interval"], unique = true), Index("status"), Index("next_due")])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_id") val merchantId: Long,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val interval: String,
    @ColumnInfo(name = "anchor_day") val anchorDay: Int,
    @ColumnInfo(name = "next_due") val nextDue: String,
    @ColumnInfo(name = "activated_on") val activatedOn: String,
    @ColumnInfo(name = "last_usage_check") val lastUsageCheck: String?,
    @ColumnInfo(name = "cancelled_on") val cancelledOn: String?,
    /** Consecutive "No" usage answers; reset on "Yes" and after a review Suggestion (Requirement 40.1, 40.2). */
    @ColumnInfo(name = "consecutive_no") val consecutiveNo: Int,
    val status: String,
)

/** A debit Transaction matched to a Subscription as one of its charges (Requirement 38.4). At most once per Transaction. */
@Entity(tableName = "subscription_charge", primaryKeys = ["transaction_id"], indices = [Index("subscription_id")])
data class SubscriptionChargeEntity(
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
    @ColumnInfo(name = "charged_on") val chargedOn: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
)

/** One monthly Usage_Check answer for a Subscription (Requirement 39.2). */
@Entity(tableName = "usage_check", indices = [Index("subscription_id")])
data class UsageCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
    @ColumnInfo(name = "due_on") val dueOn: String,
    @ColumnInfo(name = "answered_at") val answeredAt: Long?,
    /** True = used it ("Yes"), false = did not ("No"), null = still due with no answer (Requirement 39.5). */
    @ColumnInfo(name = "used_it") val usedIt: Boolean?,
)

/**
 * A Suggestion shown to the User (Requirement 50). [prompt_type] is null for rule-based ones (their own accept-rate
 * group). [change_json] carries the exact ProposedChange an accept applies; null for advice-only. response is null until
 * answered, then ACCEPTED, DISMISSED or IGNORED.
 */
@Entity(tableName = "suggestion", indices = [Index("response"), Index("shown_at")])
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "prompt_type") val promptType: String?,
    val text: String,
    val reason: String,
    @ColumnInfo(name = "shown_at") val shownAt: Long,
    @ColumnInfo(name = "responded_at") val respondedAt: Long?,
    val response: String?,
    @ColumnInfo(name = "change_json") val changeJson: String?,
)

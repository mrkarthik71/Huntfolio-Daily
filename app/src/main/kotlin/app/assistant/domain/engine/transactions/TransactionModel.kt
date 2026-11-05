package app.assistant.domain.engine.transactions

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import java.time.Instant

/** Where a Transaction's data came from. [MIXED] is an SMS and an app notification for the same payment; [MANUAL] has no Source_Ref. */
enum class TxnSource { SMS, APP, MANUAL, MIXED }

/** A stored money movement (Glossary: Transaction). The Source_Refs are the messages it was built from. */
data class Transaction(
    val id: TransactionId,
    val type: TxnType,
    val amount: Paise,
    val at: Instant,
    val merchantId: MerchantId?,
    val merchantName: String?,
    val category: CategoryId,
    val source: TxnSource,
    val reference: String?,
    val account4: String?,
    val balance: Paise?,
    val isReversal: Boolean,
    val editedFields: Set<EditedField>,
    /** True when the User set the category individually, so a correction never overrides it (Requirement 31.6). */
    val categoryUserSet: Boolean,
    val sourceRefs: List<SourceRef>,
)

/** The User-edited fields as the bitmask stored in `txn.edited_fields` (Requirement 32.1). One bit per [EditedField] ordinal. */
object EditedFieldsMask {
    fun encode(fields: Set<EditedField>): Int = fields.fold(0) { mask, field -> mask or (1 shl field.ordinal) }

    fun decode(mask: Int): Set<EditedField> = EditedField.entries.filterTo(LinkedHashSet()) { mask and (1 shl it.ordinal) != 0 }
}

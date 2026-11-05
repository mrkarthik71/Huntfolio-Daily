package app.assistant.domain.engine.dedupe

import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import java.time.Instant

/**
 * The SMS sender ID or the app package a Money_Message came from (Glossary: Message_Origin).
 * Compared ignoring letter case and surrounding space.
 */
@JvmInline
value class MessageOrigin(val value: String)

/**
 * What the Message_Parser extracted from one Money_Message and the app keeps: the fields only,
 * never the text (design: ParsedFact). Facts are the input to dedupe, so a payment can be
 * regrouped whenever another message for it arrives, whatever order they arrive in.
 */
data class ParsedFact(
    val sourceRef: SourceRef,
    val origin: MessageOrigin,
    val sourceKind: SourceKind,
    val type: TxnType,
    val amount: Paise,
    val at: Instant,
    val receivedAt: Instant,
    val reference: String?,
    val account4: String?,
    val merchantOrVpa: String?,
    val balance: Paise?,
    val isReversal: Boolean,
)

/** The facts that describe one payment, in canonical order (Requirement 29). */
data class Cluster(val facts: List<ParsedFact>) {
    init {
        require(facts.isNotEmpty()) { "A cluster holds at least one fact" }
    }

    val sourceRefs: List<SourceRef> get() = facts.map { it.sourceRef }
}

/** A Transaction field the User has edited (Requirement 32.1). */
enum class EditedField { TYPE, AMOUNT, DATE, MERCHANT }

/** Fields the User edited; a non-null value wins over every message (Requirements 29.6, 32.2). */
data class UserEdits(val type: TxnType? = null, val amount: Paise? = null, val at: Instant? = null, val merchant: String? = null) {
    val fields: Set<EditedField>
        get() = buildSet {
            if (type != null) add(EditedField.TYPE)
            if (amount != null) add(EditedField.AMOUNT)
            if (at != null) add(EditedField.DATE)
            if (merchant != null) add(EditedField.MERCHANT)
        }
}

/** A cluster read as one Transaction. */
data class ResolvedTransaction(
    val type: TxnType,
    val amount: Paise,
    val at: Instant,
    val merchantOrVpa: String?,
    val reference: String?,
    val account4: String?,
    val balance: Paise?,
    val isReversal: Boolean,
    val sourceRefs: List<SourceRef>,
    val editedFields: Set<EditedField>,
)

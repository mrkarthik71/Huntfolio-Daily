@file:Suppress("ComplexCondition", "ReturnCount")

package app.assistant.testing

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.MergeSide
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TransactionMerge
import app.assistant.domain.engine.transactions.TransactionValidator
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.CategoryChangeError
import app.assistant.domain.port.CategoryOffer
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.IngestError
import app.assistant.domain.port.IngestResult
import app.assistant.domain.port.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A Transaction for tests, with the fields a screen shows and neutral values for the rest. */
fun fakeTxn(
    id: Long,
    type: TxnType = TxnType.DEBIT,
    amountPaise: Long = 50_000,
    at: Instant = Instant.parse("2026-11-02T06:30:00Z"),
    merchant: String? = "Swiggy",
    category: CategoryId = CategoryId.UNCATEGORISED,
): Transaction = Transaction(
    id = TransactionId(id),
    type = type,
    amount = Paise(amountPaise),
    at = at,
    merchantId = null,
    merchantName = merchant,
    category = category,
    source = TxnSource.MANUAL,
    reference = null,
    account4 = null,
    balance = null,
    isReversal = false,
    editedFields = emptySet(),
    categoryUserSet = false,
    sourceRefs = emptyList(),
)

/**
 * An in-memory [TransactionRepository] that applies the real validation and merge rules and records every write.
 * [ingest] is not needed by the Transactions screen and fails loudly.
 */
class FakeMoneyTransactions(
    initial: List<Transaction> = emptyList(),
    private val today: LocalDate = LocalDate.of(2026, 11, 3),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : TransactionRepository {
    private val all = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id.value } ?: 0L) + 1L

    /** What [changeCategory] offers next; null means no offer. */
    var offerToReturn: CategoryOffer? = null

    /** What [applyCategoryToEarlier] reports as changed. */
    var appliedCount: Int = 0

    val edits = mutableListOf<Pair<TransactionId, TransactionEdit>>()
    val merges = mutableListOf<Triple<TransactionId, TransactionId, MergeSelection>>()
    val deleted = mutableListOf<TransactionId>()
    val categoryChanges = mutableListOf<Pair<TransactionId, CategoryId>>()
    val appliedOffers = mutableListOf<CategoryOffer>()

    val current: List<Transaction> get() = all.value

    override fun transactions(): Flow<List<Transaction>> = all.map { list ->
        list.sortedWith(compareByDescending<Transaction> { it.at }.thenByDescending { it.id.value })
    }

    override suspend fun get(id: TransactionId): Transaction? = all.value.firstOrNull { it.id == id }

    override suspend fun ingest(fact: ParsedFact, formatId: String): Outcome<IngestResult, IngestError> = error("ingest is not used by the Transactions screen")

    override suspend fun addManual(input: ManualTransactionInput): Outcome<TransactionId, FieldErrors<EditedField>> {
        val errors = TransactionValidator.validateNew(input, today, zone)
        val type = input.type
        val amount = input.amount
        val at = input.at
        if (!errors.isValid || type == null || amount == null || at == null) return Outcome.Err(errors)
        val id = TransactionId(nextId++)
        val created = fakeTxn(id.value, type, amount.value, at, input.merchant.trim().ifEmpty { null })
        all.value = all.value + created
        return Outcome.Ok(id)
    }

    override suspend fun edit(id: TransactionId, edit: TransactionEdit): Outcome<Unit, EditFailure> {
        val existing = get(id) ?: return Outcome.Err(EditFailure.NotFound)
        val errors = TransactionValidator.validateEdit(edit, today, zone)
        if (!errors.isValid) return Outcome.Err(EditFailure.Invalid(errors))
        edits += id to edit
        val merchant = edit.merchant
        val updated = existing.copy(
            type = edit.type ?: existing.type,
            amount = edit.amount ?: existing.amount,
            at = edit.at ?: existing.at,
            merchantName = if (merchant == null) existing.merchantName else merchant.trim().ifEmpty { null },
        )
        all.value = all.value.map { if (it.id == id) updated else it }
        return Outcome.Ok(Unit)
    }

    override suspend fun merge(first: TransactionId, second: TransactionId, selection: MergeSelection): Outcome<TransactionId, MergeError> {
        if (first == second) return Outcome.Err(MergeError.SAME_TRANSACTION)
        val a = get(first) ?: return Outcome.Err(MergeError.NOT_FOUND)
        val b = get(second) ?: return Outcome.Err(MergeError.NOT_FOUND)
        TransactionMerge.check(a.type, b.type)?.let { return Outcome.Err(it) }
        merges += Triple(first, second, selection)
        val keepId = if (first.value <= second.value) first else second
        val merged = a.copy(
            id = keepId,
            amount = if (selection.amount == MergeSide.FIRST) a.amount else b.amount,
            at = if (selection.date == MergeSide.FIRST) a.at else b.at,
            merchantName = if (selection.merchant == MergeSide.FIRST) a.merchantName else b.merchantName,
        )
        all.value = all.value.filter { it.id != first && it.id != second } + merged
        return Outcome.Ok(keepId)
    }

    override suspend fun delete(id: TransactionId) {
        deleted += id
        all.value = all.value.filter { it.id != id }
    }

    override suspend fun changeCategory(id: TransactionId, category: CategoryId): Outcome<CategoryOffer?, CategoryChangeError> {
        val existing = get(id) ?: return Outcome.Err(CategoryChangeError.NOT_FOUND)
        categoryChanges += id to category
        all.value = all.value.map { if (it.id == id) existing.copy(category = category, categoryUserSet = true) else it }
        return Outcome.Ok(offerToReturn)
    }

    override suspend fun applyCategoryToEarlier(offer: CategoryOffer): Int {
        appliedOffers += offer
        return appliedCount
    }
}

/** A [CategoryOffer] for tests. */
fun fakeOffer(count: Int, category: CategoryId, anchor: TransactionId, anchorAt: Instant = Instant.parse("2026-11-02T06:30:00Z")): CategoryOffer =
    CategoryOffer(merchant = MerchantId(1), category = category, anchor = anchor, anchorAt = anchorAt, count = count)

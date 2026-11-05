package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.DbProvider
import app.assistant.data.db.dao.TransactionRow
import app.assistant.data.db.entity.CategoryCorrectionEntity
import app.assistant.data.db.entity.ForcedMergeEntity
import app.assistant.data.db.entity.ParsedFactEntity
import app.assistant.data.db.entity.TombstoneEntity
import app.assistant.data.db.entity.TransactionEntity
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.dedupe.UserEdits
import app.assistant.domain.engine.transactions.EditedFieldsMask
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.StoredTxn
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TransactionMerge
import app.assistant.domain.engine.transactions.TransactionReconciler
import app.assistant.domain.engine.transactions.TransactionValidator
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.CategoryChangeError
import app.assistant.domain.port.CategoryOffer
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.IngestError
import app.assistant.domain.port.IngestResult
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores Transactions and keeps them in step with the messages they were built from (Requirements 29, 31, 32).
 *
 * The Deduplicator is pure and decides from a *set* of messages which belong together. This class loads the
 * connected neighbourhood of what changed, lets [TransactionReconciler] map the resulting groups onto stored rows
 * (keeping ids), and writes the difference, all in one Room transaction. Because grouping depends only on the set
 * of messages, the stored result does not depend on the order they arrived in (Requirement 29.4).
 */
@Singleton
class RoomTransactionRepository @Inject constructor(
    private val holder: DbProvider,
    private val time: TimeSource,
    private val duplicateWindow: DuplicateWindowProvider,
) : TransactionRepository {
    private val db get() = holder.database()
    private val dao get() = db.transactionDao()
    private val catalog get() = db.merchantCatalogDao()

    override fun transactions(): Flow<List<Transaction>> = combine(dao.observeRows(), dao.observeLinks()) { rows, links ->
        val refs = links.groupBy({ it.transactionId }, { SourceRef(it.sourceRef) })
        rows.map { it.toDomain(refs[it.txn.id].orEmpty()) }
    }

    override suspend fun get(id: TransactionId): Transaction? = dao.row(id.value)?.let {
        it.toDomain(dao.linksOf(id.value).map { l -> SourceRef(l.sourceRef) })
    }

    // Ingest

    override suspend fun ingest(fact: ParsedFact, formatId: String): Outcome<IngestResult, IngestError> {
        require(fact.sourceKind != SourceKind.MANUAL) { "Manual Transactions have no messages to ingest" }
        val window = duplicateWindow.window()
        return db.withTransaction {
            if (dao.tombstoneCount(fact.sourceRef.value) > 0) return@withTransaction Outcome.Err(IngestError.TOMBSTONED)
            val added = dao.insertFact(fact.toEntity(formatId)) != IGNORED
            val inserted = reconcile(listOf(fact.sourceRef.value), window)
            val transactionId =
                checkNotNull(dao.factsByRefs(listOf(fact.sourceRef.value)).single().transactionId) { "A stored message always belongs to a Transaction" }
            Outcome.Ok(IngestResult(TransactionId(transactionId), created = transactionId in inserted, alreadyStored = !added))
        }
    }

    /** Everything reachable from [seeds] by window, forced merge or shared Transaction, and the forced-merge sets among it. */
    private class Component(val facts: Map<String, ParsedFactEntity>, val forced: Set<Set<SourceRef>>)

    private suspend fun loadComponent(seeds: Collection<String>, window: Duration): Component {
        val facts = LinkedHashMap<String, ParsedFactEntity>()
        val seenGroups = HashSet<Long>()
        var frontier = dao.factsByRefs(seeds.toList()).filter { facts.putIfAbsent(it.sourceRef, it) == null }
        val windowMillis = window.toMillis()
        while (frontier.isNotEmpty()) {
            val found = ArrayList<ParsedFactEntity>()
            for ((key, group) in frontier.groupBy { it.type to it.amountPaise }) {
                found += dao.factsNear(key.first, key.second, group.minOf { it.at } - windowMillis, group.maxOf { it.at } + windowMillis)
            }
            val newGroups = dao.groupsOf(frontier.map { it.sourceRef }).filter { seenGroups.add(it) }
            if (newGroups.isNotEmpty()) found += dao.factsInGroups(newGroups)
            val transactionIds = frontier.mapNotNull { it.transactionId }.distinct()
            if (transactionIds.isNotEmpty()) found += dao.factsOfTransactions(transactionIds)
            frontier = found.filter { facts.putIfAbsent(it.sourceRef, it) == null }
        }
        val forced = if (seenGroups.isEmpty()) {
            emptySet()
        } else {
            dao.forcedInGroups(seenGroups.toList()).groupBy({ it.groupId }, { SourceRef(it.sourceRef) }).values.map { it.toSet() }.toSet()
        }
        return Component(facts, forced)
    }

    /**
     * Regroups the neighbourhood of [seeds] and writes the difference. Returns the ids of Transactions created by it.
     * A Transaction keeps its category unless its Merchant changed and the User did not set the category (Requirement 31.6).
     */
    private suspend fun reconcile(seeds: Collection<String>, window: Duration): Set<Long> {
        val component = loadComponent(seeds, window)
        if (component.facts.isEmpty()) return emptySet()
        val storedRows = dao.txns(component.facts.values.mapNotNull { it.transactionId }.distinct()).associateBy { it.id }
        val stored = storedRows.values.map { row ->
            val refs = component.facts.values.filter { it.transactionId == row.id }.map { SourceRef(it.sourceRef) }.toSet()
            StoredTxn(row.id, editsOf(row), row.editedAt?.let(Instant::ofEpochMilli), refs)
        }
        val plan = TransactionReconciler.reconcile(component.facts.values.map { it.toDomain() }, component.forced, stored, window)

        val inserted = HashSet<Long>()
        for (planned in plan.planned) {
            val resolved = planned.resolved
            val merchantId = resolveMerchant(resolved.merchantOrVpa)
            val previous = planned.existingId?.let { storedRows.getValue(it) }
            val categoryUserSet = previous?.categoryUserSet ?: false
            val category = when {
                previous != null && (categoryUserSet || previous.merchantId == merchantId) -> previous.categoryId
                else -> categoryFor(merchantId)
            }
            val entity = TransactionEntity(
                id = previous?.id ?: 0,
                type = resolved.type.name,
                amountPaise = resolved.amount.value,
                at = resolved.at.toEpochMilli(),
                merchantId = merchantId,
                categoryId = category,
                source = planned.source.name,
                reference = resolved.reference,
                account4 = resolved.account4,
                balancePaise = resolved.balance?.value,
                isReversal = resolved.isReversal,
                editedFields = EditedFieldsMask.encode(resolved.editedFields),
                categoryUserSet = categoryUserSet,
                editedAt = planned.editedAt?.toEpochMilli(),
            )
            val id = if (previous == null) dao.insertTxn(entity).also { inserted += it } else previous.id.also { dao.updateTxn(entity) }
            dao.linkFacts(planned.cluster.sourceRefs.map { it.value }, id)
        }
        if (plan.removedIds.isNotEmpty()) dao.deleteTxns(plan.removedIds)
        return inserted
    }

    /** The User-edited values held in a stored row. */
    private suspend fun editsOf(row: TransactionEntity): UserEdits {
        val edited = EditedFieldsMask.decode(row.editedFields)
        return UserEdits(
            type = if (EditedField.TYPE in edited) TxnType.valueOf(row.type) else null,
            amount = if (EditedField.AMOUNT in edited) Paise(row.amountPaise) else null,
            at = if (EditedField.DATE in edited) Instant.ofEpochMilli(row.at) else null,
            // An edited Merchant that was cleared is the empty text, which still counts as an edit.
            merchant = if (EditedField.MERCHANT in edited) row.merchantId?.let { catalog.name(it) }.orEmpty() else null,
        )
    }

    // Merchants and categories (shared with the bill proposals, see MerchantResolver)

    private suspend fun resolveMerchant(nameOrVpa: String?): Long? = MerchantResolver(catalog).resolve(nameOrVpa)

    private suspend fun categoryFor(merchantId: Long?): Long = MerchantResolver(catalog).categoryFor(merchantId)

    // Manual add and edit

    override suspend fun addManual(input: ManualTransactionInput): Outcome<TransactionId, FieldErrors<EditedField>> {
        val errors = TransactionValidator.validateNew(input, time.today(), time.zone())
        if (!errors.isValid) return Outcome.Err(errors)
        return db.withTransaction {
            val merchantId = resolveMerchant(input.merchant)
            val entity = TransactionEntity(
                type = checkNotNull(input.type).name,
                amountPaise = checkNotNull(input.amount).value,
                at = checkNotNull(input.at).toEpochMilli(),
                merchantId = merchantId,
                categoryId = categoryFor(merchantId),
                source = TxnSource.MANUAL.name,
                reference = null,
                account4 = null,
                balancePaise = null,
                isReversal = false,
                // Everything on a manual Transaction was typed by the User, so no message may change it (Requirement 32.2).
                editedFields = EditedFieldsMask.encode(EditedField.entries.toSet()),
                categoryUserSet = false,
                editedAt = time.now().toEpochMilli(),
            )
            Outcome.Ok(TransactionId(dao.insertTxn(entity)))
        }
    }

    override suspend fun edit(id: TransactionId, edit: TransactionEdit): Outcome<Unit, EditFailure> {
        val errors = TransactionValidator.validateEdit(edit, time.today(), time.zone())
        if (!errors.isValid) return Outcome.Err(EditFailure.Invalid(errors))
        val window = duplicateWindow.window()
        return db.withTransaction {
            val row = dao.txn(id.value) ?: return@withTransaction Outcome.Err(EditFailure.NotFound)
            if (edit.isEmpty) return@withTransaction Outcome.Ok(Unit)
            val merchantId = if (edit.merchant != null) resolveMerchant(edit.merchant) else row.merchantId
            val edited = EditedFieldsMask.decode(row.editedFields) + UserEdits(edit.type, edit.amount, edit.at, edit.merchant).fields
            dao.updateTxn(
                row.copy(
                    type = edit.type?.name ?: row.type,
                    amountPaise = edit.amount?.value ?: row.amountPaise,
                    at = edit.at?.toEpochMilli() ?: row.at,
                    merchantId = merchantId,
                    categoryId = if (merchantId != row.merchantId && !row.categoryUserSet) categoryFor(merchantId) else row.categoryId,
                    editedFields = EditedFieldsMask.encode(edited),
                    editedAt = time.now().toEpochMilli(),
                ),
            )
            // A Transaction built from messages is rewritten from them plus the edits just stored.
            dao.refsOf(id.value).takeIf { it.isNotEmpty() }?.let { reconcile(it, window) }
            Outcome.Ok(Unit)
        }
    }

    // Merge and delete

    override suspend fun merge(first: TransactionId, second: TransactionId, selection: MergeSelection): Outcome<TransactionId, MergeError> {
        if (first == second) return Outcome.Err(MergeError.SAME_TRANSACTION)
        val window = duplicateWindow.window()
        return db.withTransaction {
            val a = get(first) ?: return@withTransaction Outcome.Err(MergeError.NOT_FOUND)
            val b = get(second) ?: return@withTransaction Outcome.Err(MergeError.NOT_FOUND)
            TransactionMerge.check(a.type, b.type)?.let { return@withTransaction Outcome.Err(it) }

            val survivor = if (first.value < second.value) a else b
            val victim = if (survivor === a) b else a
            val edits = TransactionMerge.mergedEdits(a, b, selection)
            val survivorRow = checkNotNull(dao.txn(survivor.id.value))
            val merchantId = if (edits.merchant != null) resolveMerchant(edits.merchant) else survivorRow.merchantId
            dao.updateTxn(
                survivorRow.copy(
                    amountPaise = edits.amount?.value ?: survivorRow.amountPaise,
                    at = edits.at?.toEpochMilli() ?: survivorRow.at,
                    merchantId = merchantId,
                    categoryId = if (merchantId != survivorRow.merchantId && !survivorRow.categoryUserSet) categoryFor(merchantId) else survivorRow.categoryId,
                    editedFields = EditedFieldsMask.encode(edits.fields),
                    editedAt = time.now().toEpochMilli(),
                ),
            )

            val victimRefs = victim.sourceRefs.map { it.value }
            if (victimRefs.isNotEmpty()) dao.linkFacts(victimRefs, survivor.id.value)
            dao.deleteTxns(listOf(victim.id.value))

            val allRefs = (survivor.sourceRefs + victim.sourceRefs).map { it.value }
            if (allRefs.isNotEmpty()) {
                // Hold every message of both together from now on, whatever arrives later (Requirement 32.4).
                dao.groupsOf(allRefs).takeIf { it.isNotEmpty() }?.let { dao.relabelGroups(it, survivor.id.value) }
                if (allRefs.size >= 2) dao.putForced(allRefs.map { ForcedMergeEntity(it, survivor.id.value) })
                reconcile(allRefs, window)
            }
            Outcome.Ok(survivor.id)
        }
    }

    override suspend fun delete(id: TransactionId) {
        db.withTransaction {
            if (dao.txn(id.value) == null) return@withTransaction
            val refs = dao.refsOf(id.value)
            if (refs.isNotEmpty()) {
                val now = time.now().toEpochMilli()
                // The tombstones stop the same messages from creating the Transaction again (Requirement 32.7).
                dao.putTombstones(refs.map { TombstoneEntity(it, reason = TXN_DELETED, at = now) })
                dao.deleteFacts(refs)
                dao.deleteForced(refs)
            }
            dao.deleteTxns(listOf(id.value))
        }
    }

    // Category changes

    override suspend fun changeCategory(id: TransactionId, category: CategoryId): Outcome<CategoryOffer?, CategoryChangeError> = db.withTransaction {
        val row = dao.txn(id.value) ?: return@withTransaction Outcome.Err(CategoryChangeError.NOT_FOUND)
        if (catalog.categoryCount(category.value) == 0) return@withTransaction Outcome.Err(CategoryChangeError.UNKNOWN_CATEGORY)
        dao.updateTxn(row.copy(categoryId = category.value, categoryUserSet = true))
        val merchant = row.merchantId ?: return@withTransaction Outcome.Ok(null)
        catalog.insertCorrection(CategoryCorrectionEntity(merchantId = merchant, categoryId = category.value, at = time.now().toEpochMilli()))
        val count = dao.countEarlierToChange(merchant, category.value, row.at, row.id)
        Outcome.Ok(if (count > 0) CategoryOffer(MerchantId(merchant), category, id, Instant.ofEpochMilli(row.at), count) else null)
    }

    override suspend fun applyCategoryToEarlier(offer: CategoryOffer): Int =
        dao.applyCategoryToEarlier(offer.merchant.value, offer.category.value, offer.anchorAt.toEpochMilli(), offer.anchor.value)

    private companion object {
        const val IGNORED = -1L
        const val TXN_DELETED = "TXN_DELETED"
    }
}

internal fun ParsedFact.toEntity(formatId: String) = ParsedFactEntity(
    sourceRef = sourceRef.value,
    origin = origin.value,
    sourceKind = sourceKind.name,
    type = type.name,
    amountPaise = amount.value,
    at = at.toEpochMilli(),
    receivedAt = receivedAt.toEpochMilli(),
    reference = reference,
    account4 = account4,
    merchantOrVpa = merchantOrVpa,
    balancePaise = balance?.value,
    isReversal = isReversal,
    formatId = formatId,
    transactionId = null,
)

internal fun ParsedFactEntity.toDomain() = ParsedFact(
    sourceRef = SourceRef(sourceRef),
    origin = MessageOrigin(origin),
    sourceKind = SourceKind.valueOf(sourceKind),
    type = TxnType.valueOf(type),
    amount = Paise(amountPaise),
    at = Instant.ofEpochMilli(at),
    receivedAt = Instant.ofEpochMilli(receivedAt),
    reference = reference,
    account4 = account4,
    merchantOrVpa = merchantOrVpa,
    balance = balancePaise?.let(::Paise),
    isReversal = isReversal,
)

internal fun TransactionRow.toDomain(refs: List<SourceRef>) = Transaction(
    id = TransactionId(txn.id),
    type = TxnType.valueOf(txn.type),
    amount = Paise(txn.amountPaise),
    at = Instant.ofEpochMilli(txn.at),
    merchantId = txn.merchantId?.let(::MerchantId),
    merchantName = merchantName,
    category = CategoryId(txn.categoryId),
    source = TxnSource.valueOf(txn.source),
    reference = txn.reference,
    account4 = txn.account4,
    balance = txn.balancePaise?.let(::Paise),
    isReversal = txn.isReversal,
    editedFields = EditedFieldsMask.decode(txn.editedFields),
    categoryUserSet = txn.categoryUserSet,
    sourceRefs = refs.sortedBy { it.value },
)

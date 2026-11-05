package app.assistant.domain.engine.transactions

import app.assistant.domain.engine.dedupe.Cluster
import app.assistant.domain.engine.dedupe.Deduplicator
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.dedupe.ResolvedTransaction
import app.assistant.domain.engine.dedupe.UserEdits
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import java.time.Duration
import java.time.Instant

/** A stored Transaction as the reconciler sees it: its id, the values of the fields the User edited, and its Source_Refs. */
data class StoredTxn(val id: Long, val edits: UserEdits, val editedAt: Instant?, val refs: Set<SourceRef>)

/** One Transaction the store should hold after reconciling. [existingId] is null for a new one. */
data class PlannedTxn(val existingId: Long?, val cluster: Cluster, val edits: UserEdits, val editedAt: Instant?, val resolved: ResolvedTransaction) {
    /** SMS, APP or MIXED from the messages in the cluster. */
    val source: TxnSource
        get() {
            val kinds = cluster.facts.map { it.sourceKind }.toSet()
            return when {
                SourceKind.SMS in kinds && SourceKind.APP in kinds -> TxnSource.MIXED
                SourceKind.SMS in kinds -> TxnSource.SMS
                SourceKind.APP in kinds -> TxnSource.APP
                else -> TxnSource.MANUAL
            }
        }
}

/** What to write: the Transactions that should exist, and the stored ones that collapsed into another and must go. */
data class ReconcilePlan(val planned: List<PlannedTxn>, val removedIds: List<Long>)

/**
 * Maps the payments the Deduplicator finds onto the Transactions already stored (design: dedupe persistence).
 * Pure. The Deduplicator decides which messages belong together from the *set* of facts alone; this decides
 * which stored row represents each group, so a Transaction keeps its id for as long as it can.
 *
 * - A group that contains messages of one stored Transaction keeps that id.
 * - When several stored Transactions end up in one group, the lowest id survives and the others are removed
 *   (Requirement 29.6 keeps every User edit; where two edited the same field, the newest edit wins).
 * - When one stored Transaction's messages are split across groups, the group holding most of them (the
 *   earliest on a tie) keeps the id and the others become new Transactions.
 */
object TransactionReconciler {

    fun reconcile(facts: Collection<ParsedFact>, forcedMerges: Set<Set<SourceRef>>, stored: List<StoredTxn>, window: Duration): ReconcilePlan {
        val clusters = Deduplicator.cluster(facts, window, forcedMerges)
        val clusterOf = HashMap<SourceRef, Int>()
        clusters.forEachIndexed { index, cluster -> cluster.sourceRefs.forEach { clusterOf[it] = index } }

        val removed = ArrayList<Long>()
        val ownersOf = HashMap<Int, MutableList<StoredTxn>>()
        for (txn in stored.sortedBy { it.id }) {
            val home = homeCluster(txn, clusterOf)
            if (home == null) removed += txn.id else ownersOf.getOrPut(home) { ArrayList() } += txn
        }

        val planned = clusters.mapIndexed { index, cluster ->
            val owners = ownersOf[index].orEmpty()
            removed += owners.drop(1).map { it.id }
            val edits = mergeEdits(owners)
            PlannedTxn(
                existingId = owners.firstOrNull()?.id,
                cluster = cluster,
                edits = edits,
                editedAt = owners.mapNotNull { it.editedAt }.maxOrNull(),
                resolved = Deduplicator.resolve(cluster, edits),
            )
        }
        return ReconcilePlan(planned, removed.sorted())
    }

    /** The cluster holding most of the Transaction's messages, the lowest index on a tie; null when none are left. */
    private fun homeCluster(txn: StoredTxn, clusterOf: Map<SourceRef, Int>): Int? = txn.refs.mapNotNull { clusterOf[it] }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key

    /** Per field, the value of the Transaction edited last; the lowest id on a tie. Fields nobody edited stay unset. */
    private fun mergeEdits(owners: List<StoredTxn>): UserEdits {
        fun <T : Any> newest(value: (UserEdits) -> T?): T? = owners.filter { value(it.edits) != null }
            .maxWithOrNull(compareBy<StoredTxn> { it.editedAt ?: Instant.MIN }.thenByDescending { it.id })
            ?.let { value(it.edits) }
        return UserEdits(type = newest { it.type }, amount = newest { it.amount }, at = newest { it.at }, merchant = newest { it.merchant })
    }
}

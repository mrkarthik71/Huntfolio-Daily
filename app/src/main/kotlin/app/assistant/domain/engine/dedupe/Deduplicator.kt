package app.assistant.domain.engine.dedupe

import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Decides which Money_Messages describe the same payment (Requirement 29, design: Deduplicator).
 *
 * Pure. [cluster] depends only on the *set* of facts: they are de-duplicated by Source_Ref
 * (idempotence, Req 29.3) and sorted canonically before grouping, so arrival order cannot change
 * the result (confluence, Req 29.4). A growing cluster stands in for the stored Transaction of
 * Requirement 29.1: its time, reference, account digits and origins are those it resolves to.
 * User edits never influence grouping, only [resolve].
 */
object Deduplicator {

    /** Facts in a fixed total order, so equal inputs always group the same way. */
    internal val CANONICAL: Comparator<ParsedFact> = compareBy<ParsedFact>({ it.at }, { it.receivedAt }, { it.sourceRef.value })
        .thenBy { it.sourceKind }
        .thenBy { it.type }
        .thenBy { it.amount.value }
        .thenBy { it.origin.value }
        .thenBy { it.reference.orEmpty() }
        .thenBy { it.account4.orEmpty() }
        .thenBy { it.merchantOrVpa.orEmpty() }
        .thenBy { it.balance?.value ?: -1L }
        .thenBy { it.isReversal }

    /** Which message wins a field both hold: SMS over app, then the earlier Received_Time, then the lower Source_Ref (Req 29.6). */
    internal val PRECEDENCE: Comparator<ParsedFact> =
        compareBy<ParsedFact>({ if (it.sourceKind == SourceKind.SMS) 0 else 1 }, { it.receivedAt }, { it.sourceRef.value })

    /**
     * Partitions [facts] into payments. A fact joins an existing cluster when it has the same type
     * and amount, is no more than [window] from the cluster's time, agrees on reference and
     * account digits where both have them, and comes from a new Message_Origin, or from a known
     * one only when the reference is the same (Req 29.1, 29.5). When several clusters qualify it
     * joins the one nearest in time, then the earlier, then the one started first (Req 29.7); otherwise it starts a new one
     * (Req 29.2). Manual facts never join or attract anything (Req 32.3). Each set in
     * [forcedMerges] is held together in one cluster (Req 32.4).
     */
    fun cluster(facts: Collection<ParsedFact>, window: Duration, forcedMerges: Set<Set<SourceRef>>): List<Cluster> {
        val groups = ArrayList<Group>()
        val byTypeAndAmount = HashMap<Pair<TxnType, Long>, MutableList<Group>>()
        for (fact in facts.sortedWith(CANONICAL).distinctBy { it.sourceRef }) {
            val key = fact.type to fact.amount.value
            val target = if (fact.sourceKind == SourceKind.MANUAL) null else nearest(fact, byTypeAndAmount[key].orEmpty(), window)
            if (target != null) {
                target.add(fact)
            } else {
                val group = Group(fact, order = groups.size)
                groups += group
                if (fact.sourceKind != SourceKind.MANUAL) byTypeAndAmount.getOrPut(key) { ArrayList() } += group
            }
        }
        return mergeForced(groups, forcedMerges)
    }

    /** The cluster read as one Transaction: User edits, then the winning message per field (Req 29.6, 32.2). */
    fun resolve(cluster: Cluster, userEdits: UserEdits?): ResolvedTransaction {
        val byPrecedence = cluster.facts.sortedWith(PRECEDENCE)
        val top = byPrecedence.first()
        val edits = userEdits ?: UserEdits()
        return ResolvedTransaction(
            type = edits.type ?: top.type,
            amount = edits.amount ?: top.amount,
            at = edits.at ?: top.at,
            merchantOrVpa = edits.merchant ?: byPrecedence.firstNotNullOfOrNull { it.merchantOrVpa?.takeUnless(String::isBlank) },
            reference = byPrecedence.firstNotNullOfOrNull { it.reference?.takeUnless(String::isBlank) },
            account4 = byPrecedence.firstNotNullOfOrNull { it.account4?.takeUnless(String::isBlank) },
            balance = byPrecedence.firstNotNullOfOrNull { it.balance },
            isReversal = cluster.facts.any { it.isReversal },
            sourceRefs = cluster.sourceRefs.sortedBy { it.value },
            editedFields = edits.fields,
        )
    }

    private fun nearest(fact: ParsedFact, candidates: List<Group>, window: Duration): Group? = candidates
        .filter { it.accepts(fact, window) }
        .minWithOrNull(
            compareBy<Group>({ Duration.between(it.at, fact.at).abs() }, { it.at }, { it.order }),
        )

    private fun mergeForced(groups: List<Group>, forcedMerges: Set<Set<SourceRef>>): List<Cluster> {
        val groupOf = HashMap<SourceRef, Int>()
        groups.forEachIndexed { index, group -> group.facts.forEach { groupOf[it.sourceRef] = index } }
        val parent = IntArray(groups.size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            return root
        }
        // A manual fact is always its own group of one and never joins a forced merge either (Req 32.3).
        for (set in forcedMerges) {
            val present = set.mapNotNull { groupOf[it] }.filterNot { groups[it].facts.any { f -> f.sourceKind == SourceKind.MANUAL } }
            present.drop(1).forEach { parent[find(it)] = find(present.first()) }
        }
        return groups.indices.groupBy { find(it) }.values
            .map { ids -> Cluster(ids.flatMap { groups[it].facts }.sortedWith(CANONICAL)) }
            .sortedWith { a, b -> CANONICAL.compare(a.facts.first(), b.facts.first()) }
    }

    /** Reference numbers are compared ignoring spaces and letter case (Req 29.1). */
    internal fun normalisedReference(reference: String?): String? =
        reference?.filterNot(Char::isWhitespace)?.lowercase(Locale.ROOT)?.takeUnless(String::isEmpty)

    internal fun normalisedOrigin(origin: MessageOrigin): String = origin.value.trim().uppercase(Locale.ROOT)

    /** A cluster being built: what it resolves to so far, kept current as facts join. */
    private class Group(first: ParsedFact, val order: Int) {
        val facts = ArrayList<ParsedFact>().apply { add(first) }
        var at: Instant = first.at
            private set
        private var reference: String? = null
        private var account4: String? = null
        private val origins = HashSet<String>()

        init {
            refresh()
        }

        fun add(fact: ParsedFact) {
            facts += fact
            refresh()
        }

        fun accepts(fact: ParsedFact, window: Duration): Boolean {
            if (Duration.between(at, fact.at).abs() > window) return false
            val factReference = normalisedReference(fact.reference)
            val sameReference = reference != null && factReference != null && reference == factReference
            if (reference != null && factReference != null && !sameReference) return false
            val factAccount = fact.account4?.takeUnless(String::isBlank)
            if (account4 != null && factAccount != null && account4 != factAccount) return false
            return normalisedOrigin(fact.origin) !in origins || sameReference
        }

        private fun refresh() {
            val byPrecedence = facts.sortedWith(PRECEDENCE)
            at = byPrecedence.first().at
            reference = byPrecedence.firstNotNullOfOrNull { normalisedReference(it.reference) }
            account4 = byPrecedence.firstNotNullOfOrNull { it.account4?.takeUnless(String::isBlank) }
            origins.clear()
            facts.mapTo(origins) { normalisedOrigin(it.origin) }
        }
    }
}

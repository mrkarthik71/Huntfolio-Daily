package app.assistant.domain.engine.dedupe

import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import java.time.Duration
import java.time.Instant

/** Shorthand for building facts in tests. Source_Refs are unique by [id]. */
fun fact(
    id: Int,
    kind: SourceKind = SourceKind.SMS,
    type: TxnType = TxnType.DEBIT,
    amount: Long = 10_000,
    atSecond: Long = 0,
    receivedAfter: Long = 5,
    origin: String = "BANKA",
    reference: String? = null,
    account4: String? = null,
    merchant: String? = null,
    balance: Long? = null,
    reversal: Boolean = false,
): ParsedFact = ParsedFact(
    sourceRef = SourceRef("${if (kind == SourceKind.APP) "ntf" else "sms"}:v1:ID${id.toString().padStart(4, '0')}"),
    origin = MessageOrigin(origin),
    sourceKind = kind,
    type = type,
    amount = Paise(amount),
    at = BASE.plusSeconds(atSecond),
    receivedAt = BASE.plusSeconds(atSecond + receivedAfter),
    reference = reference,
    account4 = account4,
    merchantOrVpa = merchant,
    balance = balance?.let(::Paise),
    isReversal = reversal,
)

val BASE: Instant = Instant.parse("2025-01-05T10:00:00Z")

/**
 * The matching and resolution rules of Requirements 29 and 32.3 written out plainly and
 * independently of [Deduplicator], for the property tests to compare against (design: "Compares
 * with a reference matcher in the test source set"). It favours being obviously right over being
 * fast: every attribute of a cluster is recomputed from its facts each time it is needed.
 */
object ReferenceDedupe {
    private fun smsFirst(a: ParsedFact, b: ParsedFact): Int {
        val rank = (if (a.sourceKind == SourceKind.SMS) 0 else 1) - (if (b.sourceKind == SourceKind.SMS) 0 else 1)
        if (rank != 0) return rank
        val received = a.receivedAt.compareTo(b.receivedAt)
        return if (received != 0) received else a.sourceRef.value.compareTo(b.sourceRef.value)
    }

    private fun best(facts: List<ParsedFact>): List<ParsedFact> = facts.sortedWith(::smsFirst)

    private fun ref(text: String?): String? = text?.replace(" ", "")?.replace("\t", "")?.lowercase()?.ifEmpty { null }

    fun time(cluster: List<ParsedFact>): Instant = best(cluster).first().at

    private fun reference(cluster: List<ParsedFact>): String? = best(cluster).map { ref(it.reference) }.firstOrNull { it != null }

    private fun account(cluster: List<ParsedFact>): String? = best(cluster).map { it.account4 }.firstOrNull { !it.isNullOrBlank() }

    private fun origins(cluster: List<ParsedFact>): Set<String> = cluster.map { it.origin.value.trim().uppercase() }.toSet()

    /** Requirement 29.1 and 29.5: may [f] join this existing Transaction? */
    fun accepts(f: ParsedFact, cluster: List<ParsedFact>, window: Duration): Boolean {
        if (f.sourceKind == SourceKind.MANUAL || cluster.any { it.sourceKind == SourceKind.MANUAL }) return false
        val sameTypeAndAmount = f.type == cluster.first().type && f.amount == cluster.first().amount
        val closeEnough = Duration.between(time(cluster), f.at).abs() <= window
        val clusterRef = reference(cluster)
        val factRef = ref(f.reference)
        val referenceConflict = clusterRef != null && factRef != null && clusterRef != factRef
        val clusterAccount = account(cluster)
        val factAccount = f.account4?.takeIf { it.isNotBlank() }
        val accountConflict = clusterAccount != null && factAccount != null && clusterAccount != factAccount
        val sameRef = clusterRef != null && clusterRef == factRef
        val originFree = f.origin.value.trim().uppercase() !in origins(cluster) || sameRef
        return sameTypeAndAmount && closeEnough && !referenceConflict && !accountConflict && originFree
    }

    /** Requirement 29.7: the index of the existing cluster [f] links to, or null for a new Transaction. */
    fun findMatch(f: ParsedFact, clusters: List<List<ParsedFact>>, window: Duration): Int? = clusters.indices
        .filter { accepts(f, clusters[it], window) }
        .minWithOrNull(compareBy({ Duration.between(time(clusters[it]), f.at).abs() }, { time(clusters[it]) }, { it }))

    /** Facts must have distinct Source_Refs. Returns the partition as sets of Source_Ref values. */
    fun partition(facts: List<ParsedFact>, window: Duration): Set<Set<String>> {
        val ordered = facts.sortedWith(compareBy({ it.at }, { it.receivedAt }, { it.sourceRef.value }))
        val clusters = ArrayList<MutableList<ParsedFact>>()
        for (f in ordered) {
            val index = findMatch(f, clusters, window)
            if (index == null) clusters += mutableListOf(f) else clusters[index] += f
        }
        return clusters.map { c -> c.map { it.sourceRef.value }.toSet() }.toSet()
    }

    /** Requirement 29.6 and 32.2 for one cluster. */
    fun resolve(cluster: List<ParsedFact>, edits: UserEdits?): ResolvedTransaction {
        val ordered = best(cluster)
        return ResolvedTransaction(
            type = edits?.type ?: ordered.first().type,
            amount = edits?.amount ?: ordered.first().amount,
            at = edits?.at ?: ordered.first().at,
            merchantOrVpa = edits?.merchant ?: ordered.map { it.merchantOrVpa }.firstOrNull { !it.isNullOrBlank() },
            reference = ordered.map { it.reference }.firstOrNull { !it.isNullOrBlank() },
            account4 = ordered.map { it.account4 }.firstOrNull { !it.isNullOrBlank() },
            balance = ordered.map { it.balance }.firstOrNull { it != null },
            isReversal = cluster.any { it.isReversal },
            sourceRefs = cluster.map { it.sourceRef }.sortedBy { it.value },
            editedFields = edits?.fields.orEmpty(),
        )
    }
}

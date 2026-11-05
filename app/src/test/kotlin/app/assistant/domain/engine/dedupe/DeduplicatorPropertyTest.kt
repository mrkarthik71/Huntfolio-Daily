// Feature: Phase 3 Money — Property 39: Dedupe matching follows the rule.
package app.assistant.domain.engine.dedupe

import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.TxnType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private val origins = listOf("BANKA", "BANKB", "com.example.upiapp")
private val references = listOf(null, "ABC123", "abc 123", "XYZ999")
private val accounts = listOf(null, "1234", "4321")

/** Facts likely to collide with each other on type and amount, so matching is actually exercised. */
private val arbFact: io.kotest.property.Arb<ParsedFact> = arbitrary {
    fact(
        id = Arb.int(0..1_000_000).bind(),
        kind = Arb.element(SourceKind.SMS, SourceKind.APP, SourceKind.MANUAL).bind(),
        type = Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind(),
        amount = Arb.element(10_000L, 20_000L).bind(),
        atSecond = Arb.long(-600L..600L).bind(),
        receivedAfter = Arb.long(0L..120L).bind(),
        origin = Arb.element(origins).bind(),
        reference = Arb.element(references).bind(),
        account4 = Arb.element(accounts).bind(),
        merchant = Arb.element(null, "Shop One", "Shop Two").bind(),
        reversal = Arb.boolean().bind(),
    )
}

private fun distinctBySourceRef(facts: List<ParsedFact>): List<ParsedFact> = facts.distinctBy { it.sourceRef }

class DeduplicatorPropertyTest {

    @Test
    fun `Property 39 - clustering matches the reference matcher, nearest then earlier, never manual`() = runTest {
        checkAll(Arb.list(arbFact, 0..10), Arb.long(30L..600L)) { raw, windowSeconds ->
            val facts = distinctBySourceRef(raw)
            val window = Duration.ofSeconds(windowSeconds)
            val clusters = Deduplicator.cluster(facts, window, emptySet())

            val actual = clusters.map { it.sourceRefs.map { ref -> ref.value }.toSet() }.toSet()
            val expected = ReferenceDedupe.partition(facts, window)
            assertEquals(expected, actual, "facts: $facts, window: $window")

            // Manual facts are always singleton clusters of exactly one fact.
            clusters.filter { it.facts.any { f -> f.sourceKind == SourceKind.MANUAL } }.forEach {
                assertEquals(1, it.facts.size, "a manual fact must never share a cluster")
            }
        }
    }

    @Test
    fun `an SMS and an app notification for the same payment merge whichever arrives first`() {
        val sms = fact(1, kind = SourceKind.SMS, reference = "ABC123", account4 = "1234", merchant = "Cafe Blue")
        val app = fact(2, kind = SourceKind.APP, origin = "com.example.upiapp", reference = "ABC123", account4 = "1234", merchant = "shop@upi", atSecond = 30)

        val smsFirst = Deduplicator.cluster(listOf(sms, app), Duration.ofMinutes(10), emptySet())
        val appFirst = Deduplicator.cluster(listOf(app, sms), Duration.ofMinutes(10), emptySet())
        assertEquals(1, smsFirst.size)
        assertEquals(smsFirst.single().sourceRefs.toSet(), appFirst.single().sourceRefs.toSet())
    }

    @Test
    fun `a different reference, account, type, amount or a gap wider than the window makes a new Transaction`() {
        val base = fact(1, reference = "ABC123", account4 = "1234")
        fun clustersWith(other: ParsedFact) = Deduplicator.cluster(listOf(base, other), Duration.ofMinutes(10), emptySet())

        assertEquals(2, clustersWith(fact(2, reference = "XYZ999", account4 = "1234")).size, "different reference")
        assertEquals(2, clustersWith(fact(3, reference = "ABC123", account4 = "4321")).size, "different account")
        assertEquals(2, clustersWith(fact(4, type = TxnType.CREDIT)).size, "different type")
        assertEquals(2, clustersWith(fact(5, amount = 20_000)).size, "different amount")
        assertEquals(2, clustersWith(fact(6, reference = "ABC123", account4 = "1234", atSecond = 700)).size, "beyond the window")
    }

    @Test
    fun `two Source_Refs from the same origin need the same type, amount and reference to join`() {
        val first = fact(1, origin = "BANKA", reference = "ABC123")
        val sameOriginSameRef = fact(2, origin = "BANKA", reference = "ABC123", atSecond = 5)
        val sameOriginNoRef = fact(3, origin = "BANKA", atSecond = 5)
        val differentOrigin = fact(4, origin = "BANKB", atSecond = 5)

        assertEquals(1, Deduplicator.cluster(listOf(first, sameOriginSameRef), Duration.ofMinutes(10), emptySet()).size)
        assertEquals(2, Deduplicator.cluster(listOf(first, sameOriginNoRef), Duration.ofMinutes(10), emptySet()).size, "same origin, no shared reference")
        assertEquals(1, Deduplicator.cluster(listOf(first, differentOrigin), Duration.ofMinutes(10), emptySet()).size, "a new origin may join freely")
    }

    @Test
    fun `when several Transactions qualify, the nearest in time wins, then the earlier`() {
        val early = fact(1, reference = "ABC123", account4 = "1234", atSecond = 0)
        val near = fact(2, reference = "ABC123", account4 = "1234", atSecond = 20, origin = "BANKB")
        val far = fact(3, reference = "ABC123", account4 = "1234", atSecond = 100, origin = "com.example.upiapp")
        val newFact = fact(4, reference = "ABC123", account4 = "1234", atSecond = 30, origin = "VM-OTHER-S")

        val clusters = Deduplicator.cluster(listOf(early, near, far, newFact), Duration.ofMinutes(10), emptySet())
        val withNew = clusters.first { newFact.sourceRef in it.sourceRefs }
        assertTrue(near.sourceRef in withNew.sourceRefs, "nearest in time should win over a farther, equally eligible Transaction")
    }

    @Test
    fun `ingesting the same fact twice is idempotent`() {
        val a = fact(1, reference = "ABC123")
        val clusters = Deduplicator.cluster(listOf(a, a.copy()), Duration.ofMinutes(10), emptySet())
        assertEquals(1, clusters.size)
        assertEquals(1, clusters.single().facts.size, "duplicate Source_Refs collapse to one fact")
    }

    @Test
    fun `clustering gives the same result in any arrival order (confluence)`() {
        val facts = listOf(
            fact(1, reference = "ABC123", account4 = "1234"),
            fact(2, reference = "ABC123", account4 = "1234", atSecond = 10, origin = "BANKB"),
            fact(3, type = TxnType.CREDIT, amount = 5_000, atSecond = -200),
            fact(4, kind = SourceKind.MANUAL, atSecond = 0),
        )
        val canonical = Deduplicator.cluster(facts, Duration.ofMinutes(10), emptySet()).map { it.sourceRefs.toSet() }.toSet()
        repeat(20) {
            val shuffled = facts.shuffled()
            val result = Deduplicator.cluster(shuffled, Duration.ofMinutes(10), emptySet()).map { it.sourceRefs.toSet() }.toSet()
            assertEquals(canonical, result)
        }
    }

    @Test
    fun `forced merges from a manual merge hold their Transactions together`() {
        val a = fact(1, atSecond = 0)
        val b = fact(2, amount = 999_999, atSecond = 10_000) // would never match on its own
        val merged = Deduplicator.cluster(listOf(a, b), Duration.ofMinutes(1), setOf(setOf(a.sourceRef, b.sourceRef)))
        assertEquals(1, merged.size)
        assertEquals(setOf(a.sourceRef, b.sourceRef), merged.single().sourceRefs.toSet())
    }

    @Test
    fun `a forced merge never pulls in a manual fact`() {
        val manual = fact(1, kind = SourceKind.MANUAL)
        val sms = fact(2)
        val result = Deduplicator.cluster(listOf(manual, sms), Duration.ofMinutes(10), setOf(setOf(manual.sourceRef, sms.sourceRef)))
        assertEquals(2, result.size)
    }

    @Test
    fun `an empty fact collection clusters to nothing`() {
        assertTrue(Deduplicator.cluster(emptyList(), Duration.ofMinutes(10), emptySet()).isEmpty())
    }

    @Test
    fun `a reference comparison ignores spaces and letter case, and blank values never conflict`() {
        val a = fact(1, reference = "ABC123")
        val b = fact(2, reference = "a b c 1 2 3", atSecond = 5)
        val c = fact(3, reference = "", atSecond = 5, origin = "BANKB")
        assertEquals(1, Deduplicator.cluster(listOf(a, b), Duration.ofMinutes(10), emptySet()).size, "same reference, different case and spacing")
        assertEquals(1, Deduplicator.cluster(listOf(a, c), Duration.ofMinutes(10), emptySet()).size, "a blank reference never conflicts")
    }

    @Test
    fun `an invalid cluster of no facts is rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { Cluster(emptyList()) }
    }
}

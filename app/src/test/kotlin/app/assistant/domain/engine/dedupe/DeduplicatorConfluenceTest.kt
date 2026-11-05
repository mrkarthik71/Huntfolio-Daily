// Feature: Phase 3 Money — Property 39 (incremental linking), 29.3 idempotence, 29.4 confluence, Property 42.
package app.assistant.domain.engine.dedupe

import app.assistant.domain.model.Paise
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
import java.time.Instant
import kotlin.random.Random

private val origins = listOf("BANKA", "banka ", "BANKB", "com.example.upiapp")

private fun arbFactAt(atSeconds: LongRange): Arb<ParsedFact> = arbitrary {
    fact(
        id = Arb.int(0..1_000_000).bind(),
        kind = Arb.element(SourceKind.SMS, SourceKind.APP).bind(),
        type = Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind(),
        amount = Arb.element(10_000L, 20_000L).bind(),
        atSecond = Arb.long(atSeconds).bind(),
        receivedAfter = Arb.long(0L..120L).bind(),
        origin = Arb.element(origins).bind(),
        reference = Arb.element(null, "ABC123", "abc 123", "XYZ999").bind(),
        account4 = Arb.element(null, "1234", "4321").bind(),
        merchant = Arb.element(null, "Shop One", "Shop Two").bind(),
        balance = Arb.element(null, 100L, 200L).bind(),
        reversal = Arb.boolean().bind(),
    )
}

private fun partition(clusters: List<Cluster>) = clusters.map { c -> c.sourceRefs.map { it.value }.toSet() }.toSet()

class DeduplicatorConfluenceTest {

    @Test
    fun `Property 39 - a new message links to exactly the Transaction the reference matcher picks`() = runTest {
        checkAll(Arb.list(arbFactAt(0L..360L), 0..9), arbFactAt(361L..420L), Arb.long(30L..600L)) { raw, newFact, windowSeconds ->
            val stored = raw.distinctBy { it.sourceRef }.filter { it.sourceRef != newFact.sourceRef }
            val window = Duration.ofSeconds(windowSeconds)
            val before = Deduplicator.cluster(stored, window, emptySet())
            val after = Deduplicator.cluster(stored + newFact, window, emptySet())

            // The new fact is the latest in canonical order, so every earlier Transaction is unchanged by it.
            val expectedIndex = ReferenceDedupe.findMatch(newFact, before.map { it.facts }, window)
            val expectedAfter = before.mapIndexed { i, c ->
                c.sourceRefs.map { it.value }.toSet() +
                    (if (i == expectedIndex) setOf(newFact.sourceRef.value) else emptySet())
            }
            val expected = (expectedAfter + (if (expectedIndex == null) listOf(setOf(newFact.sourceRef.value)) else emptyList())).toSet()
            assertEquals(expected, partition(after), "new fact $newFact, stored $stored")
        }
    }

    @Test
    fun `29_3 - ingesting every message twice gives the same Transactions as ingesting it once`() = runTest {
        checkAll(Arb.list(arbFactAt(-300L..300L), 0..10), Arb.long(30L..600L)) { raw, windowSeconds ->
            val facts = raw.distinctBy { it.sourceRef }
            val window = Duration.ofSeconds(windowSeconds)
            assertEquals(
                Deduplicator.cluster(facts, window, emptySet()),
                Deduplicator.cluster(facts + facts, window, emptySet()),
            )
        }
    }

    @Test
    fun `29_4 - the stored Transactions are the same in any arrival order, with or without forced merges`() = runTest {
        checkAll(Arb.list(arbFactAt(-300L..300L), 0..10), Arb.long(30L..600L), Arb.int(0..100_000)) { raw, windowSeconds, seed ->
            val facts = raw.distinctBy { it.sourceRef }
            val window = Duration.ofSeconds(windowSeconds)
            val random = Random(seed)
            val forced = if (facts.size >= 2) setOf(facts.shuffled(random).take(2).map { it.sourceRef }.toSet()) else emptySet()

            val reference = Deduplicator.cluster(facts, window, forced)
            val shuffled = Deduplicator.cluster(facts.shuffled(random), window, forced)
            assertEquals(reference, shuffled)
            forced.forEach { set -> assertTrue(reference.any { c -> c.sourceRefs.containsAll(set) }, "forced set $set must share a cluster") }
        }
    }

    @Test
    fun `Property 42 - resolution keeps User edits, then SMS over app, then the earlier Received_Time, and fills gaps`() = runTest {
        val arbEdits = arbitrary {
            UserEdits(
                type = if (Arb.boolean().bind()) Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind() else null,
                amount = if (Arb.boolean().bind()) Paise(Arb.long(1L..500_000L).bind()) else null,
                at = if (Arb.boolean().bind()) Instant.ofEpochSecond(Arb.long(0L..2_000_000_000L).bind()) else null,
                merchant = if (Arb.boolean().bind()) Arb.element("Edited Cafe", "Another").bind() else null,
            )
        }
        checkAll(Arb.list(arbFactAt(-60L..60L), 1..6), arbEdits) { raw, edits ->
            val facts = raw.distinctBy { it.sourceRef }
            val cluster = Cluster(facts.sortedWith(Deduplicator.CANONICAL))
            val actual = Deduplicator.resolve(cluster, edits)
            assertEquals(ReferenceDedupe.resolve(facts, edits), actual)

            // User-edited fields keep the User's value no matter what is linked later (Req 32.2).
            edits.type?.let { assertEquals(it, actual.type) }
            edits.amount?.let { assertEquals(it, actual.amount) }
            edits.at?.let { assertEquals(it, actual.at) }
            edits.merchant?.let { assertEquals(it, actual.merchantOrVpa) }
            assertEquals(edits.fields, actual.editedFields)
            assertEquals(facts.any { it.isReversal }, actual.isReversal)
        }
    }

    @Test
    fun `resolution examples - SMS beats app, earlier receipt beats later, a blank is filled from the other message`() {
        val sms = fact(1, kind = SourceKind.SMS, merchant = "Cafe Blue", reference = null, balance = null)
        val app = fact(2, kind = SourceKind.APP, origin = "com.example.upiapp", merchant = "shop@upi", reference = "UPI12345", balance = 500, receivedAfter = 1)
        val resolved = Deduplicator.resolve(Cluster(listOf(sms, app)), null)

        assertEquals("Cafe Blue", resolved.merchantOrVpa, "SMS value wins a field both hold")
        assertEquals("UPI12345", resolved.reference, "a field the SMS lacks is filled from the app")
        assertEquals(Paise(500), resolved.balance)
        assertEquals(setOf(sms.sourceRef, app.sourceRef), resolved.sourceRefs.toSet())

        val earlier = fact(3, kind = SourceKind.SMS, merchant = "Earlier", receivedAfter = 5)
        val later = fact(4, kind = SourceKind.SMS, merchant = "Later", receivedAfter = 50)
        assertEquals("Earlier", Deduplicator.resolve(Cluster(listOf(later, earlier)), null).merchantOrVpa, "between two SMS the earlier receipt wins")

        val edited = Deduplicator.resolve(Cluster(listOf(sms, app)), UserEdits(merchant = "My Cafe"))
        assertEquals("My Cafe", edited.merchantOrVpa)
        assertEquals(setOf(EditedField.MERCHANT), edited.editedFields)

        assertTrue(Deduplicator.resolve(Cluster(listOf(sms, fact(5, reversal = true, atSecond = 3))), null).isReversal, "any reversal makes it a reversal")
    }
}

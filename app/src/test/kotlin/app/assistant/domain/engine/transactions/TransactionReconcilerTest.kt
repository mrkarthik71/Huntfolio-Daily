package app.assistant.domain.engine.transactions

import app.assistant.domain.engine.dedupe.BASE
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.dedupe.UserEdits
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TransactionReconcilerTest {
    private val window = Duration.ofMinutes(15)

    /** A stored Transaction built from exactly these messages. */
    private fun stored(id: Long, vararg of: ParsedFact, edits: UserEdits = UserEdits(), editedAt: Instant? = null) =
        StoredTxn(id, edits, editedAt, of.map { it.sourceRef }.toSet())

    private fun reconcile(facts: List<ParsedFact>, stored: List<StoredTxn> = emptyList()) = TransactionReconciler.reconcile(facts, emptySet(), stored, window)

    // Two payments the Deduplicator keeps apart until the middle message (three) arrives and pulls them into one:
    // message three is received earliest, so the payment's time moves to it and message two falls inside the window.
    private val first = fact(1, atSecond = 0, origin = "BANKA", receivedAfter = 5)
    private val last = fact(2, kind = SourceKind.APP, atSecond = 1_200, origin = "PAYAPP")
    private val between = fact(3, atSecond = 600, origin = "BANKC", receivedAfter = -700)

    @Test
    fun `messages with nothing stored become new Transactions`() {
        val plan = reconcile(listOf(fact(1), fact(2, kind = SourceKind.APP, origin = "PAYAPP", atSecond = 60), fact(3, amount = 99_999)))
        assertEquals(listOf(null, null), plan.planned.map { it.existingId })
        assertTrue(plan.removedIds.isEmpty())
        assertEquals(TxnSource.MIXED, plan.planned.first { it.cluster.facts.size == 2 }.source)
    }

    @Test
    fun `a Transaction keeps its id when another message for the same payment arrives`() {
        val sms = fact(1)
        val app = fact(2, kind = SourceKind.APP, origin = "PAYAPP", atSecond = 60)
        val plan = reconcile(listOf(sms, app), stored = listOf(stored(7, sms)))
        assertEquals(listOf(7L), plan.planned.map { it.existingId })
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test
    fun `without the middle message the two payments stay apart`() {
        val plan = reconcile(listOf(first, last))
        assertEquals(2, plan.planned.size)
    }

    @Test
    fun `when two stored Transactions end up as one payment the lowest id survives`() {
        val plan = reconcile(listOf(first, last, between), stored = listOf(stored(9, last), stored(4, first)))
        assertEquals(listOf(4L), plan.planned.map { it.existingId })
        assertEquals(listOf(9L), plan.removedIds)
    }

    @Test
    fun `on a collapse the newest edit of a field wins and unedited fields stay open`() {
        val older = stored(4, first, edits = UserEdits(amount = Paise(111), merchant = "Old"), editedAt = BASE.plusSeconds(10))
        val newer = stored(9, last, edits = UserEdits(amount = Paise(222)), editedAt = BASE.plusSeconds(20))
        val planned = reconcile(listOf(first, last, between), listOf(older, newer)).planned.single()
        assertEquals(Paise(222), planned.edits.amount)
        assertEquals("Old", planned.edits.merchant)
        assertNull(planned.edits.type)
        assertNull(planned.edits.at)
        assertEquals(BASE.plusSeconds(20), planned.editedAt)
        // The edits show through in what the Transaction resolves to.
        assertEquals(Paise(222), planned.resolved.amount)
        assertEquals("Old", planned.resolved.merchantOrVpa)
    }

    @Test
    fun `equal edit times go to the lower id regardless of list order`() {
        val a = stored(4, first, edits = UserEdits(amount = Paise(111)), editedAt = BASE)
        val b = stored(9, last, edits = UserEdits(amount = Paise(222)), editedAt = BASE)
        val facts = listOf(first, last, between)
        assertEquals(Paise(111), reconcile(facts, listOf(a, b)).planned.single().edits.amount)
        assertEquals(Paise(111), reconcile(facts, listOf(b, a)).planned.single().edits.amount)
    }

    @Test
    fun `when messages of one Transaction split the earliest group keeps the id and the rest becomes new`() {
        // Different references mean these two cannot be one payment, yet they were stored together.
        val a = fact(1, reference = "AAA", atSecond = 0, origin = "BANKA")
        val b = fact(2, reference = "BBB", atSecond = 30, origin = "BANKC")
        val plan = reconcile(listOf(a, b), stored = listOf(stored(5, a, b)))
        assertEquals(2, plan.planned.size)
        assertEquals(listOf(5L, null), plan.planned.map { it.existingId })
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test
    fun `the group holding most of a split Transaction keeps its id`() {
        val a = fact(1, reference = "AAA", atSecond = 0, origin = "BANKA")
        val b1 = fact(2, reference = "BBB", atSecond = 30, origin = "BANKC")
        val b2 = fact(3, kind = SourceKind.APP, reference = "BBB", atSecond = 40, origin = "PAYAPP")
        val plan = reconcile(listOf(a, b1, b2), stored = listOf(stored(5, a, b1, b2)))
        assertEquals(5L, plan.planned.single { it.cluster.facts.size == 2 }.existingId)
        assertNull(plan.planned.single { it.cluster.facts.size == 1 }.existingId)
    }

    @Test
    fun `a stored Transaction none of whose messages remain is removed`() {
        val gone = fact(99)
        val plan = reconcile(listOf(fact(1)), stored = listOf(stored(3, gone)))
        assertEquals(listOf(3L), plan.removedIds)
        assertEquals(listOf(null), plan.planned.map { it.existingId })
    }

    @Test
    fun `the order of the stored list does not change the plan`() {
        val other = fact(4, amount = 55_555, atSecond = 40)
        val txns = listOf(stored(4, first), stored(9, last), stored(12, other))
        val facts = listOf(first, last, between, other)
        assertEquals(reconcile(facts, txns), reconcile(facts, txns.reversed()))
    }

    @Test
    fun `a forced merge holds Transactions together that would stand apart`() {
        val a = fact(1, amount = 100)
        val b = fact(2, amount = 250, atSecond = 86_400)
        val forced = setOf(setOf(a.sourceRef, b.sourceRef))
        val plan = TransactionReconciler.reconcile(listOf(a, b), forced, listOf(stored(3, a), stored(8, b)), window)
        assertEquals(listOf(3L), plan.planned.map { it.existingId })
        assertEquals(listOf(8L), plan.removedIds)
        assertEquals(2, plan.planned.single().cluster.facts.size)
    }
}

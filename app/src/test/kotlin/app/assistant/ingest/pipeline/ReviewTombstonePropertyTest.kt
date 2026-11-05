// Feature: Phase 3 Money — Property 43: One review item per Source_Ref; tombstones are final.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Property 43 (Requirements 30.1, 30.2, 30.4, 32.7). Through any sequence of ingest, dismiss, resolve and Transaction delete:
 * at most one review item exists per Source_Ref; a resolved or dismissed item leaves no text anywhere; and a Source_Ref
 * that was dismissed, or whose Transaction was deleted, never again produces a Transaction or a review item.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewTombstonePropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private enum class Op { INGEST, DISMISS, RESOLVE, DELETE }

    private data class Step(val op: Op, val pick: Int)

    private val sequences = arbitrary {
        val steps = ArrayList<Step>()
        repeat(Arb.int(3..16).bind()) { steps += Step(Arb.element(Op.entries).bind(), Arb.int(0..99).bind()) }
        steps.toList()
    }

    /** Three unreadable messages, each with its own marker in the text, and three valid ones with different amounts. */
    private val universe: List<RawMessage> by lazy {
        val pay = MessageGen.Pay(
            amount = 100_000,
            day = 5,
            hour = 14,
            minute = 5,
            merchant = "Swiggy",
            account4 = "1234",
            reference = "REF111111",
            balance = 500_000,
        )
        listOf(
            Spec.Sms("AX-BANKA-S", MessageGen.unreadable(0, pay, 0)),
            Spec.Sms("AX-BANKA-S", MessageGen.unreadable(0, pay, 1)),
            Spec.Sms("VM-BANKA", MessageGen.unreadable(0, pay, 2)),
            Spec.Sms("AX-BANKA-S", MessageGen.debitSms(pay)),
            Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay.copy(amount = 200_000, day = 9))),
            Spec.Sms("VM-BANKA", MessageGen.creditSms(pay.copy(amount = 300_000, day = 12))),
        ).build()
    }

    private val resolution = ManualTransactionInput(TxnType.DEBIT, Paise(12_345), Instant.parse("2025-01-10T06:00:00Z"), "Manual Shop")

    private fun markerOf(text: String): String = Regex("LN2025STATEMENT\\d").find(text)?.value ?: error("an unreadable message keeps its marker")

    private fun checkInvariants(tombstoned: Set<SourceRef>, closedMarkers: Set<String>) {
        val items = fx.reviewItems()
        assertEquals("one item per Source_Ref", items.size, items.map { it.sourceRef }.toSet().size)
        val storedRefs = fx.transactionList().flatMap { it.sourceRefs }.toSet()
        val factRefs = fx.snapshot().factRefs.toSet()
        tombstoned.forEach { ref ->
            assertTrue("tombstoned ref has no item", items.none { it.sourceRef == ref })
            assertTrue("tombstoned ref has no Transaction", ref !in storedRefs)
            assertTrue("tombstoned ref has no stored fact", ref.value !in factRefs)
        }
        val text = MoneyTestDb.allText(fx.db)
        closedMarkers.forEach { marker -> assertTrue("a closed item leaves no text: $marker", text.none { it.contains(marker) }) }
    }

    @Test
    fun `Property 43 - one item per ref, no text after resolve or dismiss, and tombstones are final`() {
        runBlocking {
            checkAll(200, sequences) { steps ->
                fx.reset()
                val tombstoned = HashSet<SourceRef>()
                val closed = HashSet<String>()

                steps.forEach { (op, pick) ->
                    when (op) {
                        Op.INGEST -> {
                            val message = universe[pick % universe.size]
                            val outcome = fx.ingest(message)
                            if (fx.refOf(message) in tombstoned) assertEquals(IngestOutcome.TOMBSTONED, outcome)
                        }
                        Op.DISMISS -> fx.reviewItems().takeIf { it.isNotEmpty() }?.let { items ->
                            val item = items[pick % items.size]
                            fx.queue.dismiss(item.id)
                            tombstoned += item.sourceRef
                            closed += markerOf(item.redactedText)
                        }
                        Op.RESOLVE -> fx.reviewItems().takeIf { it.isNotEmpty() }?.let { items ->
                            val item = items[pick % items.size]
                            assertTrue(fx.queue.resolve(item.id, resolution) is Outcome.Ok)
                            closed += markerOf(item.redactedText)
                        }
                        Op.DELETE -> fx.transactionList().takeIf { it.isNotEmpty() }?.let { list ->
                            val victim = list[pick % list.size]
                            fx.transactions.delete(victim.id)
                            tombstoned += victim.sourceRefs
                        }
                    }
                    checkInvariants(tombstoned, closed)
                }

                // Everything comes in again, as a catch-up read would deliver it.
                universe.forEach { message ->
                    val outcome = fx.ingest(message)
                    if (fx.refOf(message) in tombstoned) assertEquals(IngestOutcome.TOMBSTONED, outcome)
                }
                checkInvariants(tombstoned, closed)
            }
        }
    }
}

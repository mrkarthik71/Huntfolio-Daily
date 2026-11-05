// Feature: Phase 3 Money — Property 4: Injected instructions do not change outcomes.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
import app.assistant.domain.engine.redact.RedactorFixtures
import app.assistant.domain.engine.transactions.Transaction
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

/**
 * Property 4 (Requirements 4.1, 4.2, 4.4), as far as the pipeline goes. A message with instruction text or a link
 * inserted into it, at the start, at the end or inside the Merchant, either gives the same effect as the clean message
 * or is not understood and goes to the review queue (or is an OTP); it never writes to any store the clean message does not
 * write to: no Task, Goal, Bill, notification, setting or alarm. What reaches the review queue has had the instruction
 * text removed. Nothing in the pipeline makes a network request, so there is no host to check; the AI half of the property
 * (Suggestions, outgoing hosts, Redacted_Payload) belongs to Phase 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InjectionPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private val synthetic = RedactorFixtures.synthetic()
    private val injections: List<String> =
        synthetic.instructions + synthetic.links + synthetic.instructions.zip(synthetic.links) { text, link -> "$text $link" }

    private data class Scenario(val kind: Int, val pay: MessageGen.Pay, val injection: String, val placement: Int)

    private val scenarios = arbitrary {
        Scenario(Arb.int(0..3).bind(), MessageGen.pays.bind(), Arb.element(injections).bind(), Arb.int(0..2).bind())
    }

    /** The message with [injection] put in the place [placement] names: 0 start, 1 end, 2 inside the Merchant where there is one. */
    private fun spec(s: Scenario, injection: String?): Spec {
        val pay = if (injection != null && s.placement == 2 && s.kind != BILL) s.pay.copy(merchant = "Cafe $injection") else s.pay
        val body = when (s.kind) {
            0 -> MessageGen.debitSms(pay)
            1 -> MessageGen.upiDebit(pay)
            2 -> MessageGen.creditSms(pay)
            else -> MessageGen.billSms(pay)
        }
        val text = when {
            injection == null || (s.placement == 2 && s.kind != BILL) -> body
            s.placement == 0 -> "$injection $body"
            else -> "$body $injection"
        }
        return if (s.kind == 1) Spec.Notif(MessageGen.UPI_PACKAGE, "", text) else Spec.Sms(MessageGen.SMS_SENDERS[0], text)
    }

    private fun effect(list: List<Transaction>) = list.map {
        listOf(it.type, it.amount, it.at, it.account4, it.reference, it.balance, it.isReversal)
    }.sortedBy { it.toString() }

    private data class Run(val outcome: IngestOutcome, val counts: Map<String, Int>, val effect: List<List<Any?>>, val bills: List<String>)

    private fun run(spec: Spec): Run {
        fx.reset()
        val outcome = fx.ingest(listOf(spec).build().single())
        val snapshot = fx.snapshot()
        // A bill proposal without its Merchant: the injection may only change who it is for, not what is owed or when.
        val bills = snapshot.billProposals.map { it.substringAfter('|') }
        return Run(outcome, MoneyTestDb.rowCounts(fx.db), effect(fx.transactionList()), bills)
    }

    @Test
    fun `Property 4 - inserted instructions cause no effect beyond the clean message or a review item`() {
        runBlocking {
            checkAll(500, scenarios) { s ->
                val clean = run(spec(s, null))
                val injected = run(spec(s, s.injection))

                assertTrue(
                    "outcome ${injected.outcome} for a message that gives ${clean.outcome}",
                    injected.outcome in setOf(clean.outcome, IngestOutcome.REVIEW_QUEUED, IngestOutcome.OTP),
                )

                // Only the stores a message of this kind writes may change.
                val message = setOf("txn", "parsed_fact", "merchant", "merchant_alias", "processed_source_ref", "review_item", "bill_proposal")
                val others = clean.counts.keys - message
                others.forEach { table -> assertEquals("the $table store was touched", clean.counts.getValue(table), injected.counts.getValue(table)) }

                if (injected.outcome == clean.outcome) {
                    assertEquals(clean.effect, injected.effect)
                    assertEquals(clean.bills, injected.bills)
                } else {
                    assertTrue("a message that was not understood makes no Transaction", injected.effect.isEmpty())
                    assertTrue("nor a Bill proposal", injected.bills.isEmpty())
                }
                // Instruction text never reaches the review queue.
                fx.reviewItems().forEach { item -> fx.patterns.instruction.forEach { assertTrue(it.pattern, !it.containsMatchIn(item.redactedText)) } }
            }
        }
    }

    private companion object {
        const val BILL = 3
    }
}

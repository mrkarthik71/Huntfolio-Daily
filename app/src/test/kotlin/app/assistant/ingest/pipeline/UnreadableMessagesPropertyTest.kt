// Feature: Phase 3 Money — Property 37: Unreadable messages go to the Review_Queue and processing continues.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.security.log.DiagnosticEvent
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
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
 * Property 37 (Requirements 28.4, 28.5, 30.1). In any sequence where some messages match no format or hold an invalid
 * amount, date or field, each of those makes exactly one review item and no Transaction, and every valid message
 * before, between and after them is still parsed and stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnreadableMessagesPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private data class Item(val valid: Boolean, val kind: Int, val pay: MessageGen.Pay)

    private val sequences = arbitrary {
        val items = ArrayList<Item>()
        repeat(Arb.int(1..10).bind()) {
            items += Item(Arb.boolean().bind(), Arb.int(0..MessageGen.UNREADABLE_KINDS - 1).bind(), MessageGen.pays.bind())
        }
        items.toList()
    }

    @Test
    fun `Property 37 - each unreadable message makes one review item and no Transaction, and valid ones are still stored`() {
        runBlocking {
            checkAll(300, sequences) { items ->
                fx.reset()
                val specs = items.mapIndexed { i, item ->
                    // A different amount for each valid payment, so none can be taken for another's duplicate.
                    val pay = item.pay.copy(amount = BASE_AMOUNT + i * AMOUNT_STEP)
                    val sender = MessageGen.SMS_SENDERS[i % MessageGen.SMS_SENDERS.size]
                    when {
                        !item.valid -> Spec.Sms(sender, MessageGen.unreadable(item.kind, pay, i))
                        item.kind % 3 == 0 -> Spec.Sms(sender, MessageGen.debitSms(pay))
                        item.kind % 3 == 1 -> Spec.Sms(sender, MessageGen.creditSms(pay))
                        else -> Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay))
                    }
                }
                val messages = specs.build()

                val outcomes = messages.map { fx.ingest(it) }

                items.forEachIndexed { i, item ->
                    assertEquals("message $i: ${item.kind}", if (item.valid) IngestOutcome.TXN_CREATED else IngestOutcome.REVIEW_QUEUED, outcomes[i])
                }
                val badRefs = messages.filterIndexed { i, _ -> !items[i].valid }.map { fx.refOf(it) }
                val goodRefs = messages.filterIndexed { i, _ -> items[i].valid }.map { fx.refOf(it) }

                // Exactly one item per unreadable message, and nothing else in the queue.
                assertEquals(badRefs.map { it.value }.sorted(), fx.reviewItems().map { it.sourceRef.value }.sorted())
                // Every valid message is stored, and no unreadable one made a Transaction.
                val stored = fx.transactionList()
                assertEquals(goodRefs.size, stored.size)
                assertEquals(goodRefs.map { it.value }.sorted(), stored.flatMap { t -> t.sourceRefs.map { it.value } }.sorted())
                assertTrue(stored.none { t -> t.sourceRefs.any { it in badRefs } })
                assertTrue("nothing failed", fx.events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().isEmpty())
            }
        }
    }

    private companion object {
        const val BASE_AMOUNT = 100_000L
        const val AMOUNT_STEP = 7_919L
    }
}

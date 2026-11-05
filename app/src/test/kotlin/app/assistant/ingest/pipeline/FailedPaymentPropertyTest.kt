// Feature: Phase 3 Money — Property 38: Failed payments change nothing.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
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
 * Property 38 (Requirement 28.11). Whatever is stored, a failed, declined or cancelled payment message creates no
 * Transaction and no review item and changes no stored Transaction. To make a wrong link tempting, the failed payments
 * often repeat the amount, time and merchant of a stored one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FailedPaymentPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private data class Scenario(val stored: List<Spec>, val failed: List<Spec>)

    private val scenarios = arbitrary {
        val stored = ArrayList<Spec>()
        val failed = ArrayList<Spec>()
        repeat(Arb.int(0..4).bind()) {
            val pay = MessageGen.pays.bind()
            stored += when (Arb.int(0..2).bind()) {
                0 -> Spec.Sms(Arb.element(MessageGen.SMS_SENDERS).bind(), MessageGen.debitSms(pay))
                1 -> Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay))
                else -> Spec.Sms(Arb.element(MessageGen.SMS_SENDERS).bind(), MessageGen.creditSms(pay))
            }
            if (Arb.boolean().bind()) failed += failedFor(pay, Arb.boolean().bind(), Arb.element(MessageGen.SMS_SENDERS).bind())
        }
        repeat(Arb.int(1..3).bind()) { failed += failedFor(MessageGen.pays.bind(), Arb.boolean().bind(), Arb.element(MessageGen.SMS_SENDERS).bind()) }
        Scenario(stored, failed)
    }

    private fun failedFor(pay: MessageGen.Pay, viaApp: Boolean, sender: String): Spec =
        if (viaApp) Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiFailed(pay)) else Spec.Sms(sender, MessageGen.failedSms(pay))

    @Test
    fun `Property 38 - a failed payment creates nothing and changes no stored Transaction`() {
        runBlocking {
            checkAll(300, scenarios) { s ->
                fx.reset()
                val messages = (s.stored + s.failed).build()
                val stored = messages.take(s.stored.size)
                val failed = messages.drop(s.stored.size)
                stored.forEach { fx.ingest(it) }

                val before = fx.snapshot()
                val transactionsBefore = fx.transactionList()
                val counts = MoneyTestDb.rowCounts(fx.db)

                failed.forEach { assertEquals(IngestOutcome.FAILED_PAYMENT, fx.ingest(it)) }

                assertEquals(transactionsBefore, fx.transactionList())
                assertEquals(before, fx.snapshot())
                assertTrue(fx.reviewItems().isEmpty())
                assertEquals(counts + ("processed_source_ref" to counts.getValue("processed_source_ref") + failed.size), MoneyTestDb.rowCounts(fx.db))
            }
        }
    }
}

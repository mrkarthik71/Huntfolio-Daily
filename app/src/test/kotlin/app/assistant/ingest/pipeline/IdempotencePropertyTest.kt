// Feature: Phase 3 Money — Property 40: Ingest is idempotent.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.domain.model.DataSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Property 40 (Requirement 29.3). Ingesting any message again with the same Source_Ref, one at a time (live reading) or as
 * a batch (catch-up or history import), leaves the stored Transactions, Source_Ref links, review items and Bill proposals
 * exactly as ingesting each message once does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IdempotencePropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private data class Scenario(val specs: List<Spec>, val repeats: List<Pair<Int, Int>>)

    private val scenarios = arbitrary {
        val specs = ArrayList<Spec>()
        repeat(Arb.int(1..6).bind()) {
            val pay = MessageGen.pays.bind()
            val sender = Arb.element(MessageGen.SMS_SENDERS).bind()
            when (Arb.int(0..6).bind()) {
                0 -> {
                    // One payment seen twice: by SMS and by app notification.
                    specs += Spec.Sms(sender, MessageGen.debitSms(pay))
                    specs += Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay))
                }
                1 -> specs += Spec.Sms(sender, MessageGen.creditSms(pay))
                2 -> specs += Spec.Sms(sender, MessageGen.unreadable(Arb.int(0..MessageGen.UNREADABLE_KINDS - 1).bind(), pay, specs.size))
                3 -> specs += Spec.Sms(sender, MessageGen.billSms(pay))
                4 -> specs += Spec.Sms(sender, MessageGen.otpSms(MessageGen.otpCodes.bind(), pay))
                5 -> specs += Spec.Sms(sender, MessageGen.failedSms(pay))
                else -> specs += Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay))
            }
        }
        val repeats = ArrayList<Pair<Int, Int>>()
        repeat(Arb.int(1..6).bind()) { repeats += Arb.int(0..99).bind() to Arb.int(0..99).bind() }
        Scenario(specs, repeats)
    }

    @Test
    fun `Property 40 - ingesting a message again changes nothing`() {
        runBlocking {
            checkAll(300, scenarios) { s ->
                val once = s.specs.build()

                fx.reset()
                once.forEach { fx.ingest(it) }
                val expected = fx.snapshot()

                // The same messages with extra copies slipped in after their first arrival: live reading.
                val withRepeats = once.toMutableList()
                s.repeats.forEach { (pick, where) ->
                    val original = once[pick % once.size]
                    val first = withRepeats.indexOf(original)
                    withRepeats.add(first + 1 + where % (withRepeats.size - first), original)
                }
                fx.reset()
                val seen = HashSet<RawMessage>()
                withRepeats.forEach { message ->
                    val outcome = fx.ingest(message)
                    if (!seen.add(message)) assertEquals(IngestOutcome.DUPLICATE_REF, outcome)
                }
                assertEquals(expected, fx.snapshot())

                // The same again as one batch: catch-up or history import.
                fx.reset()
                fx.audit.reads.clear()
                runBlocking { fx.pipeline.ingestBatch(DataSource.SMS, withRepeats) }
                assertEquals(expected, fx.snapshot())
                assertEquals(1, fx.audit.reads.size)
            }
        }
    }
}

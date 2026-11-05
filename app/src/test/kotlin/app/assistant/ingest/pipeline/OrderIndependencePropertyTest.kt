// Feature: Phase 3 Money — Property 41: Ingest is order-independent.
package app.assistant.ingest.pipeline

import android.app.Application
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
import kotlin.random.Random

/**
 * Property 41 (Requirement 29.4). Whatever order a set of messages arrives in, the stored Transactions, their Source_Refs and
 * every field are the same (up to row ids). The payments are drawn from a small pool of amounts, minutes and references so
 * that many fall inside each other's duplicate window and the grouping really is in question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OrderIndependencePropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private data class Scenario(val specs: List<Spec>, val seedA: Int, val seedB: Int)

    /** A payment squeezed into a narrow corner of the space, so neighbours collide. */
    private val crowdedPays = arbitrary {
        MessageGen.pays.bind().copy(
            amount = Arb.element(listOf(25_000L, 99_900L)).bind(),
            day = 3,
            hour = 10,
            minute = Arb.int(0..40).bind(),
            account4 = Arb.element(listOf("1111", "2222")).bind(),
            reference = "REF" + Arb.int(0..2).bind(),
        )
    }

    private val scenarios = arbitrary {
        val specs = ArrayList<Spec>()
        repeat(Arb.int(2..7).bind()) {
            val pay = crowdedPays.bind()
            val sender = Arb.element(MessageGen.SMS_SENDERS).bind()
            specs += when (Arb.int(0..4).bind()) {
                0 -> Spec.Sms(sender, MessageGen.debitSms(pay))
                1 -> Spec.Sms(sender, MessageGen.creditSms(pay))
                2 -> Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay))
                3 -> Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiCredit(pay))
                else -> Spec.Sms(sender, MessageGen.unreadable(Arb.int(0..MessageGen.UNREADABLE_KINDS - 1).bind(), pay, specs.size))
            }
        }
        Scenario(specs, Arb.int().bind(), Arb.int().bind())
    }

    @Test
    fun `Property 41 - the stored Transactions do not depend on the order messages arrive in`() {
        var scenariosRun = 0
        var withMergedPayment = 0
        runBlocking {
            checkAll(300, scenarios) { s ->
                val messages = s.specs.build()
                val orders = listOf(messages, messages.shuffled(Random(s.seedA)), messages.shuffled(Random(s.seedB)), messages.reversed())

                val snapshots = orders.map { order ->
                    fx.reset()
                    order.forEach { fx.ingest(it) }
                    fx.snapshot()
                }

                snapshots.drop(1).forEach { assertEquals(snapshots.first(), it) }
                scenariosRun++
                if (fx.transactionList().any { it.sourceRefs.size > 1 }) withMergedPayment++
            }
        }
        // The property is only worth anything if payments really do merge in a good share of the scenarios.
        assertTrue("only $withMergedPayment of $scenariosRun scenarios merged a payment", withMergedPayment * MIN_SHARE_DENOMINATOR >= scenariosRun)
    }

    private companion object {
        /** At least one scenario in five must contain a merged payment. */
        const val MIN_SHARE_DENOMINATOR = 5
    }
}

// Feature: Phase 3 Money — Property 3: OTP precedence and discard.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
import app.assistant.parse.money.MessageParser
import app.assistant.parse.money.ParseResult
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
 * Property 3 (Requirements 3.4, 28.3). Any Money_Message that matches an OTP format or holds an OTP keyword is an OTP, also
 * when it carries an amount, a merchant or a whole debit message: it creates no Transaction, review item or Bill
 * proposal and leaves no content in any store or log. Only its Source_Ref is recorded, with the outcome `otp`.
 * (The email half of the property belongs to Mail_Triage, Phase 5.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OtpPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private data class Otp(val spec: Spec, val code: String)

    private val scenarios = arbitrary {
        val pay = MessageGen.pays.bind()
        val code = MessageGen.otpCodes.bind()
        val keyword = Arb.element(MessageGen.OTP_KEYWORDS).bind()
        val origin = Arb.element(MessageGen.SMS_SENDERS).bind()
        val spec = when (Arb.int(0..4).bind()) {
            0 -> Spec.Sms(origin, MessageGen.otpSms(code, pay))
            1 -> Spec.Sms(origin, "Your $keyword is $code. ${MessageGen.debitSms(pay)}")
            2 -> Spec.Sms(origin, "Do not share your $keyword $code with anyone. Paid Rs ${MessageGen.money(pay.amount)} to ${pay.merchant}")
            3 -> Spec.Notif(MessageGen.UPI_PACKAGE, "", "Your $keyword $code. ${MessageGen.upiDebit(pay)}")
            else -> Spec.Sms(origin, "${MessageGen.billSms(pay)} Use $keyword $code to pay")
        }
        Otp(spec, code)
    }

    @Test
    fun `Property 3 - an OTP is discarded and leaves nothing behind but its Source_Ref`() {
        runBlocking {
            checkAll(500, scenarios) { s ->
                fx.reset()
                val message = listOf(s.spec).build().single()
                // The parser itself says OTP, whatever else the message holds.
                assertTrue(MessageParser.parse(message.text, message.origin, message.receivedAt, fx.time.zone(), fx.registryProbe) is ParseResult.Otp)
                fx.registryProbe.calls.set(0)

                val before = MoneyTestDb.rowCounts(fx.db)
                assertEquals(IngestOutcome.OTP, fx.ingest(message))

                val after = MoneyTestDb.rowCounts(fx.db)
                assertEquals(before + ("processed_source_ref" to 1), after)
                assertTrue(fx.transactionList().isEmpty())
                assertTrue(fx.reviewItems().isEmpty())
                assertTrue("no log content", fx.events.isEmpty())
                assertTrue("no OTP value in any store", MoneyTestDb.allText(fx.db).none { it.contains(s.code) })
                val outcomes = fx.db.openHelper.writableDatabase.query("SELECT outcome FROM processed_source_ref").use { c ->
                    generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
                }
                assertEquals(listOf("otp"), outcomes)
            }
        }
    }
}

// Feature: Phase 3 Money — Property 1: No Message_Text at rest or in logs.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Property 1 (Requirements 3.1, 3.2, 3.5, 7.5). After any mix of messages, whatever their outcome, no stored value
 * (except a review item's redacted text), no log event and no Audit_Log entry holds 12 or more characters of a message's
 * text that is not also an extracted field value, and no OTP value appears anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoTextAtRestPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    /** A message, the field values it legitimately yields, and any OTP code it carries. */
    private data class Sent(val spec: Spec, val fields: Set<String>, val code: String?)

    private val sequences = arbitrary {
        val sent = ArrayList<Sent>()
        repeat(Arb.int(1..8).bind()) {
            val pay = MessageGen.pays.bind()
            val code = MessageGen.otpCodes.bind()
            val sender = MessageGen.SMS_SENDERS[Arb.int(0 until MessageGen.SMS_SENDERS.size).bind()]
            val fields = pay.fieldValues
            sent += when (Arb.int(0..9).bind()) {
                0 -> Sent(Spec.Sms(sender, MessageGen.debitSms(pay)), fields, null)
                1 -> Sent(Spec.Notif(MessageGen.UPI_PACKAGE, "", MessageGen.upiDebit(pay)), fields, null)
                2 -> Sent(Spec.Sms(sender, MessageGen.creditSms(pay)), fields, null)
                3 -> Sent(Spec.Sms(sender, MessageGen.failedSms(pay)), fields, null)
                4 -> Sent(Spec.Sms(sender, MessageGen.billSms(pay)), fields, null)
                5 -> Sent(Spec.Sms(sender, MessageGen.otpSms(code, pay)), fields, code)
                6 -> Sent(Spec.Sms(sender, MessageGen.unreadable(Arb.int(0..MessageGen.UNREADABLE_KINDS - 1).bind(), pay, sent.size)), emptySet(), null)
                7 -> Sent(Spec.Sms("FAKEBANK", MessageGen.debitSms(pay)), emptySet(), null)
                8 -> Sent(Spec.Notif("com.other.app", "", MessageGen.upiDebit(pay)), emptySet(), null)
                else -> Sent(Spec.Notif(MessageGen.UPI_PACKAGE, "Your OTP", "$code. ${MessageGen.upiDebit(pay)}"), fields, code)
            }
        }
        sent.toList()
    }

    private fun sharesLongSubstring(stored: String, original: String): Boolean {
        val a = stored.lowercase()
        val b = original.lowercase()
        if (a.length < MIN_SHARED || b.length < MIN_SHARED) return false
        val grams = (0..b.length - MIN_SHARED).mapTo(HashSet()) { b.substring(it, it + MIN_SHARED) }
        return (0..a.length - MIN_SHARED).any { a.substring(it, it + MIN_SHARED) in grams }
    }

    @Test
    fun `Property 1 - no message text and no OTP is kept in any store, log or audit entry`() {
        runBlocking {
            checkAll(500, sequences) { sent ->
                fx.reset()
                val baseline = MoneyTestDb.allText(fx.db).toSet()
                val messages = sent.map { it.spec }.build()
                messages.forEach { fx.ingest(it) }
                runBlocking { fx.pipeline.ingestBatch(app.assistant.domain.model.DataSource.SMS, messages) }

                val texts = messages.map { it.text }
                val allowed = sent.flatMap { it.fields }.map { it.lowercase() }.toSet()
                val codes = sent.mapNotNull { it.code }

                // Stored values: everything except the review items' redacted text.
                val stored = MoneyTestDb.allText(fx.db, skip = setOf("review_item" to "redacted_text")).filterNot { it in baseline }
                stored.filterNot { it.lowercase().trim() in allowed }.forEach { value ->
                    texts.forEach { text -> assertFalse("a stored value repeats message text: $value", sharesLongSubstring(value, text)) }
                }

                // Log events carry codes and enum or number details only.
                val logged = fx.events.flatMap { listOf(it.code) + it.detail().values }
                logged.forEach { value -> texts.forEach { text -> assertFalse("a log entry repeats message text: $value", sharesLongSubstring(value, text)) } }

                // No OTP value anywhere, the review items' redacted text included.
                val everywhere = MoneyTestDb.allText(fx.db) + logged
                codes.forEach { code -> assertTrue("an OTP value was kept: $code", everywhere.none { it.contains(code) }) }
            }
        }
    }

    private companion object {
        /** The design's threshold: 12 or more characters in common is a copy of the text. */
        const val MIN_SHARED = 12
    }
}

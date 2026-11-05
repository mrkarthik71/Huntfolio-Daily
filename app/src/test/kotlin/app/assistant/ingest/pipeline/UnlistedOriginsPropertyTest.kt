// Feature: Phase 3 Money — Property 2: Unlisted origins leave no trace.
package app.assistant.ingest.pipeline

import android.app.Application
import app.assistant.data.repo.MoneyTestDb
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
 * Property 2 (Requirements 3.3, 26.1, 26.3, 27.3, 27.6). An SMS from a sender on no list, a notification from an app on
 * no list and an empty notification from a listed app leave no trace and never reach the parser; a sender that matches
 * an entry, ignoring case and its prefix and suffix, does reach the parser. The expected answer comes from an
 * independent description of the shipped list (BANKA and BANKB, optional two-character prefix and one-character suffix),
 * not from the SenderMatcher under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnlistedOriginsPropertyTest {
    private lateinit var fx: PipelineFixture

    @Before
    fun setUp() {
        fx = PipelineFixture()
    }

    @After
    fun tearDown() = fx.close()

    private val listedSender = Regex("^([A-Za-z0-9]{2}-)?BANK[AB](-[A-Za-z0-9])?$", RegexOption.IGNORE_CASE)

    private val senders: Arb<String> = arbitrary {
        val core = Arb.element(listOf("BANKA", "BANKB", "bankA", "BankB", "BANKC", "BANK", "BANKAA", "XBANKA")).bind()
        val prefix = Arb.element(listOf("", "AX-", "vm-", "9Z-", "A-", "AXX-", "AX", "--", "AX_")).bind()
        val suffix = Arb.element(listOf("", "-S", "-s", "-1", "-SS", "S", "-", "--S")).bind()
        if (Arb.int(0..5).bind() == 0) Arb.element(listOf("", "   ", "HDFC", "1234567890", "FAKEBANKA", "x".repeat(60))).bind() else prefix + core + suffix
    }

    private val packages = listOf(MessageGen.UPI_PACKAGE, "com.other.app", "", MessageGen.UPI_PACKAGE + ".evil", MessageGen.UPI_PACKAGE.uppercase())
    private val texts = listOf("", "  ", "Paid", "your statement is ready")

    @Test
    fun `Property 2 - an SMS reaches the parser exactly when its sender is on the list`() {
        runBlocking {
            checkAll(500, senders) { sender ->
                fx.reset()
                val message = listOf(Spec.Sms(sender, "Hello, your statement is ready")).build().single()
                val before = MoneyTestDb.rowCounts(fx.db)
                val outcome = fx.ingest(message)

                if (listedSender.matches(sender.trim()) && sender.trim().length <= MAX_SENDER) {
                    assertEquals("a listed sender reaches the parser: $sender", 1, fx.registryProbe.calls.get())
                } else {
                    assertEquals(IngestOutcome.DROPPED_UNLISTED, outcome)
                    assertEquals("no parser call for: $sender", 0, fx.registryProbe.calls.get())
                    assertEquals("no write for: $sender", before, MoneyTestDb.rowCounts(fx.db))
                    assertTrue(fx.events.isEmpty())
                }
            }
        }
    }

    @Test
    fun `Property 2 - a notification reaches the parser exactly when its app is listed and it has content`() {
        runBlocking {
            checkAll(500, Arb.element(packages), Arb.element(texts), Arb.element(texts)) { pkg, title, body ->
                fx.reset()
                val message = listOf(Spec.Notif(pkg, title, body)).build().single()
                val before = MoneyTestDb.rowCounts(fx.db)
                val outcome = fx.ingest(message)

                if (pkg == MessageGen.UPI_PACKAGE && (title.isNotBlank() || body.isNotBlank())) {
                    assertEquals(1, fx.registryProbe.calls.get())
                } else {
                    assertEquals(IngestOutcome.DROPPED_UNLISTED, outcome)
                    assertEquals(0, fx.registryProbe.calls.get())
                    assertEquals(before, MoneyTestDb.rowCounts(fx.db))
                    assertTrue(fx.events.isEmpty())
                }
            }
        }
    }

    private companion object {
        /** The most a sender ID can be and still be taken for a bank (SenderMatcher's documented limit). */
        const val MAX_SENDER = 40
    }
}

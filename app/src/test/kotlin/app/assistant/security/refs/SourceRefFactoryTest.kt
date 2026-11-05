package app.assistant.security.refs

import app.assistant.domain.error.Outcome
import app.assistant.domain.error.getOrNull
import app.assistant.domain.model.DataSource
import app.assistant.security.keys.KeyError
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Task 19.1: SourceRefFactory, checked against HMAC-SHA256 values computed independently with
 * Python's `hmac`/`hashlib` for the same framed input, so a bug shared between the Kotlin
 * implementation and a self-referential test cannot hide (Requirement 29.3, design: ADR 0006).
 */
class SourceRefFactoryTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyManager = FakeKeyManager(key)
    private val factory = SourceRefFactory(keyManager)

    @Test
    fun `an SMS ref matches the golden HMAC value`() {
        val body = "Rs 1,234.50 debited from a/c XX1234 on 05-01-25 14:05 at Cafe Blue. Avl Bal Rs 20,000.00"
        val ref = factory.sms("AD-BANKA-S", Instant.ofEpochSecond(1_736_064_300), body).getOrNull()
        assertEquals("sms:v1:QWRA2A3ZT7HP5PI3H4ORZJTIEM", ref?.value)
    }

    @Test
    fun `an SMS ref over UTF-8 text matches the golden HMAC value`() {
        val ref = factory.sms("AD-BANKA-S", Instant.ofEpochSecond(1_736_064_300), "Paid \u20B9250 to caf\u00E9").getOrNull()
        assertEquals("sms:v1:WJSU4IWCKIP55K6TNRWRLKOK4U", ref?.value)
    }

    @Test
    fun `a notification ref matches the golden HMAC value`() {
        val ref = factory.notification("com.example.upiapp", "0|com.example.upiapp|42|null|10001", "Payment", "Paid Rs250 to shop@upi").getOrNull()
        assertEquals("ntf:v1:2PEZCLTB2Q6A5BBMUHAABABXRM", ref?.value)
    }

    @Test
    fun `a calendar ref matches the golden HMAC value`() {
        val ref = factory.calendar(3L, 4_711L, Instant.ofEpochMilli(1_736_064_300_000)).getOrNull()
        assertEquals("cal:v1:VYLM555E2PDLE5UR3QJNZFEHDM", ref?.value)
    }

    @Test
    fun `a mail ref by Message-ID matches the golden HMAC value`() {
        val ref = factory.mail("acct-1", "<abc123@mail.example>", "INBOX", 1, 1).getOrNull()
        assertEquals("mail:v1:35NPWBNXDGVVSMSTHSL3MEVWTI", ref?.value)
    }

    @Test
    fun `a mail ref by folder, UIDVALIDITY and UID matches the golden HMAC value when the Message-ID is absent`() {
        val withoutHeader = factory.mail("acct-1", null, "INBOX", 1_700_000_000L, 42L).getOrNull()
        val blank = factory.mail("acct-1", "   ", "INBOX", 1_700_000_000L, 42L).getOrNull()
        assertEquals("mail:v1:3326NHPLJ7UOVSXFDATABMUNTM", withoutHeader?.value)
        assertEquals(withoutHeader, blank)
    }

    @Test
    fun `a Huntfolio ref matches the golden HMAC value`() {
        assertEquals("hf:v1:BFW3MV7HEH5RIMBYBGLZJAFX24", factory.huntfolio("rec-0001").getOrNull()?.value)
    }

    @Test
    fun `an unavailable key is relayed as the error, not a ref`() {
        val broken = SourceRefFactory(FakeKeyManager().apply { failure = KeyError.KeystoreUnavailable })
        assertEquals(Outcome.Err(KeyError.KeystoreUnavailable), broken.sms("AD-BANKA-S", Instant.EPOCH, "x"))
    }

    @Test
    fun `fields cannot be concatenated across a boundary to collide`() {
        // Without length-prefixing, ("ab","c") and ("a","bc") would hash identically.
        val one = factory.mail("ab", "c", "INBOX", 0, 0)
        val two = factory.mail("a", "bc", "INBOX", 0, 0)
        assertNotEquals(one, two)
    }

    @Test
    fun `the source prefix is always the one the message actually came from`() {
        val sms = factory.sms("AD-BANKA-S", Instant.EPOCH, "x").getOrNull()!!
        val ntf = factory.notification("pkg", "k", "t", "x").getOrNull()!!
        assertEquals(DataSource.SMS, sms.source)
        assertEquals(DataSource.APP_NOTIFICATIONS, ntf.source)
    }

    @Test
    fun `live reading and history import of the same SMS give the same ref (Requirement 29_3)`() {
        val body = "the same message text"
        val sentAt = Instant.ofEpochSecond(1_736_064_300)
        val liveRead = factory.sms("AD-BANKA-S", sentAt, body)
        val historyImport = factory.sms("ad-banka-s", sentAt, body)
        assertEquals(liveRead, historyImport, "case must not change the ref")
    }

    private val chars = "abcdeXYZ019 -_."

    private fun arbText(range: IntRange): Arb<String> = arbitrary { (1..Arb.int(range).bind()).map { chars[Arb.int(chars.indices).bind()] }.joinToString("") }

    @Test
    fun `the ref never contains the message text`() = runTest {
        checkAll(arbText(6..200)) { body ->
            val ref = factory.sms("AD-BANKA-S", Instant.EPOCH, body).getOrNull()!!
            assertFalse(ref.value.contains(body.take(6)), "a ref is a hash, not text")
        }
    }

    @Test
    fun `different stable inputs give different refs, and normalisation gives the same one`() = runTest {
        val arbSms = arbitrary {
            Triple(arbText(1..11).bind(), Instant.ofEpochSecond(Arb.long(0L..2_000_000_000L).bind()), arbText(0..100).bind())
        }
        checkAll(arbSms, arbSms) { (s1, t1, b1), (s2, t2, b2) ->
            val a = factory.sms(s1, t1, b1)
            val b = factory.sms(s2, t2, b2)
            if (s1.trim().uppercase() == s2.trim().uppercase() && t1.epochSecond == t2.epochSecond && b1 == b2) {
                assertEquals(a, b)
            } else {
                assertNotEquals(a, b)
            }
        }
    }

    @Test
    fun `a different key gives a different ref`() {
        val other = SourceRefFactory(FakeKeyManager(ByteArray(32) { (it + 1).toByte() }))
        assertNotEquals(factory.sms("AD-BANKA-S", Instant.EPOCH, "x"), other.sms("AD-BANKA-S", Instant.EPOCH, "x"))
    }

    @Test
    fun `a millisecond difference in the PDU timestamp does not change the ref`() {
        val seconds = Instant.ofEpochSecond(1_736_064_300)
        assertEquals(factory.sms("S", seconds, "x"), factory.sms("S", seconds.plusMillis(750), "x"))
    }

    @Test
    fun `the copy of the key handed to the factory is wiped after use`() {
        factory.sms("AD-BANKA-S", Instant.EPOCH, "x")
        val issued = checkNotNull(keyManager.lastIssued)
        assertTrue(issued.all { it == 0.toByte() }, "the key copy must be zeroed")
        // And the manager's own key is untouched, so the next call still works.
        assertTrue(factory.sms("AD-BANKA-S", Instant.EPOCH, "x") is Outcome.Ok)
    }

    @Test
    fun `a Source_Ref value never appears in logs or exceptions, only its masked form`() {
        val ref = factory.sms("AD-BANKA-S", Instant.EPOCH, "x").getOrNull()!!
        assertTrue(ref.toString().startsWith("SourceRef(sms:"))
        assertFalse(ref.toString().contains(ref.value.substringAfterLast(':')))
    }

    @Test
    fun `Base32 encodes RFC 4648 test vectors with no padding`() {
        assertEquals("", Base32.encode(byteArrayOf()))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `Base32 output uses only its own alphabet and never upper-undecodable characters`() = runTest {
        checkAll(Arb.string(0..64)) { s ->
            val encoded = Base32.encode(s.toByteArray(Charsets.UTF_8))
            assertTrue(encoded.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
        }
    }
}

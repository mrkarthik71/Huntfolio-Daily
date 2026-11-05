package app.assistant.ingest.sms

import app.assistant.domain.model.SourceRef
import app.assistant.ingest.pipeline.RawMessage
import app.assistant.security.refs.FakeKeyManager
import app.assistant.security.refs.SourceRefFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class SmsAssemblerTest {
    private val sent: Instant = Instant.parse("2025-01-05T08:35:58Z")
    private val received: Instant = Instant.parse("2025-01-05T08:36:00Z")
    private val refs = SourceRefFactory(FakeKeyManager())
    private val body = "Rs 250.00 debited from a/c 1234 on 05-01-2025 14:05 at Swiggy. Avl Bal Rs 10,000.00"

    private fun refOf(m: RawMessage.Sms): SourceRef = (refs.sms(m.origin, m.sentAt, m.body) as app.assistant.domain.error.Outcome.Ok).value

    @Test
    fun `the parts of one SMS are joined in order`() {
        val m = SmsAssembler.fromParts("AX-BANKA-S", listOf("Rs 250.00 debited from a/c 1234 ", "on 05-01-2025 14:05 ", "at Swiggy."), sent, received)
        assertEquals("Rs 250.00 debited from a/c 1234 on 05-01-2025 14:05 at Swiggy.", m?.body)
        assertEquals("AX-BANKA-S", m?.origin)
        assertEquals(received, m?.receivedAt)
        assertEquals(sent, m?.sentAt)
    }

    @Test
    fun `no sender, no parts or nothing in the body means nothing to read`() {
        assertNull(SmsAssembler.fromParts(null, listOf("x"), sent, received))
        assertNull(SmsAssembler.fromParts("   ", listOf("x"), sent, received))
        assertNull(SmsAssembler.fromParts("BANKA", emptyList(), sent, received))
        assertNull(SmsAssembler.fromParts("BANKA", listOf("", "  "), sent, received))
        assertNull(SmsAssembler.fromInbox(InboxSms(null, body, sent, received)))
        assertNull(SmsAssembler.fromInbox(InboxSms("BANKA", null, sent, received)))
        assertNull(SmsAssembler.fromInbox(InboxSms("BANKA", "   ", sent, received)))
        assertNotNull(SmsAssembler.fromInbox(InboxSms("BANKA", body, sent, received)))
    }

    @Test
    fun `the sender is trimmed`() {
        assertEquals("AX-BANKA-S", SmsAssembler.fromInbox(InboxSms("  AX-BANKA-S ", body, sent, received))?.origin)
    }

    @Test
    fun `a missing or zero sent time is replaced by the received time, the same way on both paths`() {
        listOf(null, Instant.EPOCH).forEach { missing ->
            assertEquals(received, SmsAssembler.fromParts("BANKA", listOf(body), missing, received)?.sentAt)
            assertEquals(received, SmsAssembler.fromInbox(InboxSms("BANKA", body, missing, received))?.sentAt)
        }
    }

    @Test
    fun `the same SMS read live and from the inbox has the same Source_Ref`() {
        val live = checkNotNull(SmsAssembler.fromParts("AX-BANKA-S", listOf(body.take(30), body.drop(30)), sent, received))
        val inbox = checkNotNull(SmsAssembler.fromInbox(InboxSms("AX-BANKA-S", body, sent, received)))
        assertEquals(refOf(live), refOf(inbox))

        // Also when the phone gave no sent time on either path.
        val liveNoSent = checkNotNull(SmsAssembler.fromParts("AX-BANKA-S", listOf(body), null, received))
        val inboxNoSent = checkNotNull(SmsAssembler.fromInbox(InboxSms("AX-BANKA-S", body, Instant.EPOCH, received)))
        assertEquals(refOf(liveNoSent), refOf(inboxNoSent))
    }

    @Test
    fun `a different body or sender is a different message`() {
        val a = checkNotNull(SmsAssembler.fromInbox(InboxSms("AX-BANKA-S", body, sent, received)))
        val b = checkNotNull(SmsAssembler.fromInbox(InboxSms("AX-BANKA-S", body + "!", sent, received)))
        val c = checkNotNull(SmsAssembler.fromInbox(InboxSms("VM-BANKA", body, sent, received)))
        assertEquals(3, setOf(refOf(a), refOf(b), refOf(c)).size)
    }

    @Test
    fun `an inbox row never prints its content`() {
        assertFalse(InboxSms("SECRET-SENDER", "SECRET-BODY", sent, received).toString().contains("SECRET"))
    }
}

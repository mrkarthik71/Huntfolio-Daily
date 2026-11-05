package app.assistant.ingest.pipeline

import app.assistant.domain.model.SenderEntry
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.RefState
import app.assistant.domain.port.SenderList
import app.assistant.security.keys.KeyError
import app.assistant.security.refs.FakeKeyManager
import app.assistant.security.refs.SourceRefFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The entry stages on their own, with stand-in stores, so each decision is pinned where it is made. */
class EntryStagesTest {
    private val at: Instant = Instant.parse("2025-01-05T08:36:00Z")
    private val ref = SourceRef("sms:v1:AAAA")

    private fun ctx(message: RawMessage, ref: SourceRef? = null) =
        IngestContext(MessageMeta(message.sourceKind, message.origin, message.receivedAt), message, ref)

    private fun sms(origin: String = "AX-BANKA-S") = RawMessage.Sms(origin, "body", at, at)

    private fun notification(pkg: String = "com.example.upiapp", title: String = "", body: String = "x") = RawMessage.Notification(pkg, "k", title, body, at)

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private class Ledger(val state: RefState) : IngestLedger {
        val marked = mutableListOf<Pair<SourceRef, String>>()

        override suspend fun state(ref: SourceRef) = state

        override suspend fun markProcessed(ref: SourceRef, outcome: String, at: Instant) {
            marked += ref to outcome
        }
    }

    // SenderFilterStage

    private val senders = SenderList { listOf(SenderEntry("BANKA", "??-", "-?")) }
    private val apps = MoneyAppList { it == "com.example.upiapp" }
    private val filter = SenderFilterStage(senders, apps)

    @Test
    fun `a listed sender or app continues`() {
        assertTrue(run { filter.process(ctx(sms("AX-BANKA-S"))) } is StageResult.Continue)
        assertTrue(run { filter.process(ctx(sms("banka"))) } is StageResult.Continue)
        assertTrue(run { filter.process(ctx(notification())) } is StageResult.Continue)
    }

    @Test
    fun `an unlisted sender, an unlisted app or an empty notification is dropped`() {
        val dropped = StageResult.Stop(IngestOutcome.DROPPED_UNLISTED)
        assertEquals(dropped, run { filter.process(ctx(sms("AX-BANKAX-S"))) })
        assertEquals(dropped, run { filter.process(ctx(sms(""))) })
        assertEquals(dropped, run { filter.process(ctx(notification(pkg = "com.other"))) })
        assertEquals(dropped, run { filter.process(ctx(notification(title = " ", body = ""))) })
    }

    @Test
    fun `an app removed from the list is no longer read`() {
        var listed = true
        val stage = SenderFilterStage(senders) { listed }
        assertTrue(run { stage.process(ctx(notification())) } is StageResult.Continue)
        listed = false
        assertEquals(StageResult.Stop(IngestOutcome.DROPPED_UNLISTED), run { stage.process(ctx(notification())) })
    }

    // ProcessedRefStage: stops before anything is parsed

    @Test
    fun `a handled or tombstoned Source_Ref stops here`() {
        val message = sms()
        assertTrue(run { ProcessedRefStage(Ledger(RefState.NEW)).process(ctx(message, ref)) } is StageResult.Continue)
        assertEquals(StageResult.Stop(IngestOutcome.DUPLICATE_REF), run { ProcessedRefStage(Ledger(RefState.PROCESSED)).process(ctx(message, ref)) })
        assertEquals(StageResult.Stop(IngestOutcome.TOMBSTONED), run { ProcessedRefStage(Ledger(RefState.TOMBSTONED)).process(ctx(message, ref)) })
    }

    // SourceRefStage

    @Test
    fun `the Source_Ref is the same for an SMS however many times it arrives, and differs by content and sender`() {
        val stage = SourceRefStage(SourceRefFactory(FakeKeyManager()))
        fun refOf(m: RawMessage) = ((run { stage.process(ctx(m)) }) as StageResult.Continue).ctx.ref
        assertEquals(refOf(sms()), refOf(sms()))
        assertTrue(refOf(sms()) != refOf(sms("AX-BANKB-S")))
        assertTrue(refOf(sms()) != refOf(RawMessage.Sms("AX-BANKA-S", "other", at, at)))
        assertEquals(SourceKind.APP, notification().sourceKind)
        assertTrue(refOf(notification())!!.value.startsWith("ntf:"))
    }

    @Test
    fun `an unavailable key is an error for this message and says nothing about it`() {
        val keys = FakeKeyManager().also { it.failure = KeyError.KeystoreUnavailable }
        val error = assertThrows(IllegalStateException::class.java) { run { SourceRefStage(SourceRefFactory(keys)).process(ctx(sms())) } }
        assertTrue(!error.message.orEmpty().contains("body"))
    }
}

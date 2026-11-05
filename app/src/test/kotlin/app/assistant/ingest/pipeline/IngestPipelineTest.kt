package app.assistant.ingest.pipeline

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.AuditEntry
import app.assistant.domain.model.AuditId
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.PromptType
import app.assistant.domain.model.SourceKind
import app.assistant.domain.port.AuditError
import app.assistant.domain.port.AuditLog
import app.assistant.security.log.DiagnosticEvent
import app.assistant.security.log.IngestStage
import app.assistant.security.log.SafeLog
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** The mechanics of the chain, with stand-in stages: ordering, error mapping, serial handling and the batch audit entry. */
class IngestPipelineTest {
    private val time = FakeTimeSource()
    private val events = mutableListOf<DiagnosticEvent>()
    private val log = SafeLog(time).also { it.addSink { _, event -> events += event } }
    private val audit = RecordingAudit()
    private val secret = "SECRET-MESSAGE-TEXT-4821"
    private val at: Instant = Instant.parse("2025-01-05T08:00:00Z")

    private fun sms(body: String = secret) = RawMessage.Sms("BANKA", body, at, at)

    private fun pipeline(vararg stages: PipelineStage) = IngestPipeline(stages.toList(), log, audit, time)

    private fun stage(stage: IngestStage, action: suspend (IngestContext) -> StageResult) = object : PipelineStage {
        override val name = stage

        override suspend fun process(ctx: IngestContext) = action(ctx)
    }

    private fun stop(outcome: IngestOutcome) = StageResult.Stop(outcome)

    class RecordingAudit : AuditLog {
        data class Read(val source: DataSource, val at: Instant, val items: Int, val success: Boolean)

        val reads = mutableListOf<Read>()
        var failure: Exception? = null

        override suspend fun recordRead(source: DataSource, at: Instant, itemCount: Int, success: Boolean) {
            failure?.let { throw it }
            reads += Read(source, at, itemCount, success)
        }

        override suspend fun recordAiSend(at: Instant, mode: AiMode, type: PromptType, payload: String): Outcome<AuditId, AuditError> =
            error("not used by the pipeline")

        override suspend fun markAiResult(id: AuditId, at: Instant, success: Boolean) = error("not used by the pipeline")

        override fun newest(limit: Int): Flow<List<AuditEntry>> = emptyFlow()
    }

    @Test
    fun `stages run in order and the first Stop decides the outcome`() = runBlocking {
        val seen = mutableListOf<String>()
        val result = pipeline(
            stage(IngestStage.SENDER_FILTER) {
                seen += "a"
                StageResult.Continue(it)
            },
            stage(IngestStage.PARSE) {
                seen += "b"
                stop(IngestOutcome.OTP)
            },
            stage(IngestStage.PERSIST) {
                seen += "c"
                stop(IngestOutcome.TXN_CREATED)
            },
        ).ingest(sms())
        assertEquals(IngestOutcome.OTP, result)
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `a stage that throws gives ERROR and an event that names the stage and holds no message text`() = runBlocking {
        val result = pipeline(
            stage(IngestStage.PARSE) { error("boom $secret") },
        ).ingest(sms())

        assertEquals(IngestOutcome.ERROR, result)
        val event = events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().single()
        assertEquals(IngestStage.PARSE, event.stage)
        val everything = events.joinToString { it.code + it.detail().entries.joinToString() }
        assertFalse(everything.contains(secret), "the exception message must not reach the log")
        assertTrue(everything.contains("PARSE"))
    }

    @Test
    fun `processing carries on with the next message after an error`() = runBlocking {
        val count = AtomicInteger()
        val p = pipeline(
            stage(IngestStage.PARSE) { ctx ->
                if (count.incrementAndGet() == 1) error("first fails") else stop(IngestOutcome.TXN_CREATED).also { checkNotNull(ctx.message) }
            },
        )
        assertEquals(IngestOutcome.ERROR, p.ingest(sms("one")))
        assertEquals(IngestOutcome.TXN_CREATED, p.ingest(sms("two")))
    }

    @Test
    fun `cancellation is not an error and passes through`() {
        val p = pipeline(stage(IngestStage.PARSE) { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { runBlocking { p.ingest(sms()) } }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a chain that never stops is reported as an error`() = runBlocking {
        assertEquals(IngestOutcome.ERROR, pipeline(stage(IngestStage.PARSE) { StageResult.Continue(it) }).ingest(sms()))
        assertEquals(IngestOutcome.ERROR, pipeline().ingest(sms()))
    }

    @Test
    fun `messages are handled one at a time`() = runBlocking {
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val p = pipeline(
            stage(IngestStage.PERSIST) {
                peak.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(20)
                inside.decrementAndGet()
                stop(IngestOutcome.TXN_CREATED)
            },
        )
        List(8) { async(Dispatchers.Default) { p.ingest(sms("m$it")) } }.awaitAll()
        assertEquals(1, peak.get())
    }

    @Test
    fun `a second message waits for the first rather than overlapping it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val p = pipeline(
            stage(IngestStage.PERSIST) { ctx ->
                val name = (ctx.message as RawMessage.Sms).body
                order += "start $name"
                if (name == "first") gate.await()
                order += "end $name"
                stop(IngestOutcome.TXN_CREATED)
            },
        )
        val first = async(Dispatchers.Default) { p.ingest(sms("first")) }
        while (order.isEmpty()) delay(1)
        val second = async(Dispatchers.Default, CoroutineStart.DEFAULT) { p.ingest(sms("second")) }
        delay(30)
        assertEquals(listOf("start first"), order.toList())
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("start first", "end first", "start second", "end second"), order.toList())
    }

    // Requirement 7.1: one Audit_Log read entry per batch

    @Test
    fun `a batch writes one audit entry with the number of messages read and counts each outcome`() = runBlocking {
        val p = pipeline(
            stage(IngestStage.PERSIST) { ctx ->
                when ((ctx.message as RawMessage.Sms).body) {
                    "otp" -> stop(IngestOutcome.OTP)
                    "bad" -> error("x")
                    else -> stop(IngestOutcome.TXN_CREATED)
                }
            },
        )
        val result = p.ingestBatch(DataSource.SMS, listOf(sms("a"), sms("otp"), sms("bad"), sms("b")))

        assertEquals(mapOf(IngestOutcome.TXN_CREATED to 2, IngestOutcome.OTP to 1, IngestOutcome.ERROR to 1), result.outcomes)
        assertEquals(4, result.read)
        assertEquals(2, result[IngestOutcome.TXN_CREATED])
        assertEquals(0, result[IngestOutcome.REVIEW_QUEUED])
        assertEquals(listOf(RecordingAudit.Read(DataSource.SMS, time.now(), 4, true)), audit.reads)
    }

    @Test
    fun `an empty batch and a failed read are still recorded`() = runBlocking {
        val p = pipeline(stage(IngestStage.PERSIST) { stop(IngestOutcome.TXN_CREATED) })
        p.ingestBatch(DataSource.SMS, emptyList())
        p.ingestBatch(DataSource.APP_NOTIFICATIONS, emptyList(), readSucceeded = false)
        assertEquals(
            listOf(
                RecordingAudit.Read(DataSource.SMS, time.now(), 0, true),
                RecordingAudit.Read(DataSource.APP_NOTIFICATIONS, time.now(), 0, false),
            ),
            audit.reads,
        )
    }

    @Test
    fun `a failing audit write is logged and does not lose the batch result`() = runBlocking {
        audit.failure = IllegalStateException("disk full $secret")
        val p = pipeline(stage(IngestStage.PERSIST) { stop(IngestOutcome.TXN_CREATED) })
        val result = p.ingestBatch(DataSource.SMS, listOf(sms("a")))
        assertEquals(1, result[IngestOutcome.TXN_CREATED])
        assertEquals(IngestStage.AUDIT, events.filterIsInstance<DiagnosticEvent.IngestStageFailed>().single().stage)
        assertFalse(events.joinToString { it.detail().toString() }.contains(secret))
    }

    // Requirement 3.2, 3.5: nothing prints the text

    @Test
    fun `messages and contexts never print their content`() {
        val notification = RawMessage.Notification("com.example.upiapp", "k", secret, secret, at)
        val context = IngestContext(MessageMeta(SourceKind.SMS, "BANKA", at), sms())
        listOf(sms().toString(), notification.toString(), context.toString()).forEach { assertFalse(it.contains(secret), it) }
    }

    @Test
    fun `a notification's text is its title and text joined, and an empty one has no content`() {
        assertEquals("Paid Rs 5", RawMessage.Notification("p", "k", "Paid", "Rs 5", at).text)
        assertEquals("Rs 5", RawMessage.Notification("p", "k", "", "Rs 5", at).text)
        assertFalse(RawMessage.Notification("p", "k", " ", "", at).hasContent)
        assertTrue(RawMessage.Notification("p", "k", "", "x", at).hasContent)
    }
}

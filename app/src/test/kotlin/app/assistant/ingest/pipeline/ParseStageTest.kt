package app.assistant.ingest.pipeline

import app.assistant.data.money.AssetParserRegistry
import app.assistant.domain.model.SourceKind
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

/** After the parser, a message's text is gone unless the message is unreadable and heads for redaction (design: ingest pipeline). */
class ParseStageTest {
    private val registry = AssetParserRegistry(AssetParserRegistry.FILES) { File("src/main/assets/parser_registry/$it").inputStream() }
    private val stage = ParseStage(registry, FakeTimeSource())
    private val at: Instant = Instant.parse("2025-01-05T08:36:00Z")

    private fun run(body: String): IngestContext {
        val message = RawMessage.Sms("AX-BANKA-S", body, at, at)
        val ctx = IngestContext(MessageMeta(SourceKind.SMS, message.origin, at), message)
        return (runBlocking { stage.process(ctx) } as StageResult.Continue).ctx
    }

    @Test
    fun `text is dropped once a message is parsed, an OTP or a failed payment`() {
        listOf(
            "Rs 250.00 debited from a/c 1234 on 05-01-2025 14:05 at Swiggy. Avl Bal Rs 10,000.00",
            "482913 is your OTP for a transaction of Rs 250.00 at Swiggy. Valid for 10 mins.",
            "Your transaction of Rs 250.00 at Swiggy on 05-01-2025 has failed/declined. Ref XYZ987.",
        ).forEach { body ->
            val ctx = run(body)
            assertNotNull(ctx.parse)
            assertNull(ctx.message, "text must not travel past the parser")
        }
    }

    @Test
    fun `text is kept only for an unreadable message`() {
        val ctx = run("Your statement is ready, nothing in it a format knows")
        assertTrue(ctx.parse is app.assistant.parse.money.ParseResult.Unknown)
        assertNotNull(ctx.message)
    }
}

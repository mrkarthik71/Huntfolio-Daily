package app.assistant.ingest.pipeline

import app.assistant.domain.error.Outcome
import app.assistant.domain.port.IngestLedger
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.RefState
import app.assistant.domain.port.SenderList
import app.assistant.domain.port.TimeSource
import app.assistant.ingest.SenderMatcher
import app.assistant.parse.money.MessageParser
import app.assistant.parse.money.ParseResult
import app.assistant.parse.money.ParserRegistry
import app.assistant.security.log.IngestStage
import app.assistant.security.refs.SourceRefFactory

/** Drops anything from an origin the User has not listed, keeping and logging nothing of it (Requirements 3.3, 26.3, 27.3, 27.6). */
class SenderFilterStage(private val senders: SenderList, private val apps: MoneyAppList) : PipelineStage {
    override val name = IngestStage.SENDER_FILTER

    override suspend fun process(ctx: IngestContext): StageResult {
        val listed = when (val message = checkNotNull(ctx.message)) {
            is RawMessage.Sms -> SenderMatcher.matches(message.origin, senders.entries())
            is RawMessage.Notification -> message.hasContent && apps.contains(message.packageName)
        }
        return if (listed) StageResult.Continue(ctx) else StageResult.Stop(IngestOutcome.DROPPED_UNLISTED)
    }
}

/** Computes the Source_Ref: the idempotency key and tombstone key for the message (ADR 0006). */
class SourceRefStage(private val refs: SourceRefFactory) : PipelineStage {
    override val name = IngestStage.SOURCE_REF

    override suspend fun process(ctx: IngestContext): StageResult {
        val made = when (val message = checkNotNull(ctx.message)) {
            is RawMessage.Sms -> refs.sms(message.origin, message.sentAt, message.body)
            is RawMessage.Notification -> refs.notification(message.packageName, message.key, message.title, message.body)
        }
        // The key being unavailable is an error for this message only; nothing about the message goes into the failure.
        check(made is Outcome.Ok) { "Source_Ref key unavailable" }
        return StageResult.Continue(ctx.copy(ref = made.value))
    }
}

/** Stops a message already handled (Requirement 29.3) or one whose Source_Ref was deleted or dismissed (Requirements 30.4, 32.7). */
class ProcessedRefStage(private val ledger: IngestLedger) : PipelineStage {
    override val name = IngestStage.PROCESSED_REF

    override suspend fun process(ctx: IngestContext): StageResult = when (ledger.state(checkNotNull(ctx.ref))) {
        RefState.NEW -> StageResult.Continue(ctx)
        RefState.PROCESSED -> StageResult.Stop(IngestOutcome.DUPLICATE_REF)
        RefState.TOMBSTONED -> StageResult.Stop(IngestOutcome.TOMBSTONED)
    }
}

/**
 * Reads the message with the Parser_Registry. The parser classifies an OTP before it tries any other format (Requirement
 * 28.3), so this one pass also does the design's "OTP?" check. From here on the text is dropped unless the message is
 * unreadable and heads for the Review_Queue.
 */
class ParseStage(private val registry: ParserRegistry, private val time: TimeSource) : PipelineStage {
    override val name = IngestStage.PARSE

    override suspend fun process(ctx: IngestContext): StageResult {
        val message = checkNotNull(ctx.message)
        val result = MessageParser.parse(message.text, message.origin, message.receivedAt, time.zone(), registry)
        return StageResult.Continue(ctx.copy(parse = result, message = if (result is ParseResult.Unknown) message else null))
    }
}

/** Discards an OTP, recording only its Source_Ref so a catch-up read does not parse it again (Requirement 3.4; design notes). */
class OtpStage(private val ledger: IngestLedger, private val time: TimeSource) : PipelineStage {
    override val name = IngestStage.OTP

    override suspend fun process(ctx: IngestContext): StageResult {
        if (ctx.parse !is ParseResult.Otp) return StageResult.Continue(ctx)
        ledger.markProcessed(checkNotNull(ctx.ref), OUTCOME_OTP, time.now())
        return StageResult.Stop(IngestOutcome.OTP)
    }

    private companion object {
        const val OUTCOME_OTP = "otp"
    }
}

/** A failed, declined or cancelled payment creates nothing and changes nothing (Requirement 28.11). */
class FailedPaymentStage(private val ledger: IngestLedger, private val time: TimeSource) : PipelineStage {
    override val name = IngestStage.FAILED_PAYMENT

    override suspend fun process(ctx: IngestContext): StageResult {
        if (ctx.parse !is ParseResult.FailedPayment) return StageResult.Continue(ctx)
        ledger.markProcessed(checkNotNull(ctx.ref), OUTCOME_FAILED, time.now())
        return StageResult.Stop(IngestOutcome.FAILED_PAYMENT)
    }

    private companion object {
        const val OUTCOME_FAILED = "failed_payment"
    }
}

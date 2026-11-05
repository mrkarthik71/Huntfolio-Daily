package app.assistant.security.log

import app.assistant.domain.model.DataSource

/**
 * A diagnostic record that cannot carry user content. Every field is an enum, a count,
 * a duration, a Bank_Format ID or a job name (Requirements 3.5, 7.5, 9.2). There is
 * deliberately no event type with a free-text String field.
 */
sealed interface DiagnosticEvent {
    /** Stable machine-readable code, for example `db.open_failed`. */
    val code: String

    /** Key/value details; values are enum names and numbers only. */
    fun detail(): Map<String, String>

    data class StartupUnlock(val outcome: UnlockOutcome) : DiagnosticEvent {
        override val code = "startup.unlock"
        override fun detail() = mapOf("outcome" to outcome.name)
    }

    data class DatabaseOpenFailed(val error: ErrorFingerprint) : DiagnosticEvent {
        override val code = "db.open_failed"
        override fun detail() = error.detail()
    }

    data class SourceRead(val source: DataSource, val items: Int, val success: Boolean) : DiagnosticEvent {
        override val code = "source.read"
        override fun detail() = mapOf("source" to source.name, "items" to items.toString(), "success" to success.toString())
    }

    data class JobRun(val job: JobName, val durationMs: Long, val success: Boolean) : DiagnosticEvent {
        override val code = "job.run"
        override fun detail() = mapOf("job" to job.name, "ms" to durationMs.toString(), "success" to success.toString())
    }

    data class ParserMiss(val bankFormat: BankFormatId?) : DiagnosticEvent {
        override val code = "parser.miss"
        override fun detail() = mapOf("format" to (bankFormat?.value ?: "none"))
    }

    data class ErrorCaught(val component: Component, val error: ErrorFingerprint) : DiagnosticEvent {
        override val code = "error.caught"
        override fun detail() = mapOf("component" to component.name) + error.detail()
    }

    /** A pipeline stage threw while handling one Money_Message; the message and its text are not part of the event (Requirement 28.5). */
    data class IngestStageFailed(val stage: IngestStage, val error: ErrorFingerprint) : DiagnosticEvent {
        override val code = "ingest.stage_failed"
        override fun detail() = mapOf("stage" to stage.name) + error.detail()
    }
}

/** The stages of the ingest pipeline, used to say where a message failed. */
enum class IngestStage { SENDER_FILTER, SOURCE_REF, PROCESSED_REF, PARSE, OTP, FAILED_PAYMENT, REVIEW_QUEUE, BILL_PROPOSAL, PERSIST, HOOKS, AUDIT, QUALITY }

enum class UnlockOutcome { OPENED, KEYSTORE_UNAVAILABLE, KEY_INVALIDATED, BLOB_CORRUPT, DATABASE_ERROR }

/** Named background jobs; later phases add their workers here. */
enum class JobName { MORNING_BRIEF, EVENING_REVIEW, MAINTENANCE_DAILY, CALENDAR_REFRESH, SMS_CATCHUP, SMS_HISTORY_IMPORT, MAIL_SYNC, HEALTH_CHECK, AI_REQUEST }

/** App areas, used to say where an error was caught. */
enum class Component { STARTUP, DATABASE, KEYS, SETTINGS, NETWORK, NOTIFY, INGEST, AI, MAIL, BACKUP, UI }

/** A Parser_Registry Bank_Format ID. Restricted to a short identifier so it cannot hold message text. */
@JvmInline
value class BankFormatId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Bank_Format ID must match ${PATTERN.pattern}" }
    }

    companion object {
        private val PATTERN = Regex("[a-z0-9_.-]{1,64}")
    }
}

/**
 * An exception reduced to its class name and stack frames. The exception message is dropped
 * because messages (for example from a parser or SQLite) can contain user data.
 */
data class ErrorFingerprint(val exceptionClass: String, val frames: List<String>, val causeClass: String?) {
    fun detail(): Map<String, String> = buildMap {
        put("exception", exceptionClass)
        causeClass?.let { put("cause", it) }
        put("frames", frames.joinToString("|"))
    }

    companion object {
        private const val MAX_FRAMES = 8

        fun of(t: Throwable): ErrorFingerprint = ErrorFingerprint(
            exceptionClass = t.javaClass.name,
            frames = t.stackTrace.take(MAX_FRAMES).map { "${it.className}.${it.methodName}:${it.lineNumber}" },
            causeClass = t.cause?.javaClass?.name,
        )
    }
}

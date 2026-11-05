package app.assistant.domain.engine.quality

import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.model.SourceRef
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/** How the Message_Parser treated one Money_Message. An OTP_Message is never counted (Requirement 36.1). */
enum class ParseOutcomeKind {
    DEBIT,
    CREDIT,
    BILL,
    FAILED_PAYMENT,

    /** Sent to the Review_Queue because no Bank_Format could read it. */
    REVIEW,

    /** Ignored by the Quality_Monitor. */
    OTP,
}

/** What the Quality_Monitor stores per origin and day. Counts only: never message text (Requirement 36.4). */
enum class QualityMetric { PARSED, REVIEW, CORRECTED }

/** The counters of one Message_Origin summed over a period. */
data class QualityTotals(val parsed: Int, val review: Int, val corrected: Int) {
    companion object {
        val EMPTY = QualityTotals(0, 0, 0)
    }
}

/** The parse accuracy to show: a percentage to one decimal place, or "no data" (Requirement 36.2). */
sealed interface AccuracyValue {
    data object NoData : AccuracyValue

    data class Percent(val value: BigDecimal) : AccuracyValue
}

/** The origin and Received_Time of a stored message, so a correction is credited to the day it arrived. */
data class MessageReceipt(val origin: MessageOrigin, val receivedDay: LocalDate)

/** Where the counters live. The Room implementation keeps them in `quality_counter`; tests use a map. */
interface QualityStore {
    suspend fun add(origin: MessageOrigin, day: LocalDate, metric: QualityMetric, count: Int)

    /** Remembers that [ref] was corrected. Returns true the first time, false when it was already counted. */
    suspend fun markCorrected(ref: SourceRef): Boolean

    /** The origin and Received_Time day of a message that was parsed into a Transaction, or null when none is stored. */
    suspend fun receipt(ref: SourceRef): MessageReceipt?

    fun totals(origin: MessageOrigin, from: LocalDate, to: LocalDate): Flow<QualityTotals>

    suspend fun totalsNow(origin: MessageOrigin, from: LocalDate, to: LocalDate): QualityTotals
}

interface QualityMonitor {
    /** Counts one Money_Message for [origin] on the day it was received. OTP_Messages are ignored. */
    suspend fun recordParse(origin: MessageOrigin, day: LocalDate, outcome: ParseOutcomeKind)

    /** Counts a correction of the message's Transaction once per message, on the day of its original Received_Time. */
    suspend fun recordCorrection(sourceRef: SourceRef)

    /** The accuracy over today and the 29 days before it, by Received_Time (Requirement 36.2). */
    fun accuracy(origin: MessageOrigin, today: LocalDate): Flow<AccuracyValue>
}

object QualityMath {
    const val WINDOW_DAYS = 30L

    private const val PERCENT = 100L
    private const val DECIMALS = 1

    /**
     * (parsed − corrected) ÷ (parsed + review) × 100 rounded to 1 decimal place, or null when no message was counted
     * ("no data"). Done in exact decimal arithmetic.
     */
    fun accuracy(parsed: Int, corrected: Int, review: Int): BigDecimal? {
        val messages = parsed.toLong() + review.toLong()
        if (messages == 0L) return null
        val good = BigDecimal.valueOf(parsed.toLong() - corrected.toLong()).multiply(BigDecimal.valueOf(PERCENT))
        return good.divide(BigDecimal.valueOf(messages), DECIMALS, RoundingMode.HALF_UP)
    }

    fun valueOf(totals: QualityTotals): AccuracyValue =
        accuracy(totals.parsed, totals.corrected, totals.review)?.let { AccuracyValue.Percent(it) } ?: AccuracyValue.NoData

    /** The first day of the 30-day window that ends on [today]. */
    fun windowStart(today: LocalDate): LocalDate = today.minusDays(WINDOW_DAYS - 1)
}

class DefaultQualityMonitor(private val store: QualityStore, private val time: TimeSource) : QualityMonitor {
    override suspend fun recordParse(origin: MessageOrigin, day: LocalDate, outcome: ParseOutcomeKind) {
        val metric = when (outcome) {
            ParseOutcomeKind.DEBIT, ParseOutcomeKind.CREDIT, ParseOutcomeKind.BILL, ParseOutcomeKind.FAILED_PAYMENT -> QualityMetric.PARSED
            ParseOutcomeKind.REVIEW -> QualityMetric.REVIEW
            ParseOutcomeKind.OTP -> return
        }
        store.add(normalised(origin), day, metric, 1)
    }

    override suspend fun recordCorrection(sourceRef: SourceRef) {
        val receipt = store.receipt(sourceRef) ?: return
        if (store.markCorrected(sourceRef)) store.add(normalised(receipt.origin), receipt.receivedDay, QualityMetric.CORRECTED, 1)
    }

    override fun accuracy(origin: MessageOrigin, today: LocalDate): Flow<AccuracyValue> =
        store.totals(normalised(origin), QualityMath.windowStart(today), today).map { QualityMath.valueOf(it) }

    /** The accuracy for the day the clock shows now. */
    fun accuracyToday(origin: MessageOrigin): Flow<AccuracyValue> = accuracy(origin, time.today())

    /** A Message_Origin is compared ignoring letter case and surrounding space, so its counters share one key. */
    private fun normalised(origin: MessageOrigin): MessageOrigin = MessageOrigin(origin.value.trim().lowercase(Locale.ROOT))
}

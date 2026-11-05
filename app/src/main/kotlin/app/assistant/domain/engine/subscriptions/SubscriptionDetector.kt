package app.assistant.domain.engine.subscriptions

import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import java.time.LocalDate
import kotlin.math.abs

/**
 * Finds repeating charges in debit Transactions (Requirement 37, Glossary: Subscription_Detector). Pure: it is given the
 * charges and the Subscriptions that already exist, and returns proposals. For each Merchant and interval it finds the
 * LONGEST Recurring_Pattern, skips any Merchant+interval that already has a Subscription in ANY status (37.3 remembers
 * rejections, 37.5 idempotent), and needs at least Min_Occurrences charges. Yearly cost and charge matching live here too.
 */
object SubscriptionDetector {

    /** Yearly cost in exact integer paise: amount × intervals per year (Requirement 38.2). Overflow throws, never wraps. */
    fun yearlyCost(amount: Paise, interval: SubscriptionInterval): Paise = amount * interval.perYear

    /** Total yearly cost of the active Subscriptions only (Requirement 38.3); ₹0 when none. */
    fun totalYearlyCost(active: List<SubscriptionSnapshot>): Paise =
        Paise.sum(active.filter { it.status == SubscriptionStatus.ACTIVE }.map { yearlyCost(it.amount, it.interval) })

    /**
     * Proposals for every Merchant+interval whose charges form a Recurring_Pattern and that has no Subscription yet
     * (Requirement 37.1, 37.3, 37.5). Deterministic and idempotent: the same charges give the same proposals.
     */
    fun detect(charges: List<Charge>, existing: List<SubscriptionSnapshot>, config: DetectionConfig = DetectionConfig()): List<DetectionProposal> {
        val taken = existing.map { it.merchantId to it.interval }.toSet()
        val byMerchant = charges.sortedBy { it.date }.groupBy { it.merchantId }
        val proposals = mutableListOf<DetectionProposal>()
        for ((merchantId, merchantCharges) in byMerchant) {
            for (interval in SubscriptionInterval.entries) {
                if (merchantId to interval in taken) continue
                longestPattern(merchantCharges, interval, config)?.let { chain ->
                    proposals += proposalFrom(merchantId, interval, chain)
                }
            }
        }
        // Stable order: by merchant value then interval, so the list never depends on map iteration order.
        return proposals.sortedWith(compareBy({ it.merchantId.value }, { it.interval.ordinal }))
    }

    /**
     * How one new debit lines up against the active Subscriptions (Requirement 38.4-38.5): the one with the nearest next
     * due date within Charge_Window. Within tolerance -> Advance; out of tolerance -> PriceChange; otherwise None.
     */
    fun matchCharge(charge: Charge, active: List<SubscriptionSnapshot>, config: DetectionConfig = DetectionConfig()): ChargeMatch {
        val candidate = active
            .filter { it.status == SubscriptionStatus.ACTIVE && it.merchantId == charge.merchantId }
            .filter { abs(daysBetween(it.nextDue, charge.date)) <= config.chargeWindowDays }
            .minByOrNull { abs(daysBetween(it.nextDue, charge.date)) }
            ?: return ChargeMatch.None
        val id = candidate.id ?: return ChargeMatch.None
        return if (withinTolerance(candidate.amount, charge.amount, config.amountTolerancePercent)) {
            ChargeMatch.Advance(id, DueDateRule.next(candidate.nextDue, candidate.interval, candidate.anchorDay))
        } else {
            ChargeMatch.PriceChange(id, candidate.amount, charge.amount, charge.date)
        }
    }

    /**
     * Active Subscriptions whose expected charge was not seen by the end of the Charge_Window after the next due date
     * (Requirement 38.7). Caller advances the due date and raises a "not seen" Suggestion.
     */
    fun missedCharges(active: List<SubscriptionSnapshot>, today: LocalDate, config: DetectionConfig = DetectionConfig()): List<SubscriptionSnapshot> =
        active.filter { it.status == SubscriptionStatus.ACTIVE && daysBetween(it.nextDue, today) > config.chargeWindowDays }

    // --- internals ---

    private fun proposalFrom(merchantId: MerchantId, interval: SubscriptionInterval, chain: List<Charge>): DetectionProposal {
        val latest = chain.last()
        val anchorDay = chain.first().date.dayOfMonth
        return DetectionProposal(
            merchantId = merchantId,
            amount = latest.amount,
            interval = interval,
            anchorDay = anchorDay,
            matchingCount = chain.size,
            nextDue = DueDateRule.next(latest.date, interval, anchorDay),
            chargeIds = chain.map { it.transactionId },
        )
    }

    /**
     * The longest chain of charges (date-ascending) where each charge after the first lands within Charge_Window of one
     * interval after the previous, and each amount is within tolerance of the latest. Null when shorter than Min_Occurrences.
     */
    private fun longestPattern(sorted: List<Charge>, interval: SubscriptionInterval, config: DetectionConfig): List<Charge>? {
        var best: List<Charge>? = null
        for (start in sorted.indices) {
            val chain = mutableListOf(sorted[start])
            for (i in start + 1 until sorted.size) {
                val candidate = sorted[i]
                val prev = chain.last()
                val expected = DueDateRule.next(prev.date, interval, chain.first().date.dayOfMonth)
                if (abs(daysBetween(expected, candidate.date)) <= config.chargeWindowDays &&
                    withinTolerance(chain.last().amount, candidate.amount, config.amountTolerancePercent)
                ) {
                    chain += candidate
                }
            }
            if (chain.size >= config.minOccurrences && (best == null || chain.size > best.size)) best = chain.toList()
        }
        return best
    }

    /** True when [actual] is within [percent]% of [reference]. Uses integer paise arithmetic, no floating point. */
    private fun withinTolerance(reference: Paise, actual: Paise, percent: Int): Boolean {
        val diff = abs(reference.value - actual.value)
        return diff * 100 <= reference.value * percent
    }

    private fun daysBetween(from: LocalDate, to: LocalDate): Long = to.toEpochDay() - from.toEpochDay()
}

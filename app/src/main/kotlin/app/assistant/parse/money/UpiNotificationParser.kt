package app.assistant.parse.money

import app.assistant.domain.model.Paise
import java.time.LocalDateTime

/**
 * Reads a payment message that no exact Bank_Format matches: a bank SMS from a listed sender, or a
 * notification from a payment app. It knows the common Indian phrasings ("Rs.500.00 debited from A/c
 * XX1234 … to VPA …", "Rs 1,200 spent on Card x1234 at AMAZON", "A/C X1234 debited by 500.0 … trf to
 * RAVI", "Your A/c XX1234 is credited with INR 5,000", "Paid ₹250 to Ravi", "You received ₹500 from
 * Priya", "Payment of ₹99 failed"). Rules only, and strict: it needs an amount AND a completed
 * debit/credit/failed wording, and refuses requests, reminders, bills and statements that are only
 * due, offers, cashback, links and instruction-like text, so anything doubtful goes to the
 * Review_Queue instead of being guessed. The text is untrusted data; only extracted fields leave here.
 */
object UpiNotificationParser {
    const val FORMAT_DEBIT = "generic.debit.v1"
    const val FORMAT_CREDIT = "generic.credit.v1"
    const val FORMAT_FAILED = "generic.failed.v1"

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val NUM = "([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)"
    private val currencyAmount = Regex("(?:₹|rs\\.?|inr)\\s?$NUM", opt)

    /** SBI style without a currency word: "debited by 500.0", "credited by 1000". */
    private val verbAmount = Regex("\\b(?:debited|credited)\\s+(?:by|with|for)\\s+(?:₹|rs\\.?|inr)?\\s*$NUM", opt)

    /** Amounts right after these words are a balance or a limit, never the payment. */
    private val notThePayment = Regex("(?:bal(?:ance)?|limit|avl|available|outstanding|min(?:imum)?(?: amt| amount)?|total due)\\W{0,12}$", opt)

    private val suspicious = Regex(
        "(https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|in|net|org|xyz|link|ly|io)/|\\b(?:ignore|disregard|forget)\\b.{0,40}\\b(?:instruction|rule|previous)|" +
            "\\byou\\s+are\\s+now\\b|\\b(?:system|assistant|developer)\\s*:|\\badmin mode\\b)",
        opt,
    )

    /** Not a completed payment: requests, reminders, things only due or scheduled, promotions, rewards, logins. */
    private val skip = Regex(
        "\\b(request(?:ed|s)?|requesting|collect request|remind(?:er)?|pay now|offer|cashback|reward|scratch card|coupon|" +
            "voucher|win|won|otp|verification|login|kyc|autopay set|mandate created|will be (?:debited|deducted|charged)|" +
            "to be debited|scheduled|upcoming|is due|due on|due by|due date|payment due|bill generated|statement (?:is )?(?:ready|generated)|" +
            "min(?:imum)? (?:amt|amount) due|total (?:amt|amount) due|pre-?approved|eligible)\\b",
        opt,
    )
    private val failed = Regex("\\b(failed|declined|unsuccessful|could not be completed|was not successful|transaction reversed due to)\\b", opt)
    private val debit = Regex(
        "\\b(debited|spent|withdrawn|deducted|paid|sent|you paid|purchase(?:d)?|txn of|used for|money sent|transferred to|" +
            "payment (?:of [^.]{0,30})?(?:successful|done|completed))\\b",
        opt,
    )
    private val credit = Regex("\\b(credited|received|deposited|got|added to your (?:bank|account))\\b", opt)
    private val refundWords = Regex("\\b(refund(?:ed)?|reversed|reversal)\\b", opt)

    private const val END_WORDS = "on|via|using|from|for|at|successfully|is|was|has|with|upi|ref|refno|utr|txn|avl|bal|not you|info|if"
    private const val END = "(?=\\s+(?:$END_WORDS)\\b|[.,;!|(](?:\\s|$)|[;(]|$)"
    private val toWho = Regex("\\b(?:to\\s+vpa|trf to|transferred to|to)\\s+([A-Za-z0-9@._\\- ]{2,40}?)$END", opt)
    private val atWho = Regex("\\bat\\s+([A-Za-z0-9@._&'\\- ]{2,40}?)$END", opt)
    private val creditedWho = Regex(";\\s*([A-Za-z0-9@._&'\\- ]{2,40}?)\\s+credited\\b", opt)
    private val fromWho = Regex("\\b(?:from|by)\\s+([A-Za-z0-9@._\\- ]{2,40}?)" + END.replace("from|", "to|in|"), opt)
    private val ref = Regex(
        "\\b(?:upi\\s*(?:ref(?:erence)?|txn|transaction)?\\s*(?:no|id|#)?|utr|ref(?:erence)?\\s*(?:no|id)?|refno|txn\\s*id|upi)" +
            "\\.?\\s*[:#/-]?\\s*([0-9]{9,14})\\b",
        opt,
    )
    private val acct = Regex("\\b(?:a/c|acct|account|card)\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with)?\\s*)?[x*]*([0-9]{3,4})\\b", opt)
    private val balance = Regex("\\b(?:avl\\.?\\s*bal(?:ance)?|available balance|bal(?:ance)?)\\s*(?:is|:)?\\s*(?:₹|rs\\.?|inr)?\\s*$NUM", opt)
    private val selfWords = Regex("^(?:your|you|a/c|ac|account|card|bank|vpa|upi|the)\\b", opt)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // each early return rejects one kind of message that is not a completed payment
    fun parse(text: String, at: LocalDateTime): ParseResult? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || suspicious.containsMatchIn(t)) return null
        val paise = paymentAmount(t) ?: return null
        if (failed.containsMatchIn(t)) return ParseResult.FailedPayment(FORMAT_FAILED)
        if (skip.containsMatchIn(t)) return null
        if (paise <= 0) return null
        val isRefund = refundWords.containsMatchIn(t)
        // Whichever wording comes first decides: "debited from A/c …; RAVI credited" is a debit.
        val d = debit.find(t)?.range?.first ?: Int.MAX_VALUE
        val c = credit.find(t)?.range?.first ?: Int.MAX_VALUE
        if (d == Int.MAX_VALUE && c == Int.MAX_VALUE) return null
        val isCredit = isRefund || c < d
        val who = if (isCredit) {
            fromWho.find(t)?.groupValues?.get(1)
        } else {
            (toWho.find(t) ?: atWho.find(t) ?: creditedWho.find(t))?.groupValues?.get(1)
        }?.trim()?.trimEnd('.', ',')?.takeIf { it.length >= 2 && currencyAmount.find(it) == null && !selfWords.containsMatchIn(it) }
        val message = ParsedMessage(
            type = if (isCredit) MessageType.CREDIT else MessageType.DEBIT,
            isReversal = isRefund,
            amount = Paise(paise),
            at = at,
            merchantOrVpa = who?.take(MAX_NAME),
            account4 = acct.find(t)?.groupValues?.get(1)?.takeLast(ACCOUNT_DIGITS),
            reference = ref.find(t)?.groupValues?.get(1),
            balance = balance.find(t)?.groupValues?.get(1)?.let(::toPaise)?.let(::Paise),
        )
        return ParseResult.Parsed(message, if (isCredit) FORMAT_CREDIT else FORMAT_DEBIT)
    }

    /** The first currency amount that is not a balance or limit; else "debited by 500.0". */
    private fun paymentAmount(t: String): Long? {
        val first = currencyAmount.findAll(t).firstOrNull { m ->
            !notThePayment.containsMatchIn(t.substring(maxOf(0, m.range.first - LOOK_BACK), m.range.first))
        }
        val raw = first?.groupValues?.get(1) ?: verbAmount.find(t)?.groupValues?.get(1) ?: return null
        return toPaise(raw)
    }

    private fun toPaise(s: String): Long? {
        val parts = s.replace(",", "").split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return rupees * HUNDRED + frac
    }

    private const val HUNDRED = 100L
    private const val MAX_NAME = 40
    private const val LOOK_BACK = 24
    private const val ACCOUNT_DIGITS = 4
}

package app.assistant.parse.money

import app.assistant.domain.model.Paise
import java.time.LocalDateTime

/**
 * Reads a payment notification from any UPI app when no exact Bank_Format matches it: the common
 * phrasings ("Paid ₹250 to Ravi", "₹500 sent to …", "You received ₹1,200 from …", "Payment of ₹99
 * failed", "UPI Ref No 123456789012"). Rules only, and strict: it needs a rupee amount AND a clear
 * paid/received/failed verb, and it refuses requests, reminders, offers, cashback and login alerts,
 * so anything doubtful still goes to the Review_Queue instead of being guessed. The text is untrusted
 * data; only the extracted fields leave this function.
 */
object UpiNotificationParser {
    const val FORMAT_DEBIT = "upi.generic.debit.v1"
    const val FORMAT_CREDIT = "upi.generic.credit.v1"
    const val FORMAT_FAILED = "upi.generic.failed.v1"

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val AMT = "(?:₹|rs\\.?|inr)\\s?([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)"
    private val amount = Regex(AMT, opt)

    /** Not a completed payment: requests, reminders, promotions, rewards and login alerts. */
    private val skip = Regex(
        "\\b(request(?:ed|s)?|requesting|collect request|remind(?:er)?|pay now|offer|cashback|reward|scratch card|coupon|" +
            "voucher|win|won|otp|verification|login|kyc|autopay set|mandate created)\\b",
        opt,
    )
    private val failed = Regex("\\b(failed|declined|unsuccessful|could not be completed|was not successful)\\b", opt)
    private val debit = Regex(
        "\\b(paid|sent|you paid|payment (?:of [^.]{0,30})?(?:successful|done|completed)|money sent|debited|transferred to)\\b",
        opt,
    )
    private val credit = Regex("\\b(received|credited|got|added to your (?:bank|account))\\b", opt)
    private val refundWords = Regex("\\b(refund(?:ed)?|reversed|reversal)\\b", opt)

    private val toWho =
        Regex("\\bto\\s+([A-Za-z0-9@._\\- ]{2,40}?)(?=\\s+(?:on|via|using|from|for|at|successfully|is|was|has|with|upi|ref|utr|txn)\\b|[.,!|](?:\\s|$)|$)", opt)
    private val fromWho =
        Regex(
            "\\bfrom\\s+([A-Za-z0-9@._\\- ]{2,40}?)(?=\\s+(?:on|via|using|to|in|for|at|successfully|is|was|has|with|upi|ref|utr|txn)\\b|[.,!|](?:\\s|$)|$)",
            opt,
        )
    private val ref =
        Regex(
            "\\b(?:upi\\s*(?:ref(?:erence)?|txn|transaction)?\\s*(?:no|id|#)?|utr|ref(?:erence)?\\s*(?:no|id)?|txn\\s*id)\\.?\\s*[:#-]?\\s*([0-9]{9,14})\\b",
            opt,
        )
    private val acct = Regex("(?:a/c|account|acct|bank)\\s*(?:no\\.?)?\\s*(?:x+|\\*+|ending\\s*(?:with)?\\s*)([0-9]{4})\\b", opt)

    /** Links and instruction-like wording: such a notification is never read loosely; it goes to review. */
    private val suspicious = Regex(
        "(https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|in|net|org|xyz|link|ly|io)/|\\b(?:ignore|disregard|forget)\\b.{0,40}\\b(?:instruction|rule|previous)|" +
            "\\byou\\s+are\\s+now\\b|\\b(?:system|assistant|developer)\\s*:|\\badmin mode\\b)",
        opt,
    )

    @Suppress("ReturnCount") // each early return rejects one kind of notification that is not a completed payment
    fun parse(text: String, at: LocalDateTime): ParseResult? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || suspicious.containsMatchIn(t)) return null
        val amountMatch = amount.find(t) ?: return null
        if (failed.containsMatchIn(t)) return ParseResult.FailedPayment(FORMAT_FAILED)
        if (skip.containsMatchIn(t)) return null
        val paise = toPaise(amountMatch.groupValues[1]) ?: return null
        if (paise <= 0) return null
        val isRefund = refundWords.containsMatchIn(t)
        val isCredit = credit.containsMatchIn(t) && !debit.containsMatchIn(t.substringBefore(" received")) || isRefund
        val isDebit = !isCredit && debit.containsMatchIn(t)
        if (!isCredit && !isDebit) return null
        val who = (if (isCredit) fromWho.find(t) else toWho.find(t))?.groupValues?.get(1)?.trim()?.trimEnd('.', ',')
            ?.takeIf { it.isNotBlank() && amount.find(it) == null }
        val message = ParsedMessage(
            type = if (isCredit) MessageType.CREDIT else MessageType.DEBIT,
            isReversal = isRefund,
            amount = Paise(paise),
            at = at,
            merchantOrVpa = who?.take(MAX_NAME),
            account4 = acct.find(t)?.groupValues?.get(1),
            reference = ref.find(t)?.groupValues?.get(1),
            balance = null,
        )
        return ParseResult.Parsed(message, if (isCredit) FORMAT_CREDIT else FORMAT_DEBIT)
    }

    private fun toPaise(s: String): Long? {
        val parts = s.replace(",", "").split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return rupees * HUNDRED + frac
    }

    private const val HUNDRED = 100L
    private const val MAX_NAME = 40
}

package app.assistant.parse.mail

import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** What a bill or subscription email says: who, how much, and when it is due (or when it renews). */
data class Receipt(val merchant: String, val amountPaise: Long, val dueDate: LocalDate?, val recurring: Boolean, val alreadyPaid: Boolean)

/**
 * Reads bills, statements and subscription receipts from an email's sender, subject and snippet,
 * with rules only (no AI). The text is untrusted data: nothing in it is followed or opened, and
 * only the extracted fields leave this function. Returns null for anything that is not clearly a
 * bill or receipt with an amount in rupees.
 */
object ReceiptParser {
    private val billWords = Regex(
        "\\b(bill|invoice|statement|payment due|amount due|total due|amount payable|due date|renewal|" +
            "renews|subscription|auto-?debit|autopay|e-?mandate|membership|premium due|receipt)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val recurringWords =
        Regex("\\b(subscription|renewal|renews|auto-?renew|membership|monthly plan|annual plan|auto-?debit|autopay|e-?mandate)\\b", RegexOption.IGNORE_CASE)
    private val paidWords =
        Regex(
            "\\b(payment (received|successful)|successfully paid|thank you for your payment|has been paid|paid on|payment confirmation)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val skipWords =
        Regex("\\b(otp|one time password|verification code|offer|cashback|sale|discount|loan offer|pre-?approved)\\b", RegexOption.IGNORE_CASE)
    private val amount = Regex("(?:₹|rs\\.?|inr)\\s?([0-9]{1,3}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val amountLabel =
        Regex("(amount due|total due|amount payable|total amount|minimum due|total|amount|premium)\\s*[:\\-]?\\s*$", RegexOption.IGNORE_CASE)
    private val dueLabel =
        Regex(
            "(?:due (?:on|date|by)|pay by|payable by|renews on|renewal (?:on|date)|next (?:billing|payment) (?:date|on))" +
                "\\s*[:\\-]?\\s*([0-9]{1,2}[ /\\-.][A-Za-z0-9]{1,9}[ /\\-.,]*[0-9]{2,4}|" +
                "[A-Za-z]{3,9} [0-9]{1,2},? [0-9]{4}|[0-9]{1,2} [A-Za-z]{3,9})",
            RegexOption.IGNORE_CASE,
        )

    private val dateFormats: List<DateTimeFormatter> = listOf(
        "d MMM yyyy",
        "d MMMM yyyy",
        "d/M/yyyy",
        "d-M-yyyy",
        "d.M.yyyy",
        "d/M/yy",
        "d-MMM-yyyy",
        "MMM d, yyyy",
        "MMMM d, yyyy",
        "MMM d yyyy",
    )
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }
    private val noYear = listOf("d MMM", "d MMMM").map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    @Suppress("ReturnCount") // each early return rejects one kind of mail that is not a bill
    fun parse(sender: String, subject: String, snippet: String, received: LocalDate): Receipt? {
        val text = "$subject\n$snippet"
        if (skipWords.containsMatchIn(subject)) return null
        if (!billWords.containsMatchIn(text)) return null
        val paise = pickAmount(text) ?: return null
        if (paise <= 0) return null
        val due = dueLabel.find(text)?.groupValues?.get(1)?.let { parseDate(it.trim().trimEnd(',', '.'), received) }
        val recurring = recurringWords.containsMatchIn(text)
        val paid = paidWords.containsMatchIn(text)
        // A paid one-off receipt is not a bill; a paid subscription receipt still tells us it renews.
        if (paid && !recurring) return null
        return Receipt(merchantOf(sender), paise, due, recurring, paid)
    }

    /** The amount right after a label such as "Amount due:", else the first rupee amount. */
    private fun pickAmount(text: String): Long? {
        val all = amount.findAll(text).toList()
        if (all.isEmpty()) return null
        val labelled = all.firstOrNull { m -> amountLabel.containsMatchIn(text.substring(maxOf(0, m.range.first - LABEL_WINDOW), m.range.first)) }
        return toPaise((labelled ?: all.first()).groupValues[1])
    }

    private fun toPaise(s: String): Long? {
        val clean = s.replace(",", "")
        val parts = clean.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return rupees * HUNDRED + frac
    }

    private fun parseDate(s: String, received: LocalDate): LocalDate? {
        val norm = s.replace(Regex("\\s+"), " ").replace(Regex("(\\d)(st|nd|rd|th)\\b"), "$1")
        for (f in dateFormats) runCatching { return LocalDate.parse(norm, f) }
        for (f in noYear) {
            runCatching {
                val md = MonthDay.parse(norm, f)
                val thisYear = md.atYear(received.year)
                return if (thisYear.isBefore(received.minusDays(YEAR_ROLL_GRACE))) md.atYear(received.year + 1) else thisYear
            }
        }
        return null
    }

    /** "Netflix <info@mailer.netflix.com>" → "Netflix"; a bare address → its domain name. */
    fun merchantOf(sender: String): String {
        val name = sender.substringBefore('<').trim().trim('"').takeIf { it.isNotBlank() && '@' !in it }
        if (name != null) return name.take(MAX_NAME)
        val domain = sender.substringAfter('@', "").substringBefore('>').split('.').filter { it.isNotBlank() }
        val core = domain.dropLast(1).lastOrNull { it !in GENERIC } ?: domain.firstOrNull().orEmpty()
        return core.replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { "Bill" }.take(MAX_NAME)
    }

    private val GENERIC = setOf("mail", "mailer", "email", "e", "info", "alerts", "notify", "billing", "co", "in", "com", "noreply")
    private const val HUNDRED = 100L
    private const val LABEL_WINDOW = 24
    private const val MAX_NAME = 60
    private const val YEAR_ROLL_GRACE = 30L
}

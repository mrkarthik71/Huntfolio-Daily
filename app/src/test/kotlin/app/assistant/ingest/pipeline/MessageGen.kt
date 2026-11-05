package app.assistant.ingest.pipeline

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import java.time.Instant

/** A message to send, before it gets its identity (sent time, key) from its position in a sequence. */
internal sealed interface Spec {
    data class Sms(val origin: String, val body: String) : Spec

    data class Notif(val pkg: String, val title: String, val body: String) : Spec
}

/** Gives every spec a distinct identity, so two specs with the same text are still two messages. */
internal fun List<Spec>.build(): List<RawMessage> = mapIndexed { i, spec ->
    when (spec) {
        is Spec.Sms -> RawMessage.Sms(spec.origin, spec.body, MessageGen.RECEIVED.minusSeconds(SENT_BEFORE - i), MessageGen.RECEIVED.plusSeconds(i.toLong()))
        is Spec.Notif -> RawMessage.Notification(spec.pkg, "key-$i", spec.title, spec.body, MessageGen.RECEIVED.plusSeconds(i.toLong()))
    }
}

private const val SENT_BEFORE = 10_000L

/**
 * Messages in the shipped placeholder formats (BankA SMS, one UPI app) and the ways they go wrong. Everything here is
 * synthetic; the dates fall in January 2025, before [RECEIVED].
 */
internal object MessageGen {
    val RECEIVED: Instant = Instant.parse("2025-01-31T12:00:00Z")
    const val UPI_PACKAGE = "com.example.upiapp"
    val MERCHANTS = listOf("Swiggy", "Zomato", "Metro Mart", "Blue Taxi", "Corner Cafe")
    val SMS_SENDERS = listOf("AX-BANKA-S", "VM-BANKA", "BANKA")

    /** The fields of one payment, which any of its messages can show. */
    data class Pay(
        val amount: Long,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val merchant: String,
        val account4: String,
        val reference: String,
        val balance: Long,
    ) {
        val date: String get() = "${two(day)}-01-2025"
        val time: String get() = "${two(hour)}:${two(minute)}"
        val isoDate: String get() = "2025-01-${two(day)}"
        val vpa: String get() = merchant.lowercase().replace(" ", "") + "@icici"

        /** The field values these messages carry, which the store is allowed to keep (Requirement 3.1). */
        val fieldValues: Set<String> get() = setOf(merchant, vpa, reference, account4, money(amount), money(balance), date, time, isoDate)
    }

    fun money(paise: Long) = "${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

    private fun two(n: Int) = n.toString().padStart(2, '0')

    fun debitSms(p: Pay) = "Rs ${money(p.amount)} debited from a/c ${p.account4} on ${p.date} ${p.time} at ${p.merchant}. Avl Bal Rs ${money(p.balance)}"

    fun creditSms(p: Pay) = "Rs ${money(p.amount)} credited to a/c ${p.account4} on ${p.date} ${p.time}. Ref ${p.reference}. Avl Bal Rs ${money(p.balance)}"

    fun failedSms(p: Pay) = "Your transaction of Rs ${money(p.amount)} at ${p.merchant} on ${p.date} has failed/declined. Ref ${p.reference}."

    fun billSms(p: Pay) = "Your BankA Credit Card bill of Rs ${money(p.amount)} is due on ${p.date}. Pay in the BankA app."

    fun otpSms(code: String, p: Pay) = "$code is your OTP for a transaction of Rs ${money(p.amount)} at ${p.merchant}. Valid for 10 mins."

    fun upiDebit(p: Pay) = "Paid Rs ${money(p.amount)} to ${p.vpa} on ${p.isoDate} at ${p.time}. UPI Ref ${p.reference}."

    fun upiCredit(p: Pay) = "Received Rs ${money(p.amount)} from ${p.vpa} on ${p.isoDate} at ${p.time}. UPI Ref ${p.reference}."

    fun upiFailed(p: Pay) = "Payment of Rs ${money(p.amount)} to ${p.vpa} failed. UPI Ref ${p.reference}."

    /** Texts no format matches, or matches with a value the parser must refuse (Requirement 28.4, 28.5). [n] makes each one unique. */
    fun unreadable(kind: Int, p: Pay, n: Int): String = when (kind % UNREADABLE_KINDS) {
        0 -> "Your statement for account holder Asha Menon is ready, ref LN2025STATEMENT$n. Call 9876543210 or open https://bank.example/s$n"
        1 -> "Rs 0.00 debited from a/c ${p.account4} on ${p.date} ${p.time} at Shop$n. Avl Bal Rs ${money(p.balance)}"
        2 -> "Rs ${money(p.amount)} debited from a/c ${p.account4} on 31-02-2025 ${p.time} at Shop$n. Avl Bal Rs ${money(p.balance)}"
        3 -> "Rs ${money(p.amount)} debited from a/c ${p.account4} on ${p.date.take(2)}-01-2030 ${p.time} at Shop$n. Avl Bal Rs ${money(p.balance)}"
        4 -> "Rs ${money(p.amount)}5 debited from a/c ${p.account4} on ${p.date} ${p.time} at Shop$n. Avl Bal Rs ${money(p.balance)}"
        else -> "Weekly summary $n: " + "lorem ipsum ".repeat(OVER_LONG_REPEAT)
    }

    const val UNREADABLE_KINDS = 6
    private const val OVER_LONG_REPEAT = 200

    val pays: Arb<Pay> = arbitrary {
        Pay(
            amount = Arb.long(100L..5_000_000L).bind(),
            day = Arb.int(1..28).bind(),
            hour = Arb.int(0..23).bind(),
            minute = Arb.int(0..59).bind(),
            merchant = Arb.element(MERCHANTS).bind(),
            account4 = Arb.int(1000..9999).bind().toString(),
            reference = "REF" + Arb.int(100_000..999_999).bind(),
            balance = Arb.long(0L..9_000_000L).bind(),
        )
    }

    /** A six-digit code containing a digit no Source_Ref can contain (base32 has no 0, 1, 8 or 9), so a find means a leak. */
    val otpCodes: Arb<String> = arbitrary {
        val digits = StringBuilder()
        repeat(OTP_LENGTH) { digits.append(Arb.int(0..9).bind()) }
        if (digits.none { it in "0189" }) digits.setCharAt(0, '1')
        digits.toString()
    }

    private const val OTP_LENGTH = 6

    val OTP_KEYWORDS = listOf("OTP", "otp", "One Time Password", "verification code", "Verification Code")
}

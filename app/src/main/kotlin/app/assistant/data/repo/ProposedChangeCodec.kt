package app.assistant.data.repo

import app.assistant.domain.engine.suggestions.ProposedChange
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import java.time.LocalDate

/**
 * Encodes a [ProposedChange] to a compact pipe-delimited string for the `suggestion.change_json` column, and back. The
 * set of variants is small and closed, so this avoids pulling in a serialization dependency. Format: TYPE|field|field...
 * An unknown or malformed string decodes to null (the Suggestion then applies nothing), which is safe.
 */
object ProposedChangeCodec {
    private const val SEP = "|"

    fun encode(change: ProposedChange): String = when (change) {
        is ProposedChange.SubscriptionPriceChange -> listOf("PRICE", change.subscriptionId, change.newAmount.value, change.newInterval.name).joinToString(SEP)
        is ProposedChange.SubscriptionCancel -> listOf("CANCEL", change.subscriptionId).joinToString(SEP)
        is ProposedChange.SubscriptionReactivate -> listOf("REACTIVATE", change.subscriptionId, change.nextDue).joinToString(SEP)
        is ProposedChange.SubscriptionAdvanceDue -> listOf("ADVANCE", change.subscriptionId, change.newNextDue).joinToString(SEP)
    }

    fun decode(text: String): ProposedChange? {
        val parts = text.split(SEP)
        return runCatching {
            when (parts.getOrNull(0)) {
                "PRICE" -> ProposedChange.SubscriptionPriceChange(parts[1].toLong(), Paise(parts[2].toLong()), SubscriptionInterval.valueOf(parts[3]))
                "CANCEL" -> ProposedChange.SubscriptionCancel(parts[1].toLong())
                "REACTIVATE" -> ProposedChange.SubscriptionReactivate(parts[1].toLong(), LocalDate.parse(parts[2]))
                "ADVANCE" -> ProposedChange.SubscriptionAdvanceDue(parts[1].toLong(), LocalDate.parse(parts[2]))
                else -> null
            }
        }.getOrNull()
    }
}

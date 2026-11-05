package app.assistant.ui.money.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Paise
import app.assistant.ui.common.errorText

/** Reads and writes the rupee amount text of the Budget and Bill forms. Money is never a floating point number. */
object MoneyAmountText {
    private const val PAISE_PER_RUPEE = 100L
    private const val RUPEE_SIGN = "\u20B9"
    private val amountPattern = Regex("^(\\d{1,10})(?:\\.(\\d{1,2}))?$")
    private val tooLong = Regex("^\\d{11,}(?:\\.\\d{1,2})?$")

    /**
     * Reads rupees with at most two decimals; a leading rupee sign and commas are ignored. Blank text is EMPTY, text that is
     * not an amount is NOT_A_NUMBER and an amount outside the Valid_Amount range is OUT_OF_RANGE.
     */
    fun parse(text: String): Outcome<Paise, ValidationError> {
        val cleaned = text.trim().removePrefix(RUPEE_SIGN).replace(",", "").trim()
        if (cleaned.isEmpty()) return Outcome.Err(ValidationError.EMPTY)
        val match = amountPattern.matchEntire(cleaned)
        if (match == null) {
            return Outcome.Err(if (tooLong.matches(cleaned)) ValidationError.OUT_OF_RANGE else ValidationError.NOT_A_NUMBER)
        }
        val rupees = match.groupValues[1].toLong()
        val fraction = match.groupValues[2].padEnd(2, '0').toLong()
        val paise = Paise(rupees * PAISE_PER_RUPEE + fraction)
        return if (paise.isValid) Outcome.Ok(paise) else Outcome.Err(ValidationError.OUT_OF_RANGE)
    }

    /** The amount as editable text: 1234 or 1234.50. */
    fun plain(amount: Paise): String {
        val rupees = amount.value / PAISE_PER_RUPEE
        val fraction = (amount.value % PAISE_PER_RUPEE).toInt()
        return if (fraction == 0) rupees.toString() else "$rupees.${fraction.toString().padStart(2, '0')}"
    }
}

/** A form field error shown as an icon and words, so it never relies on colour alone. */
@Composable
fun MoneyFieldError(error: ValidationError, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(errorText(error)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

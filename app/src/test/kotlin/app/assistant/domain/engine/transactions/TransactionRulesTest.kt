package app.assistant.domain.engine.transactions

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TransactionRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 3)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant()

    private fun valid() = ManualTransactionInput(TxnType.DEBIT, Paise(12_345), noon, "Swiggy")

    private fun errors(input: ManualTransactionInput) = TransactionValidator.validateNew(input, today, zone).errors

    // Requirement 32.6

    @Test
    fun `a complete manual Transaction is accepted`() = assertTrue(TransactionValidator.validateNew(valid(), today, zone).isValid)

    @Test
    fun `every missing field is reported and nothing else`() {
        val result = errors(ManualTransactionInput(null, null, null, "  "))
        assertEquals(
            mapOf(
                EditedField.TYPE to ValidationError.EMPTY,
                EditedField.AMOUNT to ValidationError.EMPTY,
                EditedField.DATE to ValidationError.EMPTY,
                EditedField.MERCHANT to ValidationError.EMPTY,
            ),
            result,
        )
    }

    @Test
    fun `the amount must be a Valid_Amount`() {
        assertEquals(ValidationError.OUT_OF_RANGE, errors(valid().copy(amount = Paise(0)))[EditedField.AMOUNT])
        assertEquals(ValidationError.OUT_OF_RANGE, errors(valid().copy(amount = Paise(Paise.MAX_VALID.value + 1)))[EditedField.AMOUNT])
        assertNull(errors(valid().copy(amount = Paise.MIN_VALID))[EditedField.AMOUNT])
        assertNull(errors(valid().copy(amount = Paise.MAX_VALID))[EditedField.AMOUNT])
    }

    @Test
    fun `a date later than today in the device zone is rejected but any time today is fine`() {
        val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
        assertNull(errors(valid().copy(at = endOfToday))[EditedField.DATE])
        assertEquals(ValidationError.IN_FUTURE, errors(valid().copy(at = startOfTomorrow))[EditedField.DATE])
        assertNull(errors(valid().copy(at = Instant.parse("2020-01-01T00:00:00Z")))[EditedField.DATE])
    }

    @Test
    fun `the Merchant name is trimmed and limited to 100 characters`() {
        assertNull(errors(valid().copy(merchant = "a".repeat(100)))[EditedField.MERCHANT])
        assertNull(errors(valid().copy(merchant = "  " + "a".repeat(100) + "  "))[EditedField.MERCHANT])
        assertEquals(ValidationError.TOO_LONG, errors(valid().copy(merchant = "a".repeat(101)))[EditedField.MERCHANT])
    }

    @Test
    fun `an edit checks only the fields it changes`() {
        val check = { e: TransactionEdit -> TransactionValidator.validateEdit(e, today, zone).errors }
        assertTrue(check(TransactionEdit()).isEmpty())
        assertEquals(mapOf(EditedField.AMOUNT to ValidationError.OUT_OF_RANGE), check(TransactionEdit(amount = Paise(0))))
        // Clearing the Merchant is allowed on an edit; only a manual add needs one.
        assertTrue(check(TransactionEdit(merchant = "  ")).isEmpty())
        assertEquals(mapOf(EditedField.MERCHANT to ValidationError.TOO_LONG), check(TransactionEdit(merchant = "a".repeat(101))))
        assertEquals(mapOf(EditedField.DATE to ValidationError.IN_FUTURE), check(TransactionEdit(at = today.plusDays(2).atStartOfDay(zone).toInstant())))
    }

    // The stored bitmask (Requirement 32.1)

    @Test
    fun `the edited-field mask round-trips every subset`() {
        val all = EditedField.entries
        for (bits in 0 until (1 shl all.size)) {
            val subset = all.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
            assertEquals(subset, EditedFieldsMask.decode(EditedFieldsMask.encode(subset)))
        }
        assertEquals(0, EditedFieldsMask.encode(emptySet()))
    }

    // Merge (Requirements 32.4, 32.8)

    private fun txn(
        id: Long,
        type: TxnType = TxnType.DEBIT,
        amount: Long = 10_000,
        atSecond: Long = 0,
        merchant: String? = "Swiggy",
        edited: Set<EditedField> = emptySet(),
    ) = Transaction(
        id = TransactionId(id),
        type = type,
        amount = Paise(amount),
        at = noon.plusSeconds(atSecond),
        merchantId = merchant?.let { MerchantId(1) },
        merchantName = merchant,
        category = CategoryId.UNCATEGORISED,
        source = TxnSource.SMS,
        reference = null,
        account4 = null,
        balance = null,
        isReversal = false,
        editedFields = edited,
        categoryUserSet = false,
        sourceRefs = emptyList(),
    )

    @Test
    fun `only Transactions of the same type may merge`() {
        assertNull(TransactionMerge.check(TxnType.DEBIT, TxnType.DEBIT))
        assertNull(TransactionMerge.check(TxnType.CREDIT, TxnType.CREDIT))
        assertEquals(MergeError.DIFFERENT_TYPE, TransactionMerge.check(TxnType.DEBIT, TxnType.CREDIT))
        assertThrows(IllegalArgumentException::class.java) {
            TransactionMerge.mergedEdits(txn(1), txn(2, type = TxnType.CREDIT), MergeSelection())
        }
    }

    @Test
    fun `where the two disagree the selected value becomes a User edit`() {
        val a = txn(1, amount = 100, atSecond = 0, merchant = "Swiggy")
        val b = txn(2, amount = 250, atSecond = 3_600, merchant = "Amazon")
        val edits = TransactionMerge.mergedEdits(a, b, MergeSelection(amount = MergeSide.SECOND, date = MergeSide.FIRST, merchant = MergeSide.SECOND))
        assertEquals(Paise(250), edits.amount)
        assertEquals(a.at, edits.at)
        assertEquals("Amazon", edits.merchant)
        assertNull(edits.type)
    }

    @Test
    fun `where they agree nothing is edited unless one already was`() {
        val plain = TransactionMerge.mergedEdits(txn(1), txn(2), MergeSelection())
        assertTrue(plain.fields.isEmpty())

        val kept = TransactionMerge.mergedEdits(
            txn(1, edited = setOf(EditedField.AMOUNT, EditedField.MERCHANT)),
            txn(2),
            MergeSelection(amount = MergeSide.SECOND),
        )
        assertEquals(setOf(EditedField.AMOUNT, EditedField.MERCHANT), kept.fields)
    }

    @Test
    fun `Merchant names that differ only in case or spaces count as the same`() {
        val edits = TransactionMerge.mergedEdits(txn(1, merchant = "Swiggy"), txn(2, merchant = "  SWIGGY "), MergeSelection(merchant = MergeSide.SECOND))
        assertNull(edits.merchant)
    }

    @Test
    fun `a Transaction with no Merchant merges with one that has one`() {
        val edits = TransactionMerge.mergedEdits(txn(1, merchant = null), txn(2, merchant = "Swiggy"), MergeSelection(merchant = MergeSide.SECOND))
        assertEquals("Swiggy", edits.merchant)
        val cleared = TransactionMerge.mergedEdits(txn(1, merchant = null), txn(2, merchant = "Swiggy"), MergeSelection(merchant = MergeSide.FIRST))
        assertEquals("", cleared.merchant)
    }
}

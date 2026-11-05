// Feature: Phase 3 Money — Property 46: Budget arithmetic. Property 6: validators accept exactly the valid region (Budget, Transaction).
package app.assistant.domain.engine.budget

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TransactionValidator
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.testing.PropertyDefaults
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class BudgetEnginePropertyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val month = YearMonth.of(2026, 10)
    private val firstCandidate = LocalDate.of(2026, 9, 25).atStartOfDay(zone).toInstant()

    private fun txn(id: Long, type: TxnType, amount: Long, at: Instant, category: Long, reversal: Boolean) = Transaction(
        id = TransactionId(id),
        type = type,
        amount = Paise(amount),
        at = at,
        merchantId = MerchantId(1),
        merchantName = "Shop",
        category = CategoryId(category),
        source = TxnSource.SMS,
        reference = null,
        account4 = null,
        balance = null,
        isReversal = reversal,
        editedFields = emptySet(),
        categoryUserSet = false,
        sourceRefs = emptyList(),
    )

    /** Transactions from late September to early November, so the month's edges are crossed in both directions. */
    private val transactions = arbitrary {
        val out = ArrayList<Transaction>()
        repeat(Arb.int(0..30).bind()) { i ->
            out += txn(
                id = i + 1L,
                type = Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind(),
                amount = Arb.long(1L..5_000_000L).bind(),
                at = firstCandidate.plusSeconds(Arb.long(0L..(42L * 86_400L)).bind()),
                category = Arb.long(1L..6L).bind(),
                reversal = Arb.boolean().bind(),
            )
        }
        out.toList()
    }

    private fun inMonth(t: Transaction): Boolean {
        val z = t.at.atZone(zone)
        return z.year == 2026 && z.monthValue == 10
    }

    private fun isSpend(t: Transaction): Boolean = t.type == TxnType.DEBIT && !t.isReversal && inMonth(t)

    @Test
    fun `Property 46 - spent per category is the sum of non-Reversal debits in the month and the categories add up to the total`() = runTest {
        checkAll(PropertyDefaults.thorough, transactions) { txns ->
            val expectedTotal = txns.filter { isSpend(it) }.sumOf { it.amount.value }
            for (c in 1L..6L) {
                val expected = txns.filter { isSpend(it) && it.category.value == c }.sumOf { it.amount.value }
                assertEquals(expected, BudgetEngine.spent(CategoryId(c), month, txns, zone).value, "category $c")
            }
            assertEquals(expectedTotal, BudgetEngine.totalSpent(month, txns, zone).value)
            assertEquals(expectedTotal, BudgetEngine.spentByCategory(month, txns, zone).values.sumOf { it.value }, "categories add up to the total")
        }
    }

    @Test
    fun `Property 46 - remaining is amount minus spent, percent is the floor, and over budget exactly when spent exceeds the amount`() = runTest {
        checkAll(PropertyDefaults.thorough, Arb.long(1L..99_999_999_999L), Arb.long(0L..99_999_999_999L)) { amount, spent ->
            val status = BudgetEngine.status(Budget(CategoryId(2), Paise(amount)), Paise(spent))
            assertEquals(amount - spent, status.remaining.value)
            assertEquals(BigInteger.valueOf(spent).multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(amount)).toLong(), status.percentUsed)
            assertEquals(spent > amount, status.isOver)
            assertEquals(if (spent > amount) spent - amount else null, status.overBy?.value)
        }
    }

    @Test
    fun `percent used keeps values above 100 and rounds down`() {
        val budget = Budget(CategoryId(2), Paise(300))
        assertEquals(66L, BudgetEngine.status(budget, Paise(199)).percentUsed)
        assertEquals(100L, BudgetEngine.status(budget, Paise(300)).percentUsed)
        assertEquals(250L, BudgetEngine.status(budget, Paise(750)).percentUsed)
        assertTrue(BudgetEngine.status(budget, Paise(301)).isOver)
        assertTrue(!BudgetEngine.status(budget, Paise(300)).isOver)
    }

    /** Property 6: a Budget amount is accepted if and only if it is a Valid_Amount. */
    @Test
    fun `Property 6 - budget amount is valid exactly from 1 paisa to 99,99,99,999 rupees 99 paise`() = runTest {
        checkAll(PropertyDefaults.standard, Arb.long(0L..100_000_000_000L).orNull(0.1)) { value ->
            val errors = BudgetValidator.validate(value?.let { Paise(it) })
            assertEquals(value != null && value in 1L..99_999_999_999L, errors.isValid, "value: $value")
            assertTrue(errors.errors.keys.all { it == BudgetField.AMOUNT })
        }
        listOf(0L, 1L, 99_999_999_999L, 100_000_000_000L).forEach {
            assertEquals(it in 1L..99_999_999_999L, BudgetValidator.validate(Paise(it)).isValid)
        }
    }

    private val today = LocalDate.of(2026, 11, 4)

    private fun dateOf(offsetSeconds: Long): Instant = today.atStartOfDay(zone).toInstant().plusSeconds(offsetSeconds)

    /** Property 6 for a manually added Transaction (Requirement 32.6). */
    @Test
    fun `Property 6 - a new Transaction is valid exactly when every field is inside the range`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.element(TxnType.DEBIT, TxnType.CREDIT).orNull(0.15),
            Arb.long(0L..100_000_000_000L).orNull(0.15),
            Arb.long(-5L * 86_400L..5L * 86_400L).orNull(0.15),
            Arb.string(0..130),
        ) { type, amount, offset, merchant ->
            val at = offset?.let { dateOf(it) }
            val errors = TransactionValidator.validateNew(ManualTransactionInput(type, amount?.let { Paise(it) }, at, merchant), today, zone)
            val expected = buildSet {
                if (type == null) add(EditedField.TYPE)
                if (amount == null || amount !in 1L..99_999_999_999L) add(EditedField.AMOUNT)
                if (at == null || at.atZone(zone).toLocalDate().isAfter(today)) add(EditedField.DATE)
                if (merchant.trim().isEmpty() || merchant.trim().length > 100) add(EditedField.MERCHANT)
            }
            assertEquals(expected, errors.errors.keys, "type=$type amount=$amount offset=$offset")
        }
    }

    /** Property 6 for an edit: only the fields being changed are checked, and a blank Merchant clears it. */
    @Test
    fun `Property 6 - an edit checks only the fields it changes`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.long(0L..100_000_000_000L).orNull(0.5),
            Arb.long(-5L * 86_400L..5L * 86_400L).orNull(0.5),
            Arb.string(0..130).orNull(0.5),
        ) { amount, offset, merchant ->
            val at = offset?.let { dateOf(it) }
            val errors = TransactionValidator.validateEdit(TransactionEdit(amount = amount?.let { Paise(it) }, at = at, merchant = merchant), today, zone)
            val expected = buildSet {
                if (amount != null && amount !in 1L..99_999_999_999L) add(EditedField.AMOUNT)
                if (at != null && at.atZone(zone).toLocalDate().isAfter(today)) add(EditedField.DATE)
                if (merchant != null && merchant.trim().length > 100) add(EditedField.MERCHANT)
            }
            assertEquals(expected, errors.errors.keys)
        }
    }
}

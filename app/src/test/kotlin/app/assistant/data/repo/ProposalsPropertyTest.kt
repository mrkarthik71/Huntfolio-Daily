// Feature: Phase 3 Money — Property 5: Proposals change nothing until accepted (Bill, Bill paid, Review_Queue).
// Property 47: Bill matching, proposals and due notifications (no duplicate Bill proposal, paid proposals once per pair).
package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.bills.BillInput
import app.assistant.domain.engine.bills.BillRules
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.BillAlarmSync
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.testing.FakeTimeSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProposalsPropertyTest {
    private lateinit var db: AppDatabase
    private lateinit var transactions: RoomTransactionRepository
    private lateinit var queue: RoomReviewQueueRepository
    private lateinit var bills: RoomBillRepository
    private lateinit var proposals: RoomBillProposals
    private val time = FakeTimeSource()

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        val provider = DbProvider { db }
        transactions = RoomTransactionRepository(provider, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        queue = RoomReviewQueueRepository(provider, time, transactions)
        bills = RoomBillRepository(provider, transactions, time, BillAlarmSync { })
        proposals = RoomBillProposals(provider)
    }

    @After
    fun tearDown() = db.close()

    private enum class Op {
        PROPOSE_BILL,
        DISMISS_PROPOSAL,
        ACCEPT_PROPOSAL,
        ADD_BILL,
        ADD_PAYMENT,
        ACCEPT_PAID,
        DISMISS_PAID,
        ADD_REVIEW,
        RESOLVE_REVIEW,
        DISMISS_REVIEW,
    }

    private data class Step(val op: Op, val pick: Int)

    private val sequences = arbitrary {
        val steps = ArrayList<Step>()
        repeat(Arb.int(4..14).bind()) { steps += Step(Arb.element(Op.entries).bind(), Arb.int(0..99).bind()) }
        steps.toList()
    }

    private val merchants = listOf("Airtel", "Jio")
    private val amounts = listOf(49_900L, 59_900L)
    private val baseDue = LocalDate.of(2026, 10, 20)

    private fun dueOf(pick: Int): LocalDate = baseDue.plusDays((pick % 5).toLong())

    private fun instantOn(date: LocalDate): Instant = date.atTime(12, 0).atZone(time.zone()).toInstant()

    private fun merchantIdOf(name: String): Long? = db.openHelper.writableDatabase
        .query("SELECT id FROM merchant WHERE name_norm = ?", arrayOf(name.lowercase())).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun isDuplicate(merchant: String, amount: Long, due: LocalDate): Boolean {
        val id = merchantIdOf(merchant) ?: return false
        return bills.bills().first().any { it.merchantId?.value == id && it.amount.value == amount && it.dueDate == due } ||
            bills.proposals().first().any { it.merchantId?.value == id && it.amount.value == amount && it.dueDate == due }
    }

    @Test
    fun `Property 5 and 47 - pending, dismissed and ignored proposals change nothing, and accepting changes exactly what they describe`() {
        runBlocking {
            checkAll(100, sequences) { steps ->
                MoneyTestDb.reset(db)
                steps.forEachIndexed { index, (op, pick) ->
                    val billsBefore = bills.bills().first()
                    val txnsBefore = transactions.transactions().first()
                    val reviewBefore = queue.openItemsNewestFirst().first()
                    when (op) {
                        Op.PROPOSE_BILL -> {
                            val merchant = merchants[pick % 2]
                            val amount = amounts[(pick / 2) % 2]
                            val due = dueOf(pick / 4)
                            val duplicate = isDuplicate(merchant, amount, due)
                            val made = proposals.propose(merchant, Paise(amount), due)
                            assertEquals("a proposal is made exactly when no equal Bill or open proposal exists", !duplicate, made)
                            assertFalse("the same parsed bill again makes no proposal", proposals.propose(merchant, Paise(amount), due))
                            assertEquals(billsBefore, bills.bills().first())
                            assertEquals(txnsBefore, transactions.transactions().first())
                        }
                        Op.DISMISS_PROPOSAL -> bills.proposals().first().takeIf { it.isNotEmpty() }?.let { open ->
                            val p = open[pick % open.size]
                            bills.dismissProposal(p.id)
                            assertEquals(billsBefore, bills.bills().first())
                            assertTrue(bills.proposals().first().none { it.id == p.id })
                        }
                        Op.ACCEPT_PROPOSAL -> bills.proposals().first().takeIf { it.isNotEmpty() }?.let { open ->
                            val p = open[pick % open.size]
                            val recurrence = if (pick % 2 == 0) BillRecurrence.MONTHLY else BillRecurrence.NONE
                            val input = BillInput("Bill ${p.id}", p.amount, p.dueDate ?: dueOf(pick), recurrence)
                            val result = bills.acceptProposal(p.id, input)
                            assertTrue(result is Outcome.Ok)
                            val created = (result as Outcome.Ok).value
                            val after = bills.bills().first()
                            assertEquals(billsBefore.size + 1, after.size)
                            assertTrue(after.containsAll(billsBefore))
                            val bill = after.single { it.id == created }
                            assertEquals(p.merchantId, bill.merchantId)
                            assertEquals(p.amount, bill.amount)
                            assertEquals(input.dueDate, bill.dueDate)
                            assertFalse(bill.paid)
                            assertTrue(bills.proposals().first().none { it.id == p.id })
                            assertEquals(txnsBefore, transactions.transactions().first())
                        }
                        Op.ADD_BILL -> {
                            val result = bills.save(
                                BillInput("Own $index", Paise(amounts[pick % 2]), dueOf(pick / 2), BillRecurrence.entries[pick % 3], merchants[(pick / 3) % 2]),
                            )
                            assertTrue(result is Outcome.Ok)
                            assertEquals(billsBefore.size + 1, bills.bills().first().size)
                        }
                        Op.ADD_PAYMENT -> {
                            val date = dueOf(pick / 4).plusDays(((pick / 2) % 3) - 1L)
                            val entered = ManualTransactionInput(TxnType.DEBIT, Paise(amounts[pick % 2]), instantOn(date), merchants[(pick / 8) % 2])
                            val added = transactions.addManual(entered)
                            val id = (added as Outcome.Ok).value
                            val txn = checkNotNull(transactions.get(id))
                            val expected = BillRules.paidCandidate(txn, billsBefore, time.zone())
                            val made = bills.proposePaid(id)
                            assertEquals(expected != null, made)
                            if (expected != null) assertTrue(bills.paidProposals().first().any { it.transaction == id && it.bill == expected.id })
                            assertFalse("a pair is proposed once", bills.proposePaid(id))
                            assertEquals("a pending paid proposal changes no Bill", billsBefore, bills.bills().first())
                        }
                        Op.ACCEPT_PAID -> bills.paidProposals().first().takeIf { it.isNotEmpty() }?.let { open ->
                            val pp = open[pick % open.size]
                            val target = billsBefore.single { it.id == pp.bill }
                            val result = bills.acceptPaidProposal(pp.transaction, pp.bill)
                            assertTrue(result is Outcome.Ok)
                            val next = (result as Outcome.Ok).value
                            val after = bills.bills().first()
                            assertTrue(after.single { it.id == pp.bill }.paid)
                            val untouched = billsBefore.filter { it.id != pp.bill }
                            assertTrue(after.containsAll(untouched))
                            when (target.recurrence) {
                                BillRecurrence.NONE -> {
                                    assertEquals(null, next)
                                    assertEquals(billsBefore.size, after.size)
                                }
                                BillRecurrence.MONTHLY, BillRecurrence.YEARLY -> {
                                    val monthly = target.recurrence == BillRecurrence.MONTHLY
                                    val expectedDue = if (monthly) target.dueDate.plusMonths(1) else target.dueDate.plusYears(1)
                                    assertEquals(billsBefore.size + 1, after.size)
                                    val created = after.single { it.id == next }
                                    assertEquals(expectedDue, created.dueDate)
                                    assertFalse(created.paid)
                                }
                            }
                            assertEquals(txnsBefore, transactions.transactions().first())
                        }
                        Op.DISMISS_PAID -> bills.paidProposals().first().takeIf { it.isNotEmpty() }?.let { open ->
                            val pp = open[pick % open.size]
                            bills.dismissPaidProposal(pp.transaction, pp.bill)
                            assertEquals(billsBefore, bills.bills().first())
                            assertTrue(bills.paidProposals().first().none { it.transaction == pp.transaction && it.bill == pp.bill })
                            assertFalse("a dismissed pair is not proposed again", bills.proposePaid(pp.transaction))
                        }
                        Op.ADD_REVIEW -> {
                            queue.add(SourceRef("sms:v1:review$index$pick"), "redacted text $pick", MessageOrigin("BANKA"), instantOn(baseDue))
                            assertEquals(txnsBefore, transactions.transactions().first())
                            assertEquals(billsBefore, bills.bills().first())
                        }
                        Op.RESOLVE_REVIEW -> reviewBefore.takeIf { it.isNotEmpty() }?.let { open ->
                            val item = open[pick % open.size]
                            val amount = Paise(12_345L + item.id.value)
                            val entered = ManualTransactionInput(TxnType.DEBIT, amount, instantOn(baseDue), "Manual Shop")
                            val result = queue.resolve(item.id, entered)
                            assertTrue(result is Outcome.Ok)
                            val created = (result as Outcome.Ok).value
                            val after = transactions.transactions().first()
                            assertEquals(txnsBefore.size + 1, after.size)
                            assertEquals(txnsBefore, after.filter { it.id != created })
                            val txn = after.single { it.id == created }
                            assertEquals(amount, txn.amount)
                            assertEquals(TxnType.DEBIT, txn.type)
                            assertEquals("Manual Shop", txn.merchantName)
                            assertTrue(queue.openItemsNewestFirst().first().none { it.id == item.id })
                        }
                        Op.DISMISS_REVIEW -> reviewBefore.takeIf { it.isNotEmpty() }?.let { open ->
                            queue.dismiss(open[pick % open.size].id)
                            assertEquals(txnsBefore, transactions.transactions().first())
                            assertEquals(billsBefore, bills.bills().first())
                        }
                    }
                }
            }
        }
    }
}

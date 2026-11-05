package app.assistant.ui.money.transactions

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.testing.FakeMoneyTransactions
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.fakeOffer
import app.assistant.testing.fakeTxn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Task 23.1 / 23.3, Requirements 31.4, 32: the Transactions ViewModel over a fake repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val nov2 = LocalDate.of(2026, 11, 2)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(repo: FakeMoneyTransactions): TransactionsViewModel {
        val vm = TransactionsViewModel(repo, FakeTimeSource())
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun list_isNewestFirst() = runTest(dispatcher) {
        val older = fakeTxn(1, at = Instant.parse("2026-11-01T06:30:00Z"), merchant = "Older")
        val newer = fakeTxn(2, at = Instant.parse("2026-11-02T06:30:00Z"), merchant = "Newer")
        val vm = viewModel(FakeMoneyTransactions(listOf(older, newer)))
        assertEquals(listOf("Newer", "Older"), vm.state.value.items.map { it.merchantName })
    }

    @Test
    fun invalidEdit_changesNothingAndReportsTheFieldError() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, amountPaise = 50_000, merchant = "Swiggy")))
        val vm = viewModel(repo)
        vm.startEdit(vm.state.value.items.first())
        vm.submit(TransactionForm(TxnType.DEBIT, "0", nov2, "Swiggy"))

        val dialog = vm.state.value.dialog as TransactionsDialog.Editor
        assertEquals(ValidationError.OUT_OF_RANGE, dialog.errors[EditedField.AMOUNT])
        assertTrue(repo.edits.isEmpty())
        assertEquals(50_000L, repo.current.single().amount.value)
    }

    @Test
    fun amountThatIsNotANumber_isRejectedWithoutCallingTheRepository() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1)))
        val vm = viewModel(repo)
        vm.startEdit(vm.state.value.items.first())
        vm.submit(TransactionForm(TxnType.DEBIT, "12abc", nov2, "Swiggy"))

        val dialog = vm.state.value.dialog as TransactionsDialog.Editor
        assertEquals(ValidationError.NOT_A_NUMBER, dialog.errors[EditedField.AMOUNT])
        assertTrue(repo.edits.isEmpty())
    }

    @Test
    fun validEdit_sendsOnlyTheChangedFields() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, amountPaise = 50_000, merchant = "Swiggy")))
        val vm = viewModel(repo)
        vm.startEdit(vm.state.value.items.first())
        vm.submit(TransactionForm(TxnType.DEBIT, "612.50", nov2, "Swiggy"))

        val edit = repo.edits.single().second
        assertEquals(61_250L, edit.amount?.value)
        assertNull(edit.type)
        assertNull(edit.at)
        assertNull(edit.merchant)
        assertNull(vm.state.value.dialog)
        assertEquals(NoticeKind.SAVED, vm.state.value.notice?.kind)
    }

    @Test
    fun manualAdd_storesTheTransaction() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions()
        val vm = viewModel(repo)
        vm.startAdd()
        vm.submit(TransactionForm(TxnType.CREDIT, "1,200", nov2, "Salary"))

        val stored = repo.current.single()
        assertEquals(TxnType.CREDIT, stored.type)
        assertEquals(120_000L, stored.amount.value)
        assertEquals("Salary", stored.merchantName)
        assertEquals(NoticeKind.ADDED, vm.state.value.notice?.kind)
    }

    @Test
    fun manualAdd_withEmptyMerchant_showsTheFieldError() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions()
        val vm = viewModel(repo)
        vm.startAdd()
        vm.submit(TransactionForm(TxnType.DEBIT, "10", nov2, "  "))

        val dialog = vm.state.value.dialog as TransactionsDialog.Editor
        assertEquals(ValidationError.EMPTY, dialog.errors[EditedField.MERCHANT])
        assertTrue(repo.current.isEmpty())
    }

    @Test
    fun mergeOfDebitAndCredit_isRejectedAndNothingMerges() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, TxnType.DEBIT), fakeTxn(2, TxnType.CREDIT)))
        val vm = viewModel(repo)
        vm.startMerge()
        vm.toggleSelected(TransactionId(1))
        vm.toggleSelected(TransactionId(2))
        vm.requestMerge()

        assertEquals(NoticeKind.MERGE_DIFFERENT_TYPE, vm.state.value.notice?.kind)
        assertNull(vm.state.value.dialog)
        assertTrue(repo.merges.isEmpty())
        assertEquals(2, repo.current.size)
    }

    @Test
    fun mergeOfTwoDebits_keepsTheSelectedValues() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1, amountPaise = 10_000), fakeTxn(2, amountPaise = 20_000)))
        val vm = viewModel(repo)
        vm.startMerge()
        vm.toggleSelected(TransactionId(2))
        vm.toggleSelected(TransactionId(1))
        vm.requestMerge()
        assertNotNull(vm.state.value.dialog as? TransactionsDialog.Merge)

        vm.confirmMerge(MergeSelection())
        assertEquals(1, repo.current.size)
        assertEquals(10_000L, repo.current.single().amount.value)
        assertEquals(NoticeKind.MERGED, vm.state.value.notice?.kind)
    }

    @Test
    fun delete_needsConfirmation() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1)))
        val vm = viewModel(repo)
        vm.requestDelete(vm.state.value.items.first())
        assertTrue(vm.state.value.dialog is TransactionsDialog.ConfirmDelete)
        assertTrue(repo.deleted.isEmpty())

        vm.confirmDelete()
        assertEquals(listOf(TransactionId(1)), repo.deleted)
        assertTrue(repo.current.isEmpty())
    }

    @Test
    fun categoryChange_withAnOffer_asksAndReportsHowManyChanged() = runTest(dispatcher) {
        val food = CategoryId(2)
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1))).apply {
            offerToReturn = fakeOffer(3, food, TransactionId(1))
            appliedCount = 3
        }
        val vm = viewModel(repo)
        vm.chooseCategory(vm.state.value.items.first(), food)

        val offer = vm.state.value.dialog as TransactionsDialog.Offer
        assertEquals(3, offer.offer.count)
        assertEquals("Food", offer.categoryName)

        vm.applyOffer()
        assertEquals(NoticeKind.CATEGORY_APPLIED, vm.state.value.notice?.kind)
        assertEquals(3, vm.state.value.notice?.count)
        assertEquals(1, repo.appliedOffers.size)
    }

    @Test
    fun categoryChange_withoutAnOffer_opensNoDialog() = runTest(dispatcher) {
        val repo = FakeMoneyTransactions(listOf(fakeTxn(1)))
        val vm = viewModel(repo)
        vm.chooseCategory(vm.state.value.items.first(), CategoryId(2))

        assertNull(vm.state.value.dialog)
        assertEquals(NoticeKind.CATEGORY_CHANGED, vm.state.value.notice?.kind)
    }
}

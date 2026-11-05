package app.assistant.ui.money.bills

import app.assistant.domain.engine.bills.BillField
import app.assistant.domain.engine.bills.BillOccurrence
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.BillPaidProposal
import app.assistant.domain.port.BillProposal
import app.assistant.testing.FakeMoneyBillRepository
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Task 23.3 / Requirement 34: Bill forms are validated, marking a recurring Bill paid creates the next one, proposals are resolved. */
@OptIn(ExperimentalCoroutinesApi::class)
class BillsViewModelTest {
    private val due = LocalDate.of(2026, 11, 10)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun bill(id: Long, name: String, paid: Boolean = false, recurrence: BillRecurrence = BillRecurrence.NONE, dueDate: LocalDate = due) =
        BillOccurrence(BillId(id), name, Paise(123_400), dueDate, recurrence, dueDate.dayOfMonth, null, paid, id, false)

    private fun viewModel(repository: FakeMoneyBillRepository) = BillsViewModel(repository, FakeTimeSource())

    @Test
    fun unpaidBillsComeBeforePaidOnes() {
        val repository = FakeMoneyBillRepository(listOf(bill(1, "Water", paid = true), bill(2, "Rent"), bill(3, "Power")))

        val ui = viewModel(repository).state.value

        assertEquals(listOf("Rent", "Power"), ui.unpaid.map { it.name })
        assertEquals(listOf("Water"), ui.paid.map { it.name })
    }

    @Test
    fun anInvalidForm_isRejectedWithAnErrorPerFieldAndStoresNothing() {
        val repository = FakeMoneyBillRepository()
        var errors: FieldErrors<BillField>? = null

        viewModel(repository).save(null, BillForm("  ", "", null, BillRecurrence.NONE)) { errors = it }

        assertEquals(ValidationError.EMPTY, errors?.get(BillField.NAME))
        assertEquals(ValidationError.EMPTY, errors?.get(BillField.AMOUNT))
        assertEquals(ValidationError.EMPTY, errors?.get(BillField.DUE_DATE))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun anAmountThatIsNotANumber_isReportedOnTheAmountField() {
        val repository = FakeMoneyBillRepository()
        var errors: FieldErrors<BillField>? = null

        viewModel(repository).save(null, BillForm("Rent", "12x", due, BillRecurrence.NONE)) { errors = it }

        assertEquals(ValidationError.NOT_A_NUMBER, errors?.get(BillField.AMOUNT))
        assertNull(errors?.get(BillField.NAME))
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun aValidForm_isStoredWithTheAmountInPaise() {
        val repository = FakeMoneyBillRepository()
        var errors: FieldErrors<BillField>? = FieldErrors(mapOf(BillField.NAME to ValidationError.EMPTY))

        viewModel(repository).save(null, BillForm("Rent", "1,234.50", due, BillRecurrence.MONTHLY)) { errors = it }

        assertNull(errors)
        val input = repository.saved.single()
        assertEquals(Paise(123_450), input.amount)
        assertEquals(due, input.dueDate)
        assertEquals(BillRecurrence.MONTHLY, input.recurrence)
    }

    @Test
    fun markingARecurringBillPaid_saysTheNextOccurrenceWasCreated() {
        val repository = FakeMoneyBillRepository(listOf(bill(1, "Rent", recurrence = BillRecurrence.MONTHLY)))
        val vm = viewModel(repository)

        vm.markPaid(vm.state.value.unpaid.single())

        assertEquals(BillNotice.PaidWithNext("Rent", LocalDate.of(2026, 12, 10)), vm.state.value.notice)
        assertEquals(listOf(LocalDate.of(2026, 12, 10)), vm.state.value.unpaid.map { it.dueDate })
        assertEquals(listOf("Rent"), vm.state.value.paid.map { it.name })
    }

    @Test
    fun markingAOneOffBillPaid_createsNoNextOccurrence() {
        val vm = viewModel(FakeMoneyBillRepository(listOf(bill(1, "Deposit"))))

        vm.markPaid(vm.state.value.unpaid.single())

        assertEquals(BillNotice.Paid("Deposit"), vm.state.value.notice)
        assertTrue(vm.state.value.unpaid.isEmpty())
    }

    @Test
    fun markingAPaidBillPaidAgain_isReportedAsFailed_andTheNoticeCanBeDismissed() {
        val vm = viewModel(FakeMoneyBillRepository(listOf(bill(1, "Water", paid = true))))

        vm.markPaid(vm.state.value.paid.single())
        assertEquals(BillNotice.Failed, vm.state.value.notice)

        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun aPaidProposal_canBeAcceptedAndDismissed() {
        val repository = FakeMoneyBillRepository(
            initial = listOf(bill(1, "Power"), bill(2, "Water")),
            paidProposals = listOf(BillPaidProposal(TransactionId(10), BillId(1)), BillPaidProposal(TransactionId(11), BillId(2))),
        )
        val vm = viewModel(repository)
        assertEquals(2, vm.state.value.paidProposals.size)

        vm.acceptPaidProposal(vm.state.value.paidProposals.first { it.bill.id == BillId(1) })
        vm.dismissPaidProposal(vm.state.value.paidProposals.single())

        assertEquals(listOf(TransactionId(10) to BillId(1)), repository.acceptedPaid)
        assertEquals(listOf(TransactionId(11) to BillId(2)), repository.dismissedPaid)
        assertTrue(vm.state.value.paidProposals.isEmpty())
        assertEquals(listOf("Water"), vm.state.value.unpaid.map { it.name })
        assertEquals(listOf("Power"), vm.state.value.paid.map { it.name })
    }

    @Test
    fun aBillProposal_isAcceptedWithTheEnteredFieldsOrDismissed() {
        val repository = FakeMoneyBillRepository(
            proposals = listOf(BillProposal(7, null, "Airtel", Paise(59_900), null), BillProposal(8, null, "Gas", Paise(80_000), due)),
        )
        val vm = viewModel(repository)
        var rejected: FieldErrors<BillField>? = null

        vm.acceptProposal(7, BillForm("Airtel", "599", null, BillRecurrence.NONE)) { rejected = it }
        assertEquals(ValidationError.EMPTY, rejected?.get(BillField.DUE_DATE))
        assertEquals(2, vm.state.value.proposals.size)

        vm.acceptProposal(7, BillForm("Airtel", "599", due, BillRecurrence.MONTHLY)) { rejected = it }
        vm.dismissProposal(8)

        assertNull(rejected)
        assertEquals(listOf(7L), repository.accepted)
        assertEquals(listOf(8L), repository.dismissed)
        assertTrue(vm.state.value.proposals.isEmpty())
    }

    @Test
    fun deletingABill_removesIt() {
        val repository = FakeMoneyBillRepository(listOf(bill(1, "Rent"), bill(2, "Power")))
        val vm = viewModel(repository)

        vm.delete(BillId(1))

        assertEquals(listOf(BillId(1)), repository.deleted)
        assertEquals(listOf("Power"), vm.state.value.unpaid.map { it.name })
    }
}

package app.assistant.ui.money.reviewqueue

import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Paise
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.TxnType
import app.assistant.testing.FakeReviewQueueRepository
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.reviewItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Task 23.1 / Requirements 30.3, 30.4, 30.6, 30.8: saving, dismissing and listing Review_Queue items. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewQueueViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val older = reviewItem(1, "Older message", receivedAt = Instant.parse("2026-11-01T05:00:00Z"))
    private val newer = reviewItem(2, "Newer message", receivedAt = Instant.parse("2026-11-02T05:00:00Z"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: FakeReviewQueueRepository) = ReviewQueueViewModel(repo, FakeTimeSource())

    private val validForm = ReviewForm(TxnType.DEBIT, "1,234.50", LocalDate.of(2026, 11, 2), "Cafe")

    @Test
    fun listsNewestFirstWithTheCount() = runTest(dispatcher) {
        val vm = viewModel(FakeReviewQueueRepository(listOf(older, newer)))

        val ui = vm.state.first { !it.loading }

        assertEquals(listOf(ReviewItemId(2), ReviewItemId(1)), ui.items.map { it.id })
        assertEquals(2, ui.count)
    }

    @Test
    fun invalidSave_keepsTheItemAndReturnsTheFieldErrors() = runTest(dispatcher) {
        val repo = FakeReviewQueueRepository(listOf(newer))
        val vm = viewModel(repo)
        var errors: FieldErrors<EditedField>? = null

        vm.save(newer.id, ReviewForm(null, "abc", LocalDate.of(2026, 11, 2), ""), onResult = { errors = it })

        val result = errors!!
        assertEquals(ValidationError.EMPTY, result[EditedField.TYPE])
        assertEquals(ValidationError.NOT_A_NUMBER, result[EditedField.AMOUNT])
        assertEquals(ValidationError.EMPTY, result[EditedField.MERCHANT])
        assertEquals(1, vm.state.first { !it.loading }.count)
        assertEquals(0, repo.resolved.size)
    }

    @Test
    fun aDateInTheFuture_isRejectedAndTheItemStays() = runTest(dispatcher) {
        val repo = FakeReviewQueueRepository(listOf(newer))
        val vm = viewModel(repo)
        var errors: FieldErrors<EditedField>? = null

        vm.save(newer.id, validForm.copy(date = LocalDate.of(2026, 11, 20)), onResult = { errors = it })

        assertEquals(ValidationError.IN_FUTURE, errors!![EditedField.DATE])
        assertEquals(1, vm.state.first { !it.loading }.count)
    }

    @Test
    fun validSave_createsTheTransactionInputAndRemovesTheItem() = runTest(dispatcher) {
        val repo = FakeReviewQueueRepository(listOf(newer, older))
        val vm = viewModel(repo)
        var called = false
        var errors: FieldErrors<EditedField>? = null

        vm.save(newer.id, validForm) {
            called = true
            errors = it
        }

        assertTrue(called)
        assertNull(errors)
        val input = repo.resolved.single().second
        assertEquals(Paise(123_450), input.amount)
        assertEquals(TxnType.DEBIT, input.type)
        assertEquals("Cafe", input.merchant)
        assertEquals(listOf(ReviewItemId(1)), vm.state.first { it.count == 1 }.items.map { it.id })
    }

    @Test
    fun dismiss_removesTheItem() = runTest(dispatcher) {
        val repo = FakeReviewQueueRepository(listOf(newer, older))
        val vm = viewModel(repo)

        vm.dismiss(newer.id)

        assertEquals(listOf(ReviewItemId(2)), repo.dismissed)
        assertEquals(listOf(ReviewItemId(1)), vm.state.first { it.count == 1 }.items.map { it.id })
    }

    @Test
    fun amountText_isReadWithoutFloatingPoint() {
        assertEquals(Paise(123_450), ReviewAmount.parse("₹1,234.50"))
        assertEquals(Paise(500), ReviewAmount.parse(" 5 "))
        assertEquals(Paise(5), ReviewAmount.parse("0.05"))
        assertNull(ReviewAmount.parse("1.234"))
        assertNull(ReviewAmount.parse("abc"))
        assertNull(ReviewAmount.parse(""))
        assertNull(ReviewAmount.parse("-5"))
    }
}

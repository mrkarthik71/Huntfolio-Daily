package app.assistant.ui.money.budgets

import app.assistant.domain.engine.budget.BudgetField
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.testing.FakeMoneyBudgetRepository
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** Task 23.3 / Requirement 33: Budgets show spent, remaining and percentage; an invalid amount changes nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModelTest {
    private val food = CategoryId(2)
    private val groceries = CategoryId(3)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeMoneyBudgetRepository) = BudgetsViewModel(repository, FakeTimeSource())

    @Test
    fun anOverspentCategory_reportsHowMuchItIsOverAndAllOfItsFigures() {
        val repository = FakeMoneyBudgetRepository(
            initial = mapOf(food to Paise(500_000), groceries to Paise(500_000)),
            spent = mapOf(food to Paise(650_000), groceries to Paise(100_000)),
        )
        val ui = viewModel(repository).state.value

        assertFalse(ui.loading)
        val over = ui.rows.first { it.category.id == food }
        assertEquals("Food", over.category.name)
        assertEquals(Paise(150_000), over.status.overBy)
        assertEquals(130L, over.status.percentUsed)
        val within = ui.rows.first { it.category.id == groceries }
        assertNull(within.status.overBy)
        assertEquals(20L, within.status.percentUsed)
        assertEquals(400_000L, within.status.remaining.value)
    }

    @Test
    fun categoriesThatHaveABudget_areLeftOutOfTheOnesYouCanAdd() {
        val ui = viewModel(FakeMoneyBudgetRepository(initial = mapOf(food to Paise(100_000)))).state.value

        assertTrue(ui.available.none { it.id == food })
        assertTrue(ui.available.any { it.id == groceries })
    }

    @Test
    fun savingAnAmount_storesItInPaise() {
        val repository = FakeMoneyBudgetRepository()
        var result: FieldErrors<BudgetField>? = FieldErrors(mapOf(BudgetField.AMOUNT to ValidationError.EMPTY))

        viewModel(repository).save(food, "\u20B91,500.50") { result = it }

        assertNull(result)
        assertEquals(mapOf(food to Paise(150_050)), repository.amounts)
    }

    @Test
    fun anInvalidAmount_isRejectedWithAFieldErrorAndChangesNothing() {
        val repository = FakeMoneyBudgetRepository(initial = mapOf(food to Paise(100_000)))
        val vm = viewModel(repository)
        val results = mutableListOf<FieldErrors<BudgetField>?>()

        vm.save(food, "") { results += it }
        vm.save(food, "abc") { results += it }
        vm.save(food, "0") { results += it }
        vm.save(food, "123456789012") { results += it }

        assertEquals(ValidationError.EMPTY, results[0]?.get(BudgetField.AMOUNT))
        assertEquals(ValidationError.NOT_A_NUMBER, results[1]?.get(BudgetField.AMOUNT))
        assertEquals(ValidationError.OUT_OF_RANGE, results[2]?.get(BudgetField.AMOUNT))
        assertEquals(ValidationError.OUT_OF_RANGE, results[3]?.get(BudgetField.AMOUNT))
        assertEquals(mapOf(food to Paise(100_000)), repository.amounts)
    }

    @Test
    fun savingWithoutACategory_isRejected() {
        val repository = FakeMoneyBudgetRepository()
        var result: FieldErrors<BudgetField>? = null

        viewModel(repository).save(null, "100") { result = it }

        assertEquals(ValidationError.EMPTY, result?.get(BudgetField.CATEGORY))
        assertTrue(repository.amounts.isEmpty())
    }

    @Test
    fun removingABudget_deletesOnlyThatCategory() {
        val repository = FakeMoneyBudgetRepository(initial = mapOf(food to Paise(100_000), groceries to Paise(200_000)))
        val vm = viewModel(repository)

        vm.remove(food)

        assertEquals(listOf(food), repository.removed)
        assertEquals(listOf(groceries), vm.state.value.rows.map { it.category.id })
    }

    @Test
    fun monthNavigation_asksForThePreviousMonthAndStopsAtTheCurrentOne() {
        val repository = FakeMoneyBudgetRepository()
        val vm = viewModel(repository)
        assertEquals(YearMonth.of(2026, 11), vm.state.value.month)
        assertFalse(vm.state.value.canGoNext)

        vm.nextMonth()
        assertEquals(YearMonth.of(2026, 11), vm.state.value.month)

        vm.previousMonth()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.month)
        assertTrue(vm.state.value.canGoNext)
        assertTrue(YearMonth.of(2026, 10) in repository.requestedMonths)

        vm.nextMonth()
        assertEquals(YearMonth.of(2026, 11), vm.state.value.month)
    }
}

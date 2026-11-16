package app.assistant.ui.money.reports

import app.assistant.domain.engine.reports.CategorySpend
import app.assistant.domain.engine.reports.MonthlyCategoryLine
import app.assistant.domain.engine.reports.MonthlySpendingReport
import app.assistant.domain.engine.reports.WeeklySpendingReport
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Paise
import app.assistant.domain.model.PeriodInstance
import app.assistant.domain.model.SignedPaise
import app.assistant.testing.FakeMoneyReportRepository
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Task 23.3 / Requirement 35: the reports open on the latest period, move between periods, and know when there is nothing to show. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpendingReportsViewModelTest {
    private val october = YearMonth.of(2026, 10)
    private val november = YearMonth.of(2026, 11)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun monthly(month: YearMonth, total: Long) = MonthlySpendingReport(
        month,
        Paise(total),
        listOf(MonthlyCategoryLine(CategoryId(2), "Food", Paise(total), Paise(0), SignedPaise(total), null)),
        Paise.ZERO,
    )

    @Test
    fun opensOnTheLatestReport_andShowsItsCategories() {
        val repository = FakeMoneyReportRepository(months = mapOf(october to monthly(october, 120_000)), latestMonth = october)

        val ui = SpendingReportsViewModel(repository, FakeTimeSource(), app.assistant.domain.port.Advisor.Disabled).state.value

        assertFalse(ui.loading)
        assertEquals(october, ui.selection.month)
        assertEquals(listOf("Food"), ui.monthly?.lines?.map { it.name })
        assertFalse(ui.isEmpty)
        assertTrue(ui.canGoNext)
    }

    @Test
    fun withoutAnyReport_opensOnTheCurrentMonth_andIsEmpty() {
        val vm = SpendingReportsViewModel(FakeMoneyReportRepository(), FakeTimeSource(), app.assistant.domain.port.Advisor.Disabled)

        assertEquals(november, vm.state.value.selection.month)
        assertTrue(vm.state.value.isEmpty)
        assertFalse(vm.state.value.canGoNext)
    }

    @Test
    fun periodNavigation_movesBackAndStopsAtTheCurrentPeriod() {
        val repository = FakeMoneyReportRepository(months = mapOf(october to monthly(october, 50_000)))
        val vm = SpendingReportsViewModel(repository, FakeTimeSource(), app.assistant.domain.port.Advisor.Disabled)

        vm.next()
        assertEquals(november, vm.state.value.selection.month)

        vm.previous()
        assertEquals(october, vm.state.value.selection.month)
        assertEquals(Paise(50_000), vm.state.value.monthly?.total)

        vm.next()
        assertEquals(november, vm.state.value.selection.month)
    }

    @Test
    fun weeklyMode_showsTheWeeksTopCategoriesAndMovesByWholeWeeks() {
        val week = PeriodInstance(GoalPeriod.WEEK, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
        val report = WeeklySpendingReport(week, Paise(90_000), listOf(CategorySpend(CategoryId(2), "Food", Paise(90_000))), Paise.ZERO, Paise.ZERO)
        val repository = FakeMoneyReportRepository(weeks = mapOf(week.first to report), latestWeek = week)
        val vm = SpendingReportsViewModel(repository, FakeTimeSource(), app.assistant.domain.port.Advisor.Disabled)

        vm.setMode(ReportMode.WEEKLY)

        assertEquals(week, vm.state.value.selection.week)
        assertEquals(listOf("Food"), vm.state.value.weekly?.top?.map { it.name })
        assertNull(vm.state.value.monthly)

        vm.previous()
        assertEquals(LocalDate.of(2026, 10, 19), vm.state.value.selection.week.first)
        assertNotNull(vm.state.value.weekly)
        assertTrue(vm.state.value.isEmpty)
    }
}

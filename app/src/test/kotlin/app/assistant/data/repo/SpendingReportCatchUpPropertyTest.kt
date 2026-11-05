// Feature: Phase 3 Money — Property 20 (spending reports): reports are a function of data and period, created on time or by catch-up.
package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.review.ReportCalculator
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.testing.FakeSettingsStore
import app.assistant.testing.FakeTimeSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpendingReportCatchUpPropertyTest {
    private lateinit var db: AppDatabase
    private lateinit var transactions: RoomTransactionRepository
    private lateinit var reports: RoomSpendingReportRepository
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 20)
    private val firstPossible = LocalDate.of(2026, 9, 1)
    private val time = FakeTimeSource(Instant.parse("2026-10-20T04:30:00Z"))

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        val provider = DbProvider { db }
        transactions = RoomTransactionRepository(provider, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        reports = RoomSpendingReportRepository(provider, transactions, FakeSettingsStore(), time)
    }

    @After
    fun tearDown() = db.close()

    private data class Spec(val dayOffset: Int, val amount: Long, val category: Long)

    private val specs = arbitrary {
        val list = ArrayList<Spec>()
        repeat(Arb.int(1..8).bind()) { list += Spec(Arb.int(0..44).bind(), Arb.long(1L..500_000L).bind(), Arb.long(1L..6L).bind()) }
        list.toList()
    }

    private fun reportRows(): Set<String> = db.openHelper.writableDatabase.query("SELECT kind, period_first_day FROM report WHERE kind LIKE 'SPENDING%'")
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) + ":" + c.getString(1) else null }.toSet() }

    private fun clearReports() = db.openHelper.writableDatabase.execSQL("DELETE FROM report WHERE kind LIKE 'SPENDING%'")

    @Test
    fun `Property 20 - the spending reports of ended periods exist whether made on time or by catch-up, and always show the stored data`() {
        runBlocking {
            checkAll(25, specs) { list ->
                MoneyTestDb.reset(db)
                clearReports()
                list.forEachIndexed { i, s ->
                    val at = firstPossible.plusDays(s.dayOffset.toLong()).atTime(12, 0).atZone(zone).toInstant()
                    val added = transactions.addManual(ManualTransactionInput(TxnType.DEBIT, Paise(s.amount), at, "Shop ${i % 3}"))
                    val id = (added as Outcome.Ok).value
                    transactions.changeCategory(id, CategoryId(s.category))
                }
                val stored = transactions.transactions().first()
                val firstDay = stored.minOf { it.at }.atZone(zone).toLocalDate()
                val months = generateSequence(YearMonth.from(firstDay)) { it.plusMonths(1) }.takeWhile { it < YearMonth.from(today) }.toList()
                val weeks = ReportCalculator.endedWeeks(null, today, DayOfWeek.SUNDAY, firstDay)
                val expected = months.map { "SPENDING_MONTHLY:${it.atDay(1)}" }.toSet() + weeks.map { "SPENDING_WEEKLY:${it.first}" }.toSet()

                reports.ensureReportsUpTo(today)
                assertEquals("catch-up creates every ended period", expected, reportRows())
                reports.ensureReportsUpTo(today)
                assertEquals("running it again creates nothing new", expected, reportRows())

                clearReports()
                var day = firstDay
                while (!day.isAfter(today)) {
                    reports.ensureReportsUpTo(day)
                    day = day.plusDays(1)
                }
                assertEquals("daily runs end with the same reports as one catch-up", expected, reportRows())

                fun spentIn(period: (LocalDate) -> Boolean): Long = stored.filter { t -> period(t.at.atZone(zone).toLocalDate()) }.sumOf { t -> t.amount.value }

                weeks.forEach { w ->
                    assertEquals("week ${w.first}", spentIn { !it.isBefore(w.first) && !it.isAfter(w.last) }, reports.weekly(w).total.value)
                }
                months.forEach { m ->
                    val before = reports.monthly(m).total.value
                    assertEquals("month $m", spentIn { YearMonth.from(it) == m }, before)
                    val victim = stored.firstOrNull { YearMonth.from(it.at.atZone(zone)) == m } ?: return@forEach
                    transactions.edit(victim.id, TransactionEdit(amount = Paise(victim.amount.value + 500)))
                    assertEquals("an edit shows in the next display", before + 500, reports.monthly(m).total.value)
                    transactions.delete(victim.id)
                    assertEquals("a delete shows in the next display", before - victim.amount.value, reports.monthly(m).total.value)
                }
            }
        }
    }
}

// Feature: Phase 3 Money — Property 47: Bill matching, proposals and due notifications (matching and notifications).
// Property 6: validators accept exactly the valid region (Bill).
package app.assistant.domain.engine.bills

import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.model.BillId
import app.assistant.domain.model.BillRecurrence
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Paise
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.QueuedAlarm
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private class FakeAlarmStore : AlarmStore {
    val rows = ArrayList<QueuedAlarm>()
    private var nextId = 1L

    override suspend fun put(type: NotificationType, refKey: String, rule: ScheduleRule, dueAt: Instant, isSnooze: Boolean) {
        rows.removeAll { it.type == type && it.refKey == refKey }
        rows += QueuedAlarm(nextId++, type, refKey, rule, dueAt, isSnooze)
    }

    override suspend fun all(): List<QueuedAlarm> = rows.toList()

    override suspend fun due(upTo: Instant): List<QueuedAlarm> = rows.filter { !it.dueAt.isAfter(upTo) }

    override suspend fun earliest(): QueuedAlarm? = rows.minByOrNull { it.dueAt }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteType(type: NotificationType) {
        rows.removeAll { it.type == type }
    }

    override suspend fun setDue(id: Long, dueAt: Instant) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(dueAt = dueAt)
    }
}

class BillMatchingPropertyTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val txnDay = LocalDate.of(2026, 11, 10)

    private fun txn(type: TxnType, merchant: Long?, amount: Long, secondsIntoDay: Long, dayOffset: Long, reversal: Boolean) = Transaction(
        id = TransactionId(1),
        type = type,
        amount = Paise(amount),
        at = txnDay.plusDays(dayOffset).atStartOfDay(zone).toInstant().plusSeconds(secondsIntoDay),
        merchantId = merchant?.let { MerchantId(it) },
        merchantName = null,
        category = CategoryId.UNCATEGORISED,
        source = TxnSource.SMS,
        reference = null,
        account4 = null,
        balance = null,
        isReversal = reversal,
        editedFields = emptySet(),
        categoryUserSet = false,
        sourceRefs = emptyList(),
    )

    private fun bill(id: Long, merchant: Long?, amount: Long, due: LocalDate, paid: Boolean = false, notified: Boolean = false) = BillOccurrence(
        id = BillId(id),
        name = "Bill $id",
        amount = Paise(amount),
        dueDate = due,
        recurrence = BillRecurrence.NONE,
        anchorDay = due.dayOfMonth,
        merchantId = merchant?.let { MerchantId(it) },
        paid = paid,
        seriesId = id,
        notified = notified,
    )

    private class Case(val txn: Transaction, val bills: List<BillOccurrence>)

    private val cases = arbitrary {
        val type = Arb.element(TxnType.DEBIT, TxnType.CREDIT).bind()
        val merchant = Arb.element(null, 1L, 2L).bind()
        val amount = Arb.element(100L, 200L).bind()
        val payment = txn(type, merchant, amount, Arb.long(0L..86_399L).bind(), 0L, reversal = Arb.int(0..9).bind() == 0)
        val bills = ArrayList<BillOccurrence>()
        repeat(Arb.int(0..6).bind()) { i ->
            bills += bill(
                id = i + 1L,
                merchant = Arb.element(null, 1L, 2L).bind(),
                amount = Arb.element(100L, 200L).bind(),
                due = txnDay.plusDays(Arb.long(-9L..9L).bind()),
                paid = Arb.int(0..4).bind() == 0,
            )
        }
        Case(payment, bills.toList())
    }

    @Test
    fun `Property 47 - a Bill is proposed paid exactly when one matches Merchant, exact amount and 5 days, and the nearest due date wins`() = runTest {
        checkAll(PropertyDefaults.thorough, cases) { case ->
            val t = case.txn
            val date = t.at.atZone(zone).toLocalDate()
            val matching = case.bills.filter { b ->
                !b.paid &&
                    b.merchantId != null &&
                    b.merchantId == t.merchantId &&
                    b.amount == t.amount &&
                    Math.abs(b.dueDate.toEpochDay() - date.toEpochDay()) <= 5L
            }
            val eligible = t.type == TxnType.DEBIT && !t.isReversal
            val chosen = BillRules.paidCandidate(t, case.bills, zone)
            if (!eligible || matching.isEmpty()) {
                assertNull(chosen, "no proposal expected")
            } else {
                requireNotNull(chosen) { "a proposal was expected" }
                assertTrue(chosen in matching)
                val nearest = matching.minOf { Math.abs(it.dueDate.toEpochDay() - date.toEpochDay()) }
                assertEquals(nearest, Math.abs(chosen.dueDate.toEpochDay() - date.toEpochDay()), "nearest due date")
            }
        }
    }

    @Test
    fun `five days either side match and six days do not`() {
        val t = txn(TxnType.DEBIT, 1L, 100L, 3_600L, 0L, reversal = false)
        fun matches(offset: Long) = BillRules.paidCandidate(t, listOf(bill(1, 1L, 100L, txnDay.plusDays(offset))), zone) != null
        assertTrue(matches(-5) && matches(5) && matches(0))
        assertTrue(!matches(-6) && !matches(6))
    }

    private val brief = LocalTime.of(8, 0)

    /** A slow reference: walk the days from 2 before the due date and take the first morning brief time not yet passed. */
    private fun referenceDay(due: LocalDate, now: Instant): LocalDate? =
        (-2L..0L).map { due.plusDays(it) }.firstOrNull { !it.atTime(brief).atZone(zone).toInstant().isBefore(now) }

    @Test
    fun `Property 47 - the notification day is the first morning brief time from 2 days before the due date, if any is left`() = runTest {
        checkAll(PropertyDefaults.thorough, Arb.long(-30_000L..30_000L), Arb.long(-6L * 86_400L..6L * 86_400L)) { epochDay, offsetSeconds ->
            val due = LocalDate.ofEpochDay(epochDay)
            val now = due.atStartOfDay(zone).toInstant().plusSeconds(offsetSeconds)
            assertEquals(referenceDay(due, now), BillNotifications.firstDay(due, brief, now, zone), "due=$due now=$now")
        }
    }

    private class AlarmCase(val bills: List<BillOccurrence>, val handled: Set<String>, val enabled: Boolean, val nowOffsetSeconds: Long)

    private val alarmCases = arbitrary {
        val bills = ArrayList<BillOccurrence>()
        repeat(Arb.int(0..8).bind()) { i ->
            bills += bill(
                id = i + 1L,
                merchant = null,
                amount = 100L,
                due = txnDay.plusDays(Arb.long(-4L..6L).bind()),
                paid = Arb.int(0..3).bind() == 0,
                notified = Arb.int(0..4).bind() == 0,
            )
        }
        val handled = bills.filter { Arb.int(0..4).bind() == 0 }.map { BillNotifications.refKey(it.id.value) }.toSet()
        AlarmCase(bills.toList(), handled, Arb.int(0..9).bind() != 0, Arb.long(-3L * 86_400L..3L * 86_400L).bind())
    }

    @Test
    fun `Property 47 - each unpaid occurrence has one alarm at its first morning brief time, and paying or deleting it cancels the alarm`() = runTest {
        checkAll(PropertyDefaults.thorough, alarmCases) { case ->
            val now = txnDay.atStartOfDay(zone).toInstant().plusSeconds(case.nowOffsetSeconds)
            val store = FakeAlarmStore()
            val alarms = BillAlarms(store)

            suspend fun sync(bills: List<BillOccurrence>) = alarms.sync(bills, case.handled, case.enabled, brief, now, zone)

            fun expected(bills: List<BillOccurrence>): Map<String, LocalDate> = if (!case.enabled) {
                emptyMap()
            } else {
                bills.filter { !it.paid && !it.notified && BillNotifications.refKey(it.id.value) !in case.handled }
                    .mapNotNull { b -> referenceDay(b.dueDate, now)?.let { BillNotifications.refKey(b.id.value) to it } }
                    .toMap()
            }

            fun check(bills: List<BillOccurrence>) {
                val want = expected(bills)
                val queued = store.rows.filter { it.type == NotificationType.BILL_DUE }
                assertEquals(want.keys, queued.map { it.refKey }.toSet(), "alarm keys")
                assertEquals(want.size, queued.size, "one alarm per occurrence")
                queued.forEach {
                    val day = want.getValue(it.refKey)
                    assertEquals(ScheduleRule.AtLocalTime(day, brief), it.rule)
                    assertEquals(day.atTime(brief).atZone(zone).toInstant(), it.dueAt)
                }
            }

            sync(case.bills)
            check(case.bills)
            val firstIds = store.rows.map { it.id }
            sync(case.bills)
            check(case.bills)
            assertEquals(firstIds, store.rows.map { it.id }, "a repeated sync changes nothing")

            val victim = case.bills.firstOrNull { BillNotifications.refKey(it.id.value) in expected(case.bills) }
            if (victim != null) {
                val paid = case.bills.map { if (it.id == victim.id) it.copy(paid = true) else it }
                sync(paid)
                check(paid)
                assertTrue(store.rows.none { it.refKey == BillNotifications.refKey(victim.id.value) }, "cancelled on paid")
                val deleted = case.bills.filter { it.id != victim.id }
                sync(deleted)
                check(deleted)
            }
        }
    }

    /** Property 6 for a Bill (Requirements 34.1, 34.6). */
    @Test
    fun `Property 6 - a Bill is valid exactly with a name of 1 to 100 characters, a Valid_Amount and a due date`() = runTest {
        checkAll(
            PropertyDefaults.standard,
            Arb.string(0..130),
            Arb.long(0L..100_000_000_000L).orNull(0.15),
            Arb.boolean(),
            Arb.element(BillRecurrence.entries),
        ) { name, amount, hasDate, recurrence ->
            val input = BillInput(name, amount?.let { Paise(it) }, if (hasDate) txnDay else null, recurrence)
            val expected = buildSet {
                if (name.trim().isEmpty() || name.trim().length > 100) add(BillField.NAME)
                if (amount == null || amount !in 1L..99_999_999_999L) add(BillField.AMOUNT)
                if (!hasDate) add(BillField.DUE_DATE)
            }
            assertEquals(expected, BillValidator.validate(input).errors.keys, "name length ${name.length} amount=$amount date=$hasDate")
        }
    }
}

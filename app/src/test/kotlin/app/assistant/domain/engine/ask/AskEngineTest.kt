package app.assistant.domain.engine.ask

import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.Paise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AskEngineTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val now = today.atTime(18, 30)
    private val food = BuiltinCategories.idOf("Food")!!
    private val shopping = BuiltinCategories.idOf("Shopping")!!

    private fun parse(q: String) = AskParser.parse(q, now)

    @Test
    fun `parser routes common questions`() {
        assertEquals(AskIntent.SPEND, parse("Where did my money go this month?").intent)
        assertEquals(AskPeriod.WEEK, parse("How much did I spend on food this week?").period)
        assertEquals(food, parse("How much did I spend on food this week?").category)
        assertEquals(food, parse("swiggy last month").category)
        assertEquals(AskPeriod.LAST_MONTH, parse("swiggy last month").period)
        assertEquals(AskIntent.SAVE_TIPS, parse("How can I save more?").intent)
        assertEquals(AskIntent.SUBSCRIPTIONS, parse("What subscriptions am I paying for?").intent)
        assertEquals(AskIntent.BILLS, parse("Which bills are due?").intent)
        assertEquals(AskIntent.GOALS, parse("Am I on track with my goals?").intent)
        assertEquals(AskIntent.TODAY, parse("What should I do today?").intent)
        assertEquals(AskIntent.UNKNOWN, parse("Write me a poem").intent)
    }

    @Test
    fun `remember and recall keep the user's words`() {
        val q = parse("Remember that my locker code is 4412")
        assertEquals(AskIntent.REMEMBER, q.intent)
        assertEquals("my locker code is 4412", q.text)
        val notes = listOf(NoteFact(1, "my locker code is 4412", today), NoteFact(2, "wifi password at office", today))
        val r = AskEngine.answer(parse("What did I save about locker?"), AskSnapshot(today, notes = notes)) as AskAnswer.Recall
        assertEquals(listOf(1L), r.notes.map { it.id })
    }

    @Test
    fun `remind parses at, in and tomorrow`() {
        val at = parse("Remind me to call home at 7pm")
        assertEquals("call home", at.text)
        assertEquals(today.atTime(19, 0), at.remindAt)
        val past = parse("remind me to stretch at 9am")
        assertEquals(today.plusDays(1).atTime(9, 0), past.remindAt)
        assertEquals(now.plusMinutes(30), parse("remind me to drink water in 30 min").remindAt)
        assertEquals(today.plusDays(1).atTime(9, 0), parse("remind me to pay rent tomorrow").remindAt)
        assertNull(parse("remind me to pay rent").remindAt)
    }

    @Test
    fun `spend totals by period with the same days of last month for comparison`() {
        val spends = listOf(
            SpendFact(today, Paise(50_000), food, "Swiggy"),
            SpendFact(today.minusDays(3), Paise(150_000), shopping, "Amazon"),
            SpendFact(today.minusMonths(1).minusDays(1), Paise(100_000), food, "Swiggy"),
            // Later in last month than today's day-of-month: excluded from the like-for-like comparison.
            SpendFact(today.minusMonths(1).plusDays(5), Paise(900_000), food, "Swiggy"),
        )
        val s = AskEngine.spend(AskPeriod.MONTH, null, AskSnapshot(today, spends))
        assertEquals(Paise(200_000), s.total)
        assertEquals(Paise(100_000), s.previous)
        assertEquals(listOf("Shopping", "Food"), s.byCategory.map { it.label })
        val f = AskEngine.spend(AskPeriod.MONTH, food, AskSnapshot(today, spends))
        assertEquals(Paise(50_000), f.total)
    }

    @Test
    fun `tips lead with unused subscriptions and rising categories`() {
        val spends = listOf(
            SpendFact(today.minusDays(1), Paise(300_000), food, "Swiggy"),
            SpendFact(today.minusDays(2), Paise(100_000), food, "Swiggy"),
            SpendFact(today.minusMonths(1).minusDays(1), Paise(100_000), food, "Swiggy"),
        )
        val subs = listOf(SubscriptionFact("MusicBox", Paise(11_900), Paise(142_800), today.plusDays(4), unusedFlag = true))
        val tips = AskEngine.tips(AskSnapshot(today, spends, subs))
        assertEquals(TipKind.UNUSED_SUBSCRIPTION, tips[0].kind)
        assertEquals(TipKind.CATEGORY_UP, tips[1].kind)
        assertEquals(Paise(400_000), tips[1].amount)
        assertTrue(tips.size <= 5)
        assertEquals(listOf(TipKind.ALL_GOOD), AskEngine.tips(AskSnapshot(today)).map { it.kind })
    }
}

package app.assistant.domain.engine.life

import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.ListItem
import app.assistant.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PersonalRulesTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun aRepeatingItemComesBackAfterItsRepeat() {
        val milk = ListItem(1, "Groceries", "Milk", done = true, repeatDays = 3, doneOn = today.minusDays(2))
        assertFalse(ListRules.isOpen(milk, today))
        assertTrue(ListRules.isOpen(milk, today.plusDays(1)))
        val once = ListItem(2, "Groceries", "Rice", done = true, repeatDays = null, doneOn = today.minusDays(30))
        assertFalse(ListRules.isOpen(once, today))
    }

    @Test
    fun openItemsComeFirstInEachList() {
        val items = listOf(
            ListItem(1, "Groceries", "Apples", true, null, today),
            ListItem(2, "Groceries", "Bread", false, null, null),
            ListItem(3, "Packing", "Charger", false, null, null),
        )
        val g = ListRules.grouped(items, today)
        assertEquals(listOf("Groceries", "Packing"), g.map { it.first })
        assertEquals("Bread", g[0].second.first().title)
    }

    @Test
    fun keepInTouchPutsTheMostOverdueFirst() {
        val people = listOf(
            KeepInTouch(1, "Ravi", 30, today.minusDays(10)),
            KeepInTouch(2, "Amma", 7, today.minusDays(20)),
            KeepInTouch(3, "Priya", 14, null),
        )
        assertEquals(listOf("Priya", "Amma", "Ravi"), KeepInTouchRules.ordered(people, today).map { it.name })
        assertFalse(KeepInTouchRules.isDue(people[0], today))
        assertTrue(KeepInTouchRules.isDue(people[1], today))
    }

    @Test
    fun noteSearchNeedsEveryWord() {
        val n = Note(1, "Car insurance", "Policy renews in March, agent Suresh", false, Instant.EPOCH)
        assertTrue(NoteSearch.matches(n, "insurance march"))
        assertTrue(NoteSearch.matches(n, "  "))
        assertFalse(NoteSearch.matches(n, "insurance bike"))
    }
}

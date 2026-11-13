package app.assistant.domain.engine.ask

import app.assistant.domain.model.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AskActionsTest {
    private val today = LocalDate.of(2026, 11, 13) // a Friday

    @Test
    fun spends() {
        assertEquals(AskAction.LogSpend(Paise(45_000), "lunch"), AskActions.parse("spent 450 on lunch", today))
        assertEquals(AskAction.LogSpend(Paise(120_050), "petrol"), AskActions.parse("I paid ₹1,200.50 for petrol", today))
        assertEquals(AskAction.LogSpend(Paise(30_000), "auto"), AskActions.parse("auto pe 300 diye", today))
    }

    @Test
    fun listsAndBuying() {
        assertEquals(AskAction.AddToList("Groceries", listOf("milk", "eggs")), AskActions.parse("add milk and eggs to groceries", today))
        assertEquals(AskAction.AddToList("Packing", listOf("charger", "towel")), AskActions.parse("add charger, towel to my packing list", today))
        assertEquals(AskAction.AddToList("Groceries", listOf("bread")), AskActions.parse("buy bread", today))
    }

    @Test
    fun tasksAndMoves() {
        assertEquals(AskAction.AddTask("call the bank", today.plusDays(1)), AskActions.parse("add task call the bank tomorrow", today))
        assertEquals(AskAction.AddTask("file the report", today), AskActions.parse("todo: file the report", today))
        assertEquals(AskAction.AddTask("buy sunscreen", today), AskActions.parse("add task buy sunscreen", today))
        assertEquals(AskAction.MoveTask("report", LocalDate.of(2026, 11, 16)), AskActions.parse("move report to monday", today))
    }

    @Test
    fun peopleAndJournal() {
        assertEquals(AskAction.Talked("Amma"), AskActions.parse("talked to Amma", today))
        assertEquals(AskAction.Journal("a calm, good day"), AskActions.parse("journal: a calm, good day", today))
    }

    @Test
    fun questionsAreNotCommands() {
        assertNull(AskActions.parse("how much did I spend this month?", today))
        assertNull(AskActions.parse("what bills are due", today))
        assertNull(AskActions.parse("move fast", today))
    }
}

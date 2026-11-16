package app.assistant.domain.engine.ask

import app.assistant.domain.model.Paise
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SpokenTextTest {
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0)

    @Test
    fun tidiesWhatRecognisersWrite() {
        assertEquals("Remind me to call Amma at 6 pm", SpokenText.normalise("Remind me to call Amma at 6 p.m."))
        assertEquals("spent 450 on lunch", SpokenText.normalise("spent rupees 450 on lunch."))
        assertEquals("Spent 1200 on petrol", SpokenText.normalise("Spent ₹1,200 on petrol"))
        assertEquals("paid 300 for auto", SpokenText.normalise("paid 300 rupees for auto"))
        assertEquals("remind me to leave at 5:30", SpokenText.normalise("remind me to leave at half past 5"))
        assertEquals("remind me at 7", SpokenText.normalise("remind me at 7 o'clock"))
    }

    @Test
    fun spokenCommandsNowWork() {
        val spend = AskActions.parse(SpokenText.normalise("I spent rupees 450 on lunch."), now.toLocalDate())
        assertEquals(AskAction.LogSpend(Paise(45_000), "lunch"), spend)
        val remind = AskParser.parse(SpokenText.normalise("Remind me to call Amma at 6 p.m."), now)
        assertEquals(AskIntent.REMIND, remind.intent)
        assertEquals(LocalDate.of(2026, 11, 13).atTime(18, 0), remind.remindAt)
        assertEquals("call Amma", remind.text)
    }

    @Test
    fun picksTheGuessAskCanActOn() {
        assertEquals("add milk to groceries", SpokenText.best(listOf("admin to groceries", "add milk to groceries"), now))
        assertEquals("tell me a joke", SpokenText.best(listOf("tell me a joke"), now))
    }
}

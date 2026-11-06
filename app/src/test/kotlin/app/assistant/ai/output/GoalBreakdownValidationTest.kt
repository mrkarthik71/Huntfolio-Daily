package app.assistant.ai.output

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.PromptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalBreakdownValidationTest {
    private val validator = OutputValidator(emptyList())
    private val ctx = OutputContext(knownHandles = setOf("g1", "g2"), payloadText = "g1=Write report\ng2=Finish course\n")

    private fun check(json: String) = validator.validate(PromptType.GOAL_BREAKDOWN, json, ctx)

    @Test
    fun aGoodBreakdownIsAccepted() {
        val steps = """[{"goal":"g1","title":"Draft the outline","size":"S"},{"goal":"g2","title":"Watch a module","size":"L"}]"""
        val r = check("""{"summary":"Steady steps get you there.","steps":$steps}""")
        assertTrue(r is Outcome.Ok)
        val out = (r as Outcome.Ok).value as ValidatedOutput.Breakdown
        assertEquals(2, out.output.steps.size)
    }

    @Test
    fun anUnknownGoalHandleIsRejected() {
        assertEquals(Outcome.Err(OutputError.UNKNOWN_HANDLE), check("""{"steps":[{"goal":"g9","title":"Do it","size":"M"}]}"""))
    }

    @Test
    fun aSizeOutsideSmlIsRejected() {
        assertEquals(Outcome.Err(OutputError.OUT_OF_RANGE), check("""{"steps":[{"goal":"g1","title":"Do it","size":"XL"}]}"""))
    }

    @Test
    fun aNumberNotInThePayloadIsRejected() {
        assertEquals(Outcome.Err(OutputError.NUMBER_NOT_IN_PAYLOAD), check("""{"steps":[{"goal":"g1","title":"Write 12 pages","size":"M"}]}"""))
    }

    @Test
    fun extraFieldsAndEmptyStepsAreRejected() {
        assertEquals(Outcome.Err(OutputError.MALFORMED), check("""{"steps":[{"goal":"g1","title":"Do it","size":"M","url":"x"}]}"""))
        assertEquals(Outcome.Err(OutputError.MALFORMED), check("""{"steps":[]}"""))
    }
}

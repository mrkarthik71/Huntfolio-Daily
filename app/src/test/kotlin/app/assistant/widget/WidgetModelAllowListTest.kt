package app.assistant.widget

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.properties
import com.lemonappdev.konsist.api.ext.list.withName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 12.2 / Requirement 21.2: the home-screen widget may show only a narrow, non-sensitive
 * projection. `WidgetModel` is the single type the widget renders from, and its field set is
 * frozen here: the build fails if a money, Bill, Goal-progress, email or any other field is added.
 */
class WidgetModelAllowListTest {

    private val allowed = setOf("taskId", "title", "done")

    @Test
    fun widgetModelExposesOnlyTheAllowListedFields() {
        val props = Konsist.scopeFromProduction()
            .classes()
            .withName("WidgetModel")
            .properties()
            .map { it.name }
            .toSet()

        assertEquals(
            "WidgetModel fields drifted from the allow-list — add a field only after confirming it carries no sensitive data.",
            allowed,
            props,
        )
    }

    @Test
    fun widgetModelHasNoMoneyBillGoalOrEmailFields() {
        val forbidden = listOf("amount", "paise", "balance", "bill", "goal", "progress", "mail", "email", "snippet")
        val props = Konsist.scopeFromProduction()
            .classes()
            .withName("WidgetModel")
            .properties()
            .map { it.name.lowercase() }

        forbidden.forEach { bad ->
            assertTrue(
                "WidgetModel must not carry a '$bad' field (Requirement 21.2).",
                props.none { it.contains(bad) },
            )
        }
    }
}

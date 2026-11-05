package app.assistant.testing

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig

/**
 * Shared property-test settings. Every property test in the project uses one of these,
 * so the iteration counts promised in the design are set in one place.
 */
@OptIn(ExperimentalKotest::class)
object PropertyDefaults {
    /** Project default: at least 100 generated cases per property. */
    val standard: PropTestConfig = PropTestConfig(iterations = 100)

    /** Parser, dedupe and Redactor properties get 1,000 cases because their input space is larger. */
    val thorough: PropTestConfig = PropTestConfig(iterations = 1_000)
}

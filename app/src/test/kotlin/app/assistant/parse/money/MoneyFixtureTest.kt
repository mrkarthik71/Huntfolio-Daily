package app.assistant.parse.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Task 18.8: each fixture under `fixtures/money` parses to its recorded expected result. */
class MoneyFixtureTest {

    @TestFactory
    fun `every fixture matches its expected result`(): List<DynamicTest> = MoneyFixtures.all().map { f ->
        DynamicTest.dynamicTest(f.id) {
            val result = MessageParser.parse(f.text, f.origin, f.receivedAt.atZone(f.zone).toInstant(), f.zone, TestRegistries.shipped)
            assertEquals(f.expectedResult(), result, "${f.file.path}: ${f.text}")
        }
    }
}

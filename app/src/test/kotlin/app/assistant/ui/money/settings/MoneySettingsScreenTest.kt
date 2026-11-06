package app.assistant.ui.money.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.port.HistoryImportState
import app.assistant.domain.port.HistoryImportSummary
import app.assistant.domain.port.InstalledApp
import app.assistant.domain.port.MoneyReaderState
import app.assistant.testing.FakeAccuracyMonitor
import app.assistant.testing.FakeInstalledApps
import app.assistant.testing.FakeMoneyAppStore
import app.assistant.testing.FakeMoneyReaderControls
import app.assistant.testing.FakeSenderStore
import app.assistant.testing.FakeTimeSource
import app.assistant.ui.designsystem.AssistantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * Task 23.2 and 23.3 / Requirements 26.1, 26.2, 26.4, 26.5, 27.5, 36.2: the money settings screen. The tall screen keeps the
 * whole list composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class MoneySettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        controls: FakeMoneyReaderControls = FakeMoneyReaderControls(),
        senders: FakeSenderStore = FakeSenderStore(listOf("HDFCBK")),
        apps: FakeMoneyAppStore = FakeMoneyAppStore(),
        installed: List<InstalledApp> = emptyList(),
        quality: FakeAccuracyMonitor = FakeAccuracyMonitor(),
    ) {
        val viewModel = MoneySettingsViewModel(controls, senders, apps, FakeInstalledApps(installed), quality, FakeTimeSource())
        compose.setContent { AssistantTheme { MoneySettingsScreen(onBack = {}, viewModel = viewModel) } }
    }

    @Test
    fun theSmsRationaleIsShownBeforeEnabling() {
        val controls = FakeMoneyReaderControls(sms = MoneyReaderState.OFF)
        show(controls)

        compose.onNodeWithText("Read bank and UPI SMS").performClick()

        compose.onNodeWithText("Allow reading bank SMS?").assertIsDisplayed()
        assertEquals("nothing is enabled before the User confirms", 0, controls.smsEnables)

        // Android's own dialog is not shown under Robolectric, so the permission is granted here.
        org.robolectric.Shadows.shadowOf(compose.activity.application)
            .grantPermissions(android.Manifest.permission.READ_SMS, android.Manifest.permission.RECEIVE_SMS)
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(1, controls.smsEnables)
    }

    @Test
    fun decliningTheRationale_enablesNothing() {
        val controls = FakeMoneyReaderControls(sms = MoneyReaderState.OFF)
        show(controls)

        compose.onNodeWithText("Read bank and UPI SMS").performClick()
        compose.onNodeWithText("Not now").performClick()

        compose.waitForIdle()
        assertEquals(0, controls.smsEnables)
        compose.onNodeWithText("Allow reading bank SMS?").assertDoesNotExist()
    }

    @Test
    fun theSmsFreeBuildSaysSmsReadingIsNotPartOfIt() {
        show(FakeMoneyReaderControls(sms = MoneyReaderState.NOT_AVAILABLE))

        compose.onNodeWithText("SMS reading is not part of this build").assertIsDisplayed()
        compose.onNodeWithText("Read bank and UPI SMS").assertDoesNotExist()
    }

    @Test
    fun theHistoryImportOfferAndSummaryAreShown() {
        val controls = FakeMoneyReaderControls(sms = MoneyReaderState.ACTIVE, history = HistoryImportState.Offered)
        show(controls)
        compose.onNodeWithText("Read the bank SMS from the last 3 months once, so your history is filled in.").assertIsDisplayed()

        compose.onNodeWithText("Read last 3 months").performClick()
        compose.waitForIdle()
        assertEquals(1, controls.imports)

        controls.setHistory(HistoryImportState.Done(HistoryImportSummary(smsRead = 40, transactionsCreated = 31, reviewItems = 4)))
        compose.waitForIdle()
        compose.onNodeWithText("Import finished: 40 SMS read, 31 transactions created, 4 added to the review list.").assertIsDisplayed()
    }

    @Test
    fun addingAnInvalidSender_isRejected() {
        show(senders = FakeSenderStore(listOf("HDFCBK")))

        compose.onNodeWithTag(SENDER_INPUT_TAG).performTextInput("bad id!")
        compose.onNodeWithText("Add sender").performClick()

        compose.waitForIdle()
        compose.onNodeWithText("Use 2 to 20 letters or digits, with no spaces or dashes.").assertIsDisplayed()
        compose.onNodeWithText("BAD ID!").assertDoesNotExist()
    }

    @Test
    fun addingAValidSender_listsItInCapitals() {
        show()

        compose.onNodeWithTag(SENDER_INPUT_TAG).performTextInput("sbiupi")
        compose.onNodeWithText("Add sender").performClick()

        compose.waitForIdle()
        compose.onAllNodesWithText("SBIUPI")[0].assertIsDisplayed()
        compose.onNodeWithText("Added by you").assertIsDisplayed()
    }

    @Test
    fun theInstalledAppPickerListsLabelsAndAddsAnApp() {
        val apps = FakeMoneyAppStore()
        show(apps = apps, installed = listOf(InstalledApp("com.example.pay", "Example Pay")))

        compose.onNodeWithText("Example Pay").performClick()

        compose.waitForIdle()
        assertEquals(setOf("com.example.pay"), apps.listedNow)
    }

    @Test
    fun notificationAccessExplainsAndOffersAndroidSettingsWhenPermissionIsMissing() {
        val controls = FakeMoneyReaderControls(notifications = MoneyReaderState.NEEDS_PERMISSION)
        show(controls)

        compose.onNodeWithText("Open Android notification access").performClick()

        assertEquals(1, controls.settingsOpened)
        assertTrue("access is read again when the screen resumes", controls.refreshes >= 1)
    }

    @Test
    fun accuracyShowsAPercentageOrNotEnoughData() {
        show(
            senders = FakeSenderStore(listOf("HDFCBK", "ICICIB")),
            quality = FakeAccuracyMonitor(mapOf("HDFCBK" to AccuracyValue.Percent(BigDecimal("97.5")))),
        )

        compose.onNodeWithText("97.5%").assertIsDisplayed()
        compose.onNodeWithText("Not enough data yet").assertIsDisplayed()
    }
}

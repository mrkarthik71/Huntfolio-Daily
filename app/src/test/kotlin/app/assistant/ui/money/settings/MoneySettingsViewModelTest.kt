package app.assistant.ui.money.settings

import app.assistant.domain.engine.quality.AccuracyValue
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.AddSenderResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** Task 23.2 / Requirements 5.4, 26.2, 26.5, 27.2, 27.5, 36.2: the money settings state and actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoneySettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val controls = FakeMoneyReaderControls()
    private val senders = FakeSenderStore(listOf("HDFCBK"))
    private val apps = FakeMoneyAppStore(setOf("com.example.pay"))
    private val installed = FakeInstalledApps(listOf(InstalledApp("com.example.pay", "Example Pay"), InstalledApp("com.example.bank", "Example Bank")))
    private val quality = FakeAccuracyMonitor(mapOf("HDFCBK" to AccuracyValue.Percent(BigDecimal("97.5"))))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MoneySettingsViewModel(controls, senders, apps, installed, quality, FakeTimeSource())

    @Test
    fun showsAppLabelsAndWhichAppsAreListed() = runTest(dispatcher) {
        val ui = viewModel().state.first { !it.loading }

        assertEquals(
            listOf(MoneyAppRow("com.example.pay", "Example Pay", true), MoneyAppRow("com.example.bank", "Example Bank", false)),
            ui.apps,
        )
    }

    @Test
    fun addingAndRemovingAnApp_changesTheList() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setApp("com.example.bank", true)
        vm.state.first { s -> s.apps.any { it.packageName == "com.example.bank" && it.listed } }

        vm.setApp("com.example.pay", false)
        val ui = vm.state.first { s -> s.apps.any { it.packageName == "com.example.pay" && !it.listed } }
        assertEquals(setOf("com.example.bank"), ui.apps.filter { it.listed }.map { it.packageName }.toSet())
    }

    @Test
    fun anInvalidAppId_isReported() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setApp("nope", true)

        assertEquals(AddAppResult.INVALID, vm.state.first { it.messages.app != null }.messages.app)
    }

    @Test
    fun anInvalidSender_isRejectedAndNothingIsAdded() = runTest(dispatcher) {
        val vm = viewModel()

        vm.addSender("not valid!")

        val ui = vm.state.first { it.messages.sender != null }
        assertEquals(AddSenderResult.INVALID, ui.messages.sender)
        assertEquals(listOf("HDFCBK"), ui.senders.map { it.entry.coreHeader })
    }

    @Test
    fun aValidSender_isAddedInCapitalsAndCanBeRemoved_butABuiltInOneCannot() = runTest(dispatcher) {
        val vm = viewModel()

        vm.addSender(" sbiupi ")
        assertEquals(listOf("HDFCBK", "SBIUPI"), vm.state.first { it.senders.size == 2 }.senders.map { it.entry.coreHeader })
        assertNull(vm.state.value.messages.sender)

        vm.removeSender("HDFCBK")
        assertEquals(2, vm.state.value.senders.size)

        vm.removeSender("SBIUPI")
        assertEquals(listOf("HDFCBK"), vm.state.first { it.senders.size == 1 }.senders.map { it.entry.coreHeader })
    }

    @Test
    fun aDuplicateSender_isReported() = runTest(dispatcher) {
        val vm = viewModel()

        vm.addSender("hdfcbk")

        assertEquals(AddSenderResult.ALREADY_LISTED, vm.state.first { it.messages.sender != null }.messages.sender)
    }

    @Test
    fun accuracyIsShownPerOrigin_andNoDataForTheRest() = runTest(dispatcher) {
        val ui = viewModel().state.first { !it.loading }

        val byOrigin = ui.accuracy.associate { it.origin to it.value }
        assertEquals(AccuracyValue.Percent(BigDecimal("97.5")), byOrigin["HDFCBK"])
        assertEquals(AccuracyValue.NoData, byOrigin["com.example.pay"])
    }

    @Test
    fun smsReading_isOnlyEnabledWhenTheViewModelIsAskedAfterTheRationale() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.first { !it.loading }
        assertEquals(0, controls.smsEnables)

        vm.enableSms()

        assertEquals(1, controls.smsEnables)
        assertEquals(MoneyReaderState.ACTIVE, vm.state.first { it.readers.sms == MoneyReaderState.ACTIVE }.readers.sms)
    }

    @Test
    fun historyImportOfferAndSummary_comeFromTheReader() = runTest(dispatcher) {
        val vm = viewModel()
        controls.setHistory(HistoryImportState.Offered)
        assertEquals(HistoryImportState.Offered, vm.state.first { it.readers.historyImport == HistoryImportState.Offered }.readers.historyImport)

        vm.runHistoryImport()
        vm.declineHistoryImport()
        val done = HistoryImportState.Done(HistoryImportSummary(smsRead = 40, transactionsCreated = 31, reviewItems = 4))
        controls.setHistory(done)

        assertEquals(1, controls.imports)
        assertEquals(1, controls.declines)
        assertEquals(done, vm.state.first { it.readers.historyImport == done }.readers.historyImport)
    }

    @Test
    fun notificationAccess_flowsThroughSettingsAndARefresh() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setNotifications(true)
        assertEquals(MoneyReaderState.NEEDS_PERMISSION, vm.state.first { it.readers.notifications == MoneyReaderState.NEEDS_PERMISSION }.readers.notifications)

        vm.openNotificationAccessSettings()
        controls.grantNotificationAccess()
        vm.refreshNotifications()

        assertEquals(1, controls.settingsOpened)
        assertEquals(1, controls.refreshes)
        assertEquals(MoneyReaderState.ACTIVE, vm.state.first { it.readers.notifications == MoneyReaderState.ACTIVE }.readers.notifications)
    }
}

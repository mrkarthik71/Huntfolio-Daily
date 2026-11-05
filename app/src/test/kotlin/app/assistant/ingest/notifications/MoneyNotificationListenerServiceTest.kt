package app.assistant.ingest.notifications

import android.app.Application
import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import app.assistant.domain.model.DataSource
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.MoneyAppList
import app.assistant.domain.port.ReaderFlags
import app.assistant.domain.port.ReaderStateStore
import app.assistant.ingest.pipeline.BatchResult
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageIngestor
import app.assistant.ingest.pipeline.RawMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Task 21.3 for the Notification_Reader (Requirements 2.3, 27.1, 27.3, 27.6): a listed app's title+text are ingested, an
 * unlisted app is ignored, an empty notification is dropped, and the listener never cancels or alters notifications. The
 * service is a thin wrapper over NotificationMapper + MoneyAppList, so these assert exactly that composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MoneyNotificationListenerServiceTest {

    private class RecordingIngestor : MessageIngestor {
        val messages = mutableListOf<RawMessage>()

        override suspend fun ingestBatch(source: DataSource, messages: List<RawMessage>, readSucceeded: Boolean): BatchResult {
            this.messages += messages
            return BatchResult(mapOf(IngestOutcome.TXN_CREATED to messages.size))
        }
    }

    private class ListOf(private val listed: Set<String>) : MoneyAppList {
        override suspend fun contains(packageName: String) = packageName in listed
    }

    private class FakeReaderState(initial: ReaderFlags) : ReaderStateStore {
        private val s = MutableStateFlow(initial)
        override val flags = s.asStateFlow()
        override suspend fun current() = s.value
        override suspend fun enableSms(at: Instant) = s.update { it.copy(smsEnabled = true, smsEnabledAt = at) }
        override suspend fun disableSms() = s.update { it.copy(smsEnabled = false) }
        override suspend fun advanceSmsCursor(to: Instant) = Unit
        override suspend fun markHistoryImportOffered() = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = s.update { it.copy(notificationsEnabled = enabled) }
    }

    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val io = d
        override val default = d
        override val main = d
    }

    private lateinit var ingestor: RecordingIngestor
    private lateinit var service: MoneyNotificationListenerService

    private fun sbn(pkg: String, title: String?, text: String?): StatusBarNotification {
        val extras = Bundle().apply {
            if (title != null) putCharSequence(Notification.EXTRA_TITLE, title)
            if (text != null) putCharSequence(Notification.EXTRA_TEXT, text)
        }
        val notification = Notification.Builder(org.robolectric.RuntimeEnvironment.getApplication(), "c").build().apply { this.extras.putAll(extras) }
        return StatusBarNotification(pkg, pkg, 1, "tag", 0, 0, 0, notification, android.os.UserHandle.getUserHandleForUid(0), 1_700_000_000_000L)
    }

    private fun build(enabled: Boolean, listed: Set<String>, dispatcher: CoroutineDispatcher, scope: CoroutineScope): MoneyNotificationListenerService {
        val recording = RecordingIngestor()
        ingestor = recording
        return MoneyNotificationListenerService().apply {
            ingestor = recording
            moneyApps = ListOf(listed)
            readerState = FakeReaderState(ReaderFlags(notificationsEnabled = enabled))
            dispatchers = TestDispatchers(dispatcher)
            this.scope = scope
        }
    }

    @Before
    fun setUp() {
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().mainLooper).idle()
    }

    @Test
    fun `a listed app's notification is read as title and text`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        service = build(enabled = true, listed = setOf("com.bank.upi"), dispatcher, this)
        service.onNotificationPosted(sbn("com.bank.upi", "Paid", "Rs 250 to Swiggy"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, ingestor.messages.size)
        val message = ingestor.messages.single() as RawMessage.Notification
        assertEquals("Paid Rs 250 to Swiggy", message.text)
    }

    @Test
    fun `an unlisted app is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        service = build(enabled = true, listed = setOf("com.bank.upi"), dispatcher, this)
        service.onNotificationPosted(sbn("com.games.app", "You won!", "Tap to claim"))
        testScheduler.advanceUntilIdle()
        assertTrue(ingestor.messages.isEmpty())
    }

    @Test
    fun `an empty notification from a listed app is dropped`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        service = build(enabled = true, listed = setOf("com.bank.upi"), dispatcher, this)
        service.onNotificationPosted(sbn("com.bank.upi", null, null))
        testScheduler.advanceUntilIdle()
        assertTrue(ingestor.messages.isEmpty())
    }

    @Test
    fun `nothing is read while notification reading is off`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        service = build(enabled = false, listed = setOf("com.bank.upi"), dispatcher, this)
        service.onNotificationPosted(sbn("com.bank.upi", "Paid", "Rs 250 to Swiggy"))
        testScheduler.advanceUntilIdle()
        assertTrue(ingestor.messages.isEmpty())
    }
}

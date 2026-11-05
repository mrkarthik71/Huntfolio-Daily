package app.assistant.ingest.notifications

import android.app.Application
import app.assistant.data.db.DbProvider
import app.assistant.data.repo.MoneyTestDb
import app.assistant.data.repo.RoomMoneyAppStore
import app.assistant.domain.port.AddAppResult
import app.assistant.domain.port.MoneyAppRules
import app.assistant.ingest.pipeline.IngestOutcome
import app.assistant.ingest.pipeline.MessageGen
import app.assistant.ingest.pipeline.PipelineFixture
import app.assistant.ingest.pipeline.RawMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Money_App_List the User edits (Requirements 27.2, 27.3, 27.7), against the real pipeline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MoneyAppStoreTest {
    private lateinit var fx: PipelineFixture
    private lateinit var store: RoomMoneyAppStore

    @Before
    fun setUp() {
        fx = PipelineFixture()
        store = RoomMoneyAppStore(DbProvider { fx.db })
    }

    @After
    fun tearDown() = fx.close()

    private fun apps(): Set<String> = runBlocking { store.apps().first() }

    private fun upiPayment(key: String, amount: Long, reference: String): RawMessage {
        val pay = MessageGen.Pay(amount, 5, 14, 6, "Swiggy", "1234", reference, 0)
        return RawMessage.Notification(MessageGen.UPI_PACKAGE, key, "", MessageGen.upiDebit(pay), MessageGen.RECEIVED)
    }

    @Test
    fun `apps can be added, listed once and removed`() {
        assertEquals(setOf(MessageGen.UPI_PACKAGE), apps())
        assertEquals(AddAppResult.ADDED, runBlocking { store.add("com.bank.app") })
        assertEquals(AddAppResult.ALREADY_LISTED, runBlocking { store.add("com.bank.app") })
        assertEquals(setOf(MessageGen.UPI_PACKAGE, "com.bank.app"), apps())
        runBlocking { store.remove("com.bank.app") }
        runBlocking { store.remove("com.bank.app") }
        assertEquals(setOf(MessageGen.UPI_PACKAGE), apps())
    }

    @Test
    fun `a name that cannot be an application id is refused`() {
        listOf("", "  ", "nodots", "com..double", "1com.start", "com.bad name", "com.bank.", ".com.bank", "a".repeat(300) + ".b").forEach {
            assertEquals(it, AddAppResult.INVALID, runBlocking { store.add(it) })
            assertFalse(it, MoneyAppRules.isValidPackage(it))
        }
        assertTrue(MoneyAppRules.isValidPackage("com.example.upiapp"))
        assertTrue(MoneyAppRules.isValidPackage("in.co.bank_1.App2"))
        assertEquals(setOf(MessageGen.UPI_PACKAGE), apps())
    }

    @Test
    fun `an app that is removed is no longer read, and what it made is kept`() {
        assertEquals(IngestOutcome.TXN_CREATED, fx.ingest(upiPayment("k1", 25_000, "REF111111")))
        val before = fx.transactionList()
        assertEquals(1, before.size)

        runBlocking { store.remove(MessageGen.UPI_PACKAGE) }
        // Anything it posts afterwards is dropped without a trace (Requirements 27.3, 27.7) ...
        val counts = MoneyTestDb.rowCounts(fx.db)
        assertEquals(IngestOutcome.DROPPED_UNLISTED, fx.ingest(upiPayment("k2", 99_900, "REF222222")))
        assertEquals(counts, MoneyTestDb.rowCounts(fx.db))
        // ... and the Transactions already made are kept.
        assertEquals(before, fx.transactionList())

        // Put back, it is read again.
        runBlocking { store.add(MessageGen.UPI_PACKAGE) }
        assertEquals(IngestOutcome.TXN_CREATED, fx.ingest(upiPayment("k3", 99_900, "REF222222")))
        assertEquals(2, fx.transactionList().size)
    }
}

package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.MergeSide
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.testing.FakeTimeSource
import app.assistant.testing.RecordingQualityMonitor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A correction is an edit or merge that changes the type, amount, merchant or date of a Transaction made from messages
 * (Requirement 36.1). Counting never changes the outcome of the edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrackedTransactionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var room: RoomTransactionRepository
    private lateinit var monitor: RecordingQualityMonitor
    private lateinit var repo: TrackedTransactionRepository
    private val time = FakeTimeSource()

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        room = RoomTransactionRepository(DbProvider { db }, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        monitor = RecordingQualityMonitor()
        repo = TrackedTransactionRepository(room, monitor)
    }

    @After
    fun tearDown() = db.close()

    private fun txns(): List<Transaction> = runBlocking { room.transactions().first() }

    private fun ingest(id: Int, merchant: String = "Swiggy", amount: Long = 25_000, atSecond: Long = 0) {
        runBlocking { room.ingest(fact(id, merchant = merchant, atSecond = atSecond, amount = amount, origin = "BANKA")) }
    }

    @Test
    fun `an edit that changes the amount counts a correction for each message of the Transaction`() {
        ingest(1)
        val t = txns().single()
        assertEquals(Outcome.Ok(Unit), runBlocking { repo.edit(t.id, TransactionEdit(amount = Paise(26_000))) })
        assertEquals(t.sourceRefs, monitor.corrections)
    }

    @Test
    fun `an edit that changes nothing counted is not a correction`() {
        ingest(1)
        val t = txns().single()
        runBlocking { repo.edit(t.id, TransactionEdit(amount = t.amount)) }
        assertTrue(monitor.corrections.isEmpty())
    }

    @Test
    fun `an invalid edit is not a correction`() {
        ingest(1)
        val t = txns().single()
        val result = runBlocking { repo.edit(t.id, TransactionEdit(amount = Paise(0))) }
        assertTrue(result is Outcome.Err)
        assertTrue(monitor.corrections.isEmpty())
    }

    @Test
    fun `a merge that changes the surviving values counts a correction and one that does not counts nothing`() {
        ingest(1, amount = 10_000)
        ingest(2, merchant = "Amazon", amount = 25_000, atSecond = 86_400)
        ingest(3, merchant = "Zomato", amount = 30_000, atSecond = 172_800)
        val (a, b, c) = txns().sortedBy { it.id.value }

        // Keeping the lower Transaction's own values changes nothing.
        runBlocking { repo.merge(a.id, b.id, MergeSelection()) }
        assertTrue(monitor.corrections.isEmpty())

        // Taking the other Transaction's amount does.
        runBlocking { repo.merge(c.id, a.id, MergeSelection(amount = MergeSide.FIRST)) }
        assertEquals(txns().single().sourceRefs.toSet(), monitor.corrections.toSet())
    }

    @Test
    fun `a counter that fails never fails the edit`() {
        repo = TrackedTransactionRepository(room, RecordingQualityMonitor(fails = true))
        ingest(1)
        val t = txns().single()
        assertEquals(Outcome.Ok(Unit), runBlocking { repo.edit(t.id, TransactionEdit(amount = Paise(26_000))) })
        assertEquals(Paise(26_000), txns().single().amount)
    }
}

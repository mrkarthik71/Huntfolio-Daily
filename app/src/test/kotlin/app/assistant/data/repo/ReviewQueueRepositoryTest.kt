package app.assistant.data.repo

import android.app.Application
import android.database.Cursor
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.dedupe.BASE
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.Paise
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.IngestError
import app.assistant.domain.port.ReviewAddResult
import app.assistant.domain.port.ReviewItem
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** The Review_Queue on an in-memory Room database (task 20.5, Requirements 30.1 to 30.4, 30.6 to 30.8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewQueueRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var transactions: RoomTransactionRepository
    private lateinit var queue: RoomReviewQueueRepository
    private val time = FakeTimeSource()

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        transactions = RoomTransactionRepository(DbProvider { db }, time, DuplicateWindowProvider { Duration.ofMinutes(15) })
        queue = RoomReviewQueueRepository(DbProvider { db }, time, transactions)
    }

    @After
    fun tearDown() = db.close()

    private val smsRef = SourceRef("sms:v1:AAAA")
    private val appRef = SourceRef("ntf:v1:BBBB")
    private val origin = MessageOrigin("BANKA")
    private val marker = "ZQXMARKER"
    private val text = "Rs [ACCOUNT] debited $marker at [NAME]"

    private fun add(ref: SourceRef = smsRef, receivedAt: Instant = BASE, body: String = text) = runBlocking { queue.add(ref, body, origin, receivedAt) }

    private fun open(): List<ReviewItem> = runBlocking { queue.openItemsNewestFirst().first() }

    private fun valid(merchant: String = "Swiggy") = ManualTransactionInput(TxnType.DEBIT, Paise(25_000), BASE, merchant)

    /** Every text value in every table, to show where a string is stored. */
    private fun allText(): List<String> = db.openHelper.writableDatabase.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'",
    )
        .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        .flatMap { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { c ->
                generateSequence {
                    if (c.moveToNext()) c else null
                }.flatMap { row -> (0 until row.columnCount).mapNotNull { row.stringOrNull(it) } }.toList()
            }
        }

    private fun Cursor.stringOrNull(i: Int): String? = if (getType(i) == Cursor.FIELD_TYPE_STRING) getString(i) else null

    // Requirement 30.1

    @Test
    fun `an unreadable message becomes one item holding its redacted text, origin and time`() {
        assertEquals(ReviewAddResult.ADDED, add())
        val item = open().single()
        assertEquals(smsRef, item.sourceRef)
        assertEquals(text, item.redactedText)
        assertEquals(origin, item.origin)
        assertEquals(BASE, item.receivedAt)
        assertEquals(1, runBlocking { queue.openCount().first() })
        assertEquals(item, runBlocking { queue.get(item.id) })
    }

    @Test
    fun `the same Source_Ref never makes a second item`() {
        add()
        assertEquals(ReviewAddResult.ALREADY_OPEN, add(body = "different text"))
        val item = open().single()
        assertEquals(text, item.redactedText)
    }

    @Test
    fun `only SMS and app notification refs are reviewed`() {
        val error = runCatching { runBlocking { queue.add(SourceRef("cal:v1:CCCC"), text, origin, BASE) } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(open().isEmpty())
    }

    // Requirement 30.8

    @Test
    fun `items are listed newest Received_Time first`() {
        add(SourceRef("sms:v1:ONE"), receivedAt = BASE)
        add(SourceRef("sms:v1:THREE"), receivedAt = BASE.plusSeconds(200))
        add(SourceRef("sms:v1:TWO"), receivedAt = BASE.plusSeconds(100))
        assertEquals(listOf("sms:v1:THREE", "sms:v1:TWO", "sms:v1:ONE"), open().map { it.sourceRef.value })
    }

    @Test
    fun `items received at the same time list the later one first`() {
        add(SourceRef("sms:v1:FIRST"))
        add(SourceRef("sms:v1:SECOND"))
        assertEquals(listOf("sms:v1:SECOND", "sms:v1:FIRST"), open().map { it.sourceRef.value })
    }

    // Requirement 30.4

    @Test
    fun `dismissing deletes the item and its text and the message never comes back`() {
        add()
        val id = open().single().id
        runBlocking { queue.dismiss(id) }

        assertTrue(open().isEmpty())
        assertNull(runBlocking { queue.get(id) })
        assertTrue(allText().none { it.contains(marker) })
        assertEquals(ReviewAddResult.TOMBSTONED, add())
        assertTrue(open().isEmpty())
        val reason = db.openHelper.writableDatabase.query("SELECT reason FROM tombstone WHERE source_ref = '${smsRef.value}'").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals("REVIEW_DISMISSED", reason)
    }

    @Test
    fun `dismissing a missing item does nothing`() {
        runBlocking { queue.dismiss(ReviewItemId(404)) }
        assertTrue(open().isEmpty())
    }

    // Requirement 32.7

    @Test
    fun `a message of a deleted Transaction makes no item`() {
        val stored = (runBlocking { transactions.ingest(fact(1, merchant = "Swiggy")) } as Outcome.Ok).value
        val ref = fact(1).sourceRef
        runBlocking { transactions.delete(stored.transaction) }
        assertEquals(ReviewAddResult.TOMBSTONED, add(ref))
        assertTrue(open().isEmpty())
    }

    // Requirement 30.3

    @Test
    fun `resolving creates the Transaction with the entered fields marked as edited and deletes the item and text`() {
        add()
        val id = open().single().id
        val result = runBlocking { queue.resolve(id, valid()) }

        val created = checkNotNull(runBlocking { transactions.get((result as Outcome.Ok).value) })
        assertEquals(listOf(smsRef), created.sourceRefs)
        assertEquals(TxnSource.SMS, created.source)
        assertEquals(Paise(25_000), created.amount)
        assertEquals(BASE, created.at)
        assertEquals("Swiggy", created.merchantName)
        assertEquals(EditedField.entries.toSet(), created.editedFields)

        assertTrue(open().isEmpty())
        assertNull(runBlocking { queue.get(id) })
        assertTrue("the redacted text must not survive resolving", allText().none { it.contains(marker) })
    }

    @Test
    fun `a notification item resolves to an APP Transaction`() {
        add(appRef)
        val id = open().single().id
        val created = checkNotNull(runBlocking { transactions.get((runBlocking { queue.resolve(id, valid()) } as Outcome.Ok).value) })
        assertEquals(TxnSource.APP, created.source)
    }

    @Test
    fun `resolving goes through the Deduplicator and joins a Transaction for the same payment`() {
        // A notification for the payment was already read; the SMS for it was not understood.
        val existing = (
            runBlocking {
                transactions.ingest(fact(1, SourceKind.APP, TxnType.DEBIT, 25_000, atSecond = 20, origin = "PAYAPP", merchant = "Swiggy"))
            } as Outcome.Ok
            ).value
        add()
        val id = open().single().id
        val result = runBlocking { queue.resolve(id, valid()) }

        assertEquals(Outcome.Ok(existing.transaction), result)
        val only = runBlocking { transactions.transactions().first() }.single()
        assertEquals(2, only.sourceRefs.size)
        assertEquals(TxnSource.MIXED, only.source)
        assertTrue(open().isEmpty())
    }

    @Test
    fun `resolving twice finds nothing the second time`() {
        add()
        val id = open().single().id
        assertTrue(runBlocking { queue.resolve(id, valid()) } is Outcome.Ok)
        assertEquals(Outcome.Err(EditFailure.NotFound), runBlocking { queue.resolve(id, valid()) })
        assertEquals(1, runBlocking { transactions.transactions().first() }.size)
    }

    // Requirement 30.6

    @Test
    fun `an invalid save is rejected, names the field, and keeps the item and its text`() {
        add()
        val id = open().single().id
        val before = open()
        val bad = ManualTransactionInput(TxnType.DEBIT, Paise(0), time.now().plus(Duration.ofDays(2)), "   ")
        val failure = (runBlocking { queue.resolve(id, bad) } as Outcome.Err).error as EditFailure.Invalid

        assertEquals(ValidationError.OUT_OF_RANGE, failure.errors[EditedField.AMOUNT])
        assertEquals(ValidationError.IN_FUTURE, failure.errors[EditedField.DATE])
        assertEquals(ValidationError.EMPTY, failure.errors[EditedField.MERCHANT])
        assertEquals(before, open())
        assertTrue(runBlocking { transactions.transactions().first() }.isEmpty())
        assertTrue(allText().any { it.contains(marker) })
    }

    @Test
    fun `a missing field is rejected too`() {
        add()
        val id = open().single().id
        val failure = (runBlocking { queue.resolve(id, ManualTransactionInput(null, null, null, "")) } as Outcome.Err).error as EditFailure.Invalid
        assertEquals(setOf(EditedField.TYPE, EditedField.AMOUNT, EditedField.DATE, EditedField.MERCHANT), failure.errors.errors.keys)
        assertNotNull(runBlocking { queue.get(id) })
    }

    @Test
    fun `an item whose Source_Ref was tombstoned meanwhile is dropped when resolved`() {
        add()
        val id = open().single().id
        db.openHelper.writableDatabase.execSQL("INSERT INTO tombstone (source_ref, reason, at) VALUES ('${smsRef.value}', 'TXN_DELETED', 1)")
        assertEquals(Outcome.Err(EditFailure.NotFound), runBlocking { queue.resolve(id, valid()) })
        assertTrue(open().isEmpty())
        assertTrue(runBlocking { transactions.transactions().first() }.isEmpty())
        assertEquals(Outcome.Err(IngestError.TOMBSTONED), runBlocking { transactions.ingest(fact(9).copy(sourceRef = smsRef)) })
    }
}

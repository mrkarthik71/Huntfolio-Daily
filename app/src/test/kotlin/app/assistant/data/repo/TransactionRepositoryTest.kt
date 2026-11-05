package app.assistant.data.repo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.money.MoneySeeder
import app.assistant.domain.engine.categorise.CategorisedTransaction
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.engine.dedupe.BASE
import app.assistant.domain.engine.dedupe.EditedField
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.MergeSide
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TxnSource
import app.assistant.domain.error.Outcome
import app.assistant.domain.error.ValidationError
import app.assistant.domain.model.BuiltinCategories
import app.assistant.domain.model.CategoryId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.CategoryChangeError
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.IngestError
import app.assistant.domain.port.IngestResult
import app.assistant.testing.FakeTimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Transaction storage on an in-memory Room database (task 20.2): dedupe persistence, edits, merge, delete,
 * manual add and category corrections (Requirements 29, 31, 32). Runs under Robolectric on framework SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransactionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomTransactionRepository
    private val time = FakeTimeSource()
    private val window = DuplicateWindowProvider { Duration.ofMinutes(15) }

    @Before
    fun setUp() {
        db = newDatabase()
        repo = RoomTransactionRepository(DbProvider { db }, time, window)
    }

    @After
    fun tearDown() = db.close()

    private fun newDatabase(): AppDatabase {
        val created = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { MoneySeeder { File("src/main/assets/$it").readText() }.seed(created) }
        return created
    }

    private fun txns(): List<Transaction> = runBlocking { repo.transactions().first() }

    private fun ingest(f: ParsedFact): IngestResult = (runBlocking { repo.ingest(f) } as Outcome.Ok).value

    private fun category(name: String) = checkNotNull(BuiltinCategories.idOf(name))

    private fun sms(id: Int, merchant: String? = "Swiggy", atSecond: Long = 0, amount: Long = 25_000, reference: String? = null) =
        fact(id, merchant = merchant, atSecond = atSecond, amount = amount, reference = reference, origin = "BANKA")

    private fun app(id: Int, merchant: String? = "Swiggy", atSecond: Long = 30, amount: Long = 25_000, reference: String? = null) =
        fact(id, kind = SourceKind.APP, merchant = merchant, atSecond = atSecond, amount = amount, reference = reference, origin = "PAYAPP")

    // Requirement 29.1, 29.2: link or create

    @Test
    fun `an SMS and an app notification for one payment become one Transaction`() {
        val first = ingest(sms(1))
        val second = ingest(app(2))
        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.transaction, second.transaction)
        val only = txns().single()
        assertEquals(TxnSource.MIXED, only.source)
        assertEquals(2, only.sourceRefs.size)
        assertEquals(Paise(25_000), only.amount)
    }

    @Test
    fun `a different amount or a time outside the window makes a separate Transaction`() {
        ingest(sms(1))
        ingest(app(2, amount = 25_001))
        ingest(app(3, atSecond = 3_600))
        assertEquals(3, txns().size)
    }

    @Test
    fun `ingesting the same message twice changes nothing`() {
        val f = sms(1)
        val first = ingest(f)
        val before = txns()
        val again = ingest(f)
        assertTrue(again.alreadyStored)
        assertEquals(first.transaction, again.transaction)
        assertEquals(before, txns())
    }

    @Test
    fun `a manual fact cannot be ingested`() {
        val manual = fact(1, kind = SourceKind.MANUAL)
        val error = runCatching { runBlocking { repo.ingest(manual) } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // Requirement 29.4: arrival order does not matter

    /** What a Transaction means, without its row id, so two stores can be compared. */
    private fun shape(list: List<Transaction>) = list.map {
        listOf(it.type, it.amount, it.at, it.reference, it.account4, it.merchantName, it.source, it.isReversal, it.category, it.sourceRefs.map { r -> r.value })
    }.sortedBy { it.toString() }

    @Test
    fun `every arrival order of the same messages gives the same Transactions`() {
        val messages = listOf(
            sms(1, reference = "R1"),
            app(2, reference = "R1"),
            sms(3, merchant = "Amazon", amount = 99_900, atSecond = 4_000),
            app(4, merchant = "Amazon", amount = 99_900, atSecond = 4_050),
        )
        val results = permutations(messages).map { order ->
            db.close()
            db = newDatabase()
            repo = RoomTransactionRepository(DbProvider { db }, time, window)
            order.forEach { ingest(it) }
            shape(txns())
        }
        assertEquals(1, results.toSet().size)
        assertEquals(2, results.first().size)
    }

    @Test
    fun `a later message can pull two stored Transactions into one and the lower id survives`() {
        val firstMessage = fact(1, atSecond = 0, origin = "BANKA", receivedAfter = 5, merchant = "Swiggy")
        val lastMessage = fact(2, kind = SourceKind.APP, atSecond = 1_200, origin = "PAYAPP", merchant = "Swiggy")
        val middle = fact(3, atSecond = 600, origin = "BANKC", receivedAfter = -700, merchant = "Swiggy")
        val a = ingest(firstMessage)
        val b = ingest(lastMessage)
        assertEquals(2, txns().size)

        val c = ingest(middle)
        val left = txns().single()
        assertEquals(a.transaction, left.id)
        assertTrue(a.transaction.value < b.transaction.value)
        assertEquals(3, left.sourceRefs.size)
        assertEquals(a.transaction, c.transaction)
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

    // Requirement 31: categories

    @Test
    fun `a known Merchant gets its default category`() {
        ingest(sms(1, merchant = "SWIGGY"))
        val t = txns().single()
        assertEquals(category("Food"), t.category)
        assertNotNull(t.merchantId)
    }

    @Test
    fun `an unknown Merchant is created with itself as name and alias and the Transaction is Uncategorised`() {
        ingest(sms(1, merchant = "  Corner Chai Stall "))
        val t = txns().single()
        assertEquals(CategoryId.UNCATEGORISED, t.category)
        assertEquals("Corner Chai Stall", t.merchantName)
        // The same text in another case finds the Merchant that was created, not a second one.
        ingest(sms(2, merchant = "corner chai stall", atSecond = 7_200))
        assertEquals(1, txns().mapNotNull { it.merchantId }.toSet().size)
    }

    @Test
    fun `a message with no Merchant is Uncategorised and has no Merchant`() {
        ingest(sms(1, merchant = null))
        val t = txns().single()
        assertNull(t.merchantId)
        assertEquals(CategoryId.UNCATEGORISED, t.category)
    }

    @Test
    fun `a Transaction created after a correction takes the corrected category`() {
        ingest(sms(1, atSecond = 0))
        val first = txns().single()
        runBlocking { repo.changeCategory(first.id, category("Shopping")) }
        time.advance(Duration.ofMinutes(1))
        ingest(sms(2, atSecond = 7_200))
        val newest = txns().first { it.id != first.id }
        assertEquals(category("Shopping"), newest.category)
    }

    @Test
    fun `changing a category offers to change earlier ones and applying skips those set by the User`() {
        ingest(sms(1, atSecond = 0))
        ingest(sms(2, atSecond = 7_200))
        ingest(sms(3, atSecond = 14_400))
        ingest(sms(4, atSecond = 21_600))
        val byTime = txns().sortedBy { it.at }
        // The User set the second one's category on its own earlier.
        runBlocking { repo.changeCategory(byTime[1].id, category("Entertainment")) }

        val offer = (runBlocking { repo.changeCategory(byTime[3].id, category("Shopping")) } as Outcome.Ok).value
        assertNotNull(offer)
        // Earlier than the fourth: the first and third change; the second was set individually.
        assertEquals(2, offer!!.count)
        val before = txns().sortedBy { it.at }
        assertEquals(2, runBlocking { repo.applyCategoryToEarlier(offer) })
        val after = txns().sortedBy { it.at }

        // The storage query agrees with the pure rule.
        val expected = Categoriser.applyCorrectionToEarlier(
            before.take(3).map { CategorisedTransaction(it.id, it.merchantId, it.category, it.categoryUserSet) },
            offer.merchant,
            offer.category,
        )
        assertEquals(expected.map { it.category }, after.take(3).map { it.category })
        assertEquals(category("Entertainment"), after[1].category)
        assertEquals(category("Shopping"), after[3].category)
    }

    @Test
    fun `no offer is made when there is nothing earlier to change or no Merchant`() {
        ingest(sms(1))
        val only = txns().single()
        assertNull((runBlocking { repo.changeCategory(only.id, category("Shopping")) } as Outcome.Ok).value)

        ingest(sms(2, merchant = null, atSecond = 7_200, amount = 77_700))
        val noMerchant = txns().first { it.merchantId == null }
        assertNull((runBlocking { repo.changeCategory(noMerchant.id, category("Food")) } as Outcome.Ok).value)
        assertEquals(category("Food"), txns().first { it.merchantId == null }.category)
    }

    @Test
    fun `a category change to something unknown or on a missing Transaction is refused`() {
        ingest(sms(1))
        val id = txns().single().id
        assertEquals(Outcome.Err(CategoryChangeError.UNKNOWN_CATEGORY), runBlocking { repo.changeCategory(id, CategoryId(9_999)) })
        assertEquals(Outcome.Err(CategoryChangeError.NOT_FOUND), runBlocking { repo.changeCategory(TransactionId(9_999), category("Food")) })
        assertEquals(category("Food"), txns().single().category)
    }

    @Test
    fun `later messages do not change the category of earlier Transactions after a correction`() {
        ingest(sms(1, atSecond = 0))
        val first = txns().single()
        // A correction for the Merchant, declined for earlier ones.
        ingest(sms(2, atSecond = 7_200))
        val second = txns().first { it.id != first.id }
        runBlocking { repo.changeCategory(second.id, category("Shopping")) }
        // Another message for the first payment arrives afterwards.
        ingest(app(3, atSecond = 20))
        assertEquals(category("Food"), txns().first { it.id == first.id }.category)
    }

    // Requirement 32.1, 32.2: edits

    @Test
    fun `an edit is stored and marked, and later messages for the payment do not change it`() {
        ingest(sms(1))
        val id = txns().single().id
        assertEquals(Outcome.Ok(Unit), runBlocking { repo.edit(id, TransactionEdit(amount = Paise(26_000), merchant = "Amazon")) })
        ingest(app(2, amount = 25_000, merchant = "Swiggy"))

        val t = txns().single()
        assertEquals(Paise(26_000), t.amount)
        assertEquals("Amazon", t.merchantName)
        assertEquals(setOf(EditedField.AMOUNT, EditedField.MERCHANT), t.editedFields)
        assertEquals(2, t.sourceRefs.size)
        // A field the User did not edit still follows the messages.
        assertEquals(BASE, t.at)
    }

    @Test
    fun `an invalid edit is rejected and changes nothing`() {
        ingest(sms(1))
        val before = txns().single()
        val future = time.now().plus(Duration.ofDays(3))
        val result = runBlocking { repo.edit(before.id, TransactionEdit(amount = Paise(0), at = future, merchant = "a".repeat(101))) }
        val errors = (result as Outcome.Err<EditFailure>).error as EditFailure.Invalid
        assertEquals(ValidationError.OUT_OF_RANGE, errors.errors[EditedField.AMOUNT])
        assertEquals(ValidationError.IN_FUTURE, errors.errors[EditedField.DATE])
        assertEquals(ValidationError.TOO_LONG, errors.errors[EditedField.MERCHANT])
        assertEquals(before, txns().single())
    }

    @Test
    fun `editing a missing Transaction is reported`() {
        val result = runBlocking { repo.edit(TransactionId(404), TransactionEdit(amount = Paise(1))) }
        assertEquals(Outcome.Err(EditFailure.NotFound), result)
    }

    @Test
    fun `changing the Merchant of an edit recategorises unless the User set the category`() {
        ingest(sms(1))
        val id = txns().single().id
        runBlocking { repo.edit(id, TransactionEdit(merchant = "Amazon")) }
        assertEquals(category("Shopping"), txns().single().category)

        runBlocking { repo.changeCategory(id, category("Health")) }
        runBlocking { repo.edit(id, TransactionEdit(merchant = "Swiggy")) }
        assertEquals(category("Health"), txns().single().category)
    }

    @Test
    fun `clearing the Merchant on an edit leaves it empty even when a message names one`() {
        ingest(sms(1))
        val id = txns().single().id
        runBlocking { repo.edit(id, TransactionEdit(merchant = "")) }
        ingest(app(2, merchant = "Swiggy"))
        val t = txns().single()
        assertNull(t.merchantId)
        assertTrue(EditedField.MERCHANT in t.editedFields)
    }

    // Requirement 32.3, 32.6: manual add

    @Test
    fun `a manual Transaction has source MANUAL and no Source_Ref, and is never merged with a message`() {
        val input = ManualTransactionInput(TxnType.DEBIT, Paise(25_000), BASE, "Swiggy")
        val id = (runBlocking { repo.addManual(input) } as Outcome.Ok).value
        val manual = checkNotNull(runBlocking { repo.get(id) })
        assertEquals(TxnSource.MANUAL, manual.source)
        assertTrue(manual.sourceRefs.isEmpty())
        assertEquals(EditedField.entries.toSet(), manual.editedFields)
        assertEquals(category("Food"), manual.category)

        // An SMS for the very same payment makes its own Transaction.
        ingest(sms(1))
        assertEquals(2, txns().size)
    }

    @Test
    fun `an invalid manual Transaction is rejected and nothing is stored`() {
        val result = runBlocking { repo.addManual(ManualTransactionInput(TxnType.DEBIT, Paise(100), BASE, "   ")) }
        assertEquals(ValidationError.EMPTY, ((result as Outcome.Err).error).errors[EditedField.MERCHANT])
        assertTrue(txns().isEmpty())
        val future = runBlocking { repo.addManual(ManualTransactionInput(TxnType.CREDIT, Paise(100), time.now().plus(Duration.ofDays(2)), "X")) }
        assertEquals(ValidationError.IN_FUTURE, ((future as Outcome.Err).error).errors[EditedField.DATE])
        assertTrue(txns().isEmpty())
    }

    // Requirement 32.5, 32.7: delete and tombstones

    @Test
    fun `a deleted Transaction stays gone when its messages arrive again`() {
        ingest(sms(1))
        ingest(app(2))
        val id = txns().single().id
        runBlocking { repo.delete(id) }
        assertTrue(txns().isEmpty())
        assertNull(runBlocking { repo.get(id) })

        assertEquals(Outcome.Err(IngestError.TOMBSTONED), runBlocking { repo.ingest(sms(1)) })
        assertEquals(Outcome.Err(IngestError.TOMBSTONED), runBlocking { repo.ingest(app(2)) })
        assertTrue(txns().isEmpty())
        // Only the deleted messages are blocked; a new one makes a new Transaction.
        ingest(sms(3, atSecond = 7_200))
        assertEquals(1, txns().size)
    }

    @Test
    fun `deleting a manual Transaction removes it and deleting a missing one does nothing`() {
        val id = (runBlocking { repo.addManual(ManualTransactionInput(TxnType.DEBIT, Paise(100), BASE, "Cash")) } as Outcome.Ok).value
        runBlocking { repo.delete(id) }
        runBlocking { repo.delete(id) }
        assertTrue(txns().isEmpty())
    }

    // Requirement 32.4, 32.8: merge

    @Test
    fun `merging keeps the lower id with all Source_Refs and the selected values`() {
        ingest(sms(1, merchant = "Swiggy", amount = 10_000, atSecond = 0))
        ingest(sms(2, merchant = "Amazon", amount = 25_000, atSecond = 86_400))
        val (a, b) = txns().sortedBy { it.id.value }

        val result = runBlocking { repo.merge(b.id, a.id, MergeSelection(amount = MergeSide.FIRST, date = MergeSide.SECOND, merchant = MergeSide.FIRST)) }
        assertEquals(Outcome.Ok(a.id), result)

        val merged = txns().single()
        assertEquals(a.id, merged.id)
        assertEquals(2, merged.sourceRefs.size)
        assertEquals(Paise(25_000), merged.amount)
        assertEquals(a.at, merged.at)
        assertEquals("Amazon", merged.merchantName)
        assertEquals(category("Shopping"), merged.category)
        assertEquals(setOf(EditedField.AMOUNT, EditedField.DATE, EditedField.MERCHANT), merged.editedFields)
    }

    @Test
    fun `merged Transactions stay merged when another message arrives`() {
        ingest(sms(1, amount = 10_000, atSecond = 0))
        ingest(sms(2, merchant = "Amazon", amount = 25_000, atSecond = 86_400))
        val (a, b) = txns().sortedBy { it.id.value }
        runBlocking { repo.merge(a.id, b.id, MergeSelection()) }

        // A notification for the second payment joins the merged Transaction.
        val joined = ingest(app(3, merchant = "Amazon", amount = 25_000, atSecond = 86_430))
        assertFalse(joined.created)
        val only = txns().single()
        assertEquals(a.id, only.id)
        assertEquals(3, only.sourceRefs.size)
    }

    @Test
    fun `a debit and a credit do not merge and both stay unchanged`() {
        ingest(sms(1))
        ingest(fact(2, type = TxnType.CREDIT, merchant = "Swiggy", atSecond = 7_200, origin = "BANKA"))
        val before = txns()
        val (a, b) = before.sortedBy { it.id.value }
        assertEquals(Outcome.Err(MergeError.DIFFERENT_TYPE), runBlocking { repo.merge(a.id, b.id, MergeSelection()) })
        assertEquals(before, txns())
    }

    @Test
    fun `merging a Transaction with itself or a missing one is refused`() {
        ingest(sms(1))
        val id = txns().single().id
        assertEquals(Outcome.Err(MergeError.SAME_TRANSACTION), runBlocking { repo.merge(id, id, MergeSelection()) })
        assertEquals(Outcome.Err(MergeError.NOT_FOUND), runBlocking { repo.merge(id, TransactionId(404), MergeSelection()) })
        assertEquals(1, txns().size)
    }

    @Test
    fun `a manual Transaction can merge with one built from a message`() {
        val manual = (runBlocking { repo.addManual(ManualTransactionInput(TxnType.DEBIT, Paise(25_000), BASE, "Cash")) } as Outcome.Ok).value
        val smsId = ingest(sms(1, merchant = "Swiggy")).transaction
        val result = runBlocking { repo.merge(manual, smsId, MergeSelection(merchant = MergeSide.SECOND)) }
        assertEquals(Outcome.Ok(manual), result)
        val merged = txns().single()
        assertEquals(manual, merged.id)
        assertEquals(1, merged.sourceRefs.size)
        assertEquals("Swiggy", merged.merchantName)
        assertEquals(TxnSource.SMS, merged.source)
    }

    // Stored facts hold no message text (Requirement 3.1): only fields.

    @Test
    fun `no table the repository writes has a column that could hold message text`() {
        val columns = listOf("parsed_fact", "txn", "tombstone", "forced_merge", "merchant", "merchant_alias", "category_correction").flatMap { table ->
            db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            }
        }
        assertTrue(columns.none { it.contains("text") || it.contains("body") || it.contains("message") })
    }
}

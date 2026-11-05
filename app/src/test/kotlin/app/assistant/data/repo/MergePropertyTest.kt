// Feature: Phase 3 Money — Property 44: Merge keeps all Source_Refs and only same-type pairs merge.
package app.assistant.data.repo

import android.app.Application
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.domain.engine.categorise.Categoriser
import app.assistant.domain.engine.dedupe.BASE
import app.assistant.domain.engine.dedupe.fact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.MergeSide
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.TransactionId
import app.assistant.domain.model.TxnType
import app.assistant.domain.port.DuplicateWindowProvider
import app.assistant.testing.FakeTimeSource
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

private enum class Kind { SMS, SMS_AND_APP, MANUAL }

private data class TxnSpec(val kind: Kind, val type: TxnType, val amount: Long, val merchant: String?)

private data class MergeScenario(val specs: List<TxnSpec>, val first: Int, val second: Int, val selection: MergeSelection)

/**
 * Property 44 on the real repository (Requirements 32.4, 32.8): for any two stored Transactions, a merge succeeds exactly
 * when they have the same type; then one Transaction holds both Source_Ref sets and the selected values and the other is
 * gone; a rejected merge changes nothing, and Transactions not involved are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MergePropertyTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomTransactionRepository

    @Before
    fun setUp() {
        db = MoneyTestDb.create()
        repo = RoomTransactionRepository(DbProvider { db }, FakeTimeSource(), DuplicateWindowProvider { Duration.ofMinutes(15) })
    }

    @After
    fun tearDown() = db.close()

    private val scenarios = arbitrary {
        val count = Arb.int(2..5).bind()
        val specs = ArrayList<TxnSpec>()
        repeat(count) {
            val kind = Arb.element(Kind.entries).bind()
            val merchant = Arb.element(listOf(null, "Swiggy", "Amazon", "Corner Shop", "  SWIGGY ")).bind()
            specs += TxnSpec(
                kind = kind,
                type = Arb.element(TxnType.entries).bind(),
                amount = Arb.element(listOf(1_000L, 25_000L, 99_900L)).bind(),
                // A manual Transaction must have a Merchant (Requirement 32.6).
                merchant = if (kind == Kind.MANUAL) merchant ?: "Cash" else merchant,
            )
        }
        val first = Arb.int(0 until count).bind()
        val offset = Arb.int(1 until count).bind()
        MergeScenario(
            specs,
            first,
            (first + offset) % count,
            MergeSelection(Arb.element(MergeSide.entries).bind(), Arb.element(MergeSide.entries).bind(), Arb.element(MergeSide.entries).bind()),
        )
    }

    /** Stores each spec as its own Transaction, a day apart so none can be mistaken for another's duplicate. */
    private suspend fun store(specs: List<TxnSpec>): List<TransactionId> {
        var next = 1
        return specs.mapIndexed { index, spec ->
            val day = index * 86_400L
            when (spec.kind) {
                Kind.MANUAL -> (
                    repo.addManual(
                        ManualTransactionInput(spec.type, Paise(spec.amount), BASE.plusSeconds(day), checkNotNull(spec.merchant)),
                    ) as Outcome.Ok
                    ).value
                Kind.SMS -> (
                    repo.ingest(
                        fact(next++, type = spec.type, amount = spec.amount, atSecond = day, merchant = spec.merchant, origin = "BANKA"),
                    ) as Outcome.Ok
                    ).value.transaction
                Kind.SMS_AND_APP -> {
                    val sms = repo.ingest(fact(next++, type = spec.type, amount = spec.amount, atSecond = day, merchant = spec.merchant, origin = "BANKA"))
                    repo.ingest(fact(next++, SourceKind.APP, spec.type, spec.amount, day + 30, origin = "PAYAPP", merchant = spec.merchant))
                    (sms as Outcome.Ok).value.transaction
                }
            }
        }
    }

    private suspend fun all(): List<Transaction> = repo.transactions().first().sortedBy { it.id.value }

    @Test
    fun `Property 44 - a merge succeeds only for the same type and then keeps every Source_Ref`() {
        runBlocking {
            checkAll(200, scenarios) { s ->
                MoneyTestDb.reset(db)
                val ids = store(s.specs)
                val before = all()
                val a = before.first { it.id == ids[s.first] }
                val b = before.first { it.id == ids[s.second] }

                val result = repo.merge(a.id, b.id, s.selection)

                if (a.type != b.type) {
                    assertEquals(Outcome.Err(MergeError.DIFFERENT_TYPE), result)
                    assertEquals(before, all())
                    return@checkAll
                }
                val survivorId = if (a.id.value < b.id.value) a.id else b.id
                val victimId = if (survivorId == a.id) b.id else a.id
                assertEquals(Outcome.Ok(survivorId), result)

                val after = all()
                assertEquals(before.size - 1, after.size)
                assertNull(repo.get(victimId))
                assertTrue(after.none { it.id == victimId })

                val merged = after.single { it.id == survivorId }
                // Every Source_Ref of both, and nothing else.
                assertEquals((a.sourceRefs + b.sourceRefs).map { it.value }.sorted(), merged.sourceRefs.map { it.value }.sorted())
                // Where the two disagree the selected side's value is the one kept.
                assertEquals(if (a.amount == b.amount) a.amount else pick(s.selection.amount, a, b) { it.amount }, merged.amount)
                assertEquals(if (a.at == b.at) a.at else pick(s.selection.date, a, b) { it.at }, merged.at)
                val sameMerchant = Categoriser.normalise(a.merchantName.orEmpty()) == Categoriser.normalise(b.merchantName.orEmpty())
                if (!sameMerchant) assertEquals(pick(s.selection.merchant, a, b) { it.merchantName }, merged.merchantName)
                assertEquals(a.type, merged.type)
                // Transactions that were not merged are untouched.
                assertEquals(before.filter { it.id != a.id && it.id != b.id }, after.filter { it.id != survivorId })
            }
        }
    }

    private fun <T> pick(side: MergeSide, a: Transaction, b: Transaction, field: (Transaction) -> T): T = field(if (side == MergeSide.FIRST) a else b)
}

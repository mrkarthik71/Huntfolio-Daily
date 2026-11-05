package app.assistant.data.repo

import app.assistant.domain.engine.quality.QualityMonitor
import app.assistant.domain.engine.transactions.MergeError
import app.assistant.domain.engine.transactions.MergeSelection
import app.assistant.domain.engine.transactions.Transaction
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Transaction repository the app uses: it is [RoomTransactionRepository] plus the Quality_Monitor's correction count.
 * A correction is an edit or merge that changes the type, amount, merchant or date of a Transaction that came from
 * messages (Requirement 36.1). Counting never changes the outcome of the edit.
 */
@Singleton
class TrackedTransactionRepository @Inject constructor(private val inner: RoomTransactionRepository, private val quality: QualityMonitor) :
    TransactionRepository by inner {
    override suspend fun edit(id: TransactionId, edit: TransactionEdit): Outcome<Unit, EditFailure> {
        val before = inner.get(id)
        val result = inner.edit(id, edit)
        if (result is Outcome.Ok) countCorrection(before, inner.get(id))
        return result
    }

    override suspend fun merge(first: TransactionId, second: TransactionId, selection: MergeSelection): Outcome<TransactionId, MergeError> {
        // The lower id survives, so its values are what the merge may change (Requirement 32.4).
        val survivor = inner.get(if (first.value < second.value) first else second)
        val result = inner.merge(first, second, selection)
        if (result is Outcome.Ok) countCorrection(survivor, inner.get(result.value))
        return result
    }

    private suspend fun countCorrection(before: Transaction?, after: Transaction?) {
        if (before == null || after == null || !changed(before, after)) return
        for (ref in after.sourceRefs) {
            try {
                quality.recordCorrection(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                // A counter that cannot be written must not undo or fail the User's edit.
            }
        }
    }

    private fun changed(before: Transaction, after: Transaction): Boolean =
        before.type != after.type || before.amount != after.amount || before.merchantId != after.merchantId || before.at != after.at
}

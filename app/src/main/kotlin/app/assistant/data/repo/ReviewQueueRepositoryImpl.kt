package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.ReviewItemEntity
import app.assistant.data.db.entity.TombstoneEntity
import app.assistant.domain.engine.dedupe.MessageOrigin
import app.assistant.domain.engine.dedupe.ParsedFact
import app.assistant.domain.engine.transactions.ManualTransactionInput
import app.assistant.domain.engine.transactions.TransactionEdit
import app.assistant.domain.engine.transactions.TransactionValidator
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.Paise
import app.assistant.domain.model.ReviewItemId
import app.assistant.domain.model.SourceKind
import app.assistant.domain.model.SourceRef
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.EditFailure
import app.assistant.domain.port.ReviewAddResult
import app.assistant.domain.port.ReviewItem
import app.assistant.domain.port.ReviewQueueRepository
import app.assistant.domain.port.TimeSource
import app.assistant.domain.port.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messages the parsers could not read (Requirement 30). An item holds the redacted text only until the User resolves
 * or dismisses it; both delete the row, so the text never outlives the decision (Requirement 30.2).
 */
@Singleton
class RoomReviewQueueRepository @Inject constructor(
    private val holder: DbProvider,
    private val time: TimeSource,
    private val transactions: TransactionRepository,
) : ReviewQueueRepository {
    private val db get() = holder.database()
    private val dao get() = db.reviewQueueDao()

    override suspend fun add(
        ref: SourceRef,
        redactedText: String,
        origin: MessageOrigin,
        receivedAt: Instant,
        fallbackAttempted: Boolean,
        proposalJson: String?,
    ): ReviewAddResult {
        require(ref.source == DataSource.SMS || ref.source == DataSource.APP_NOTIFICATIONS) { "Only SMS and app notifications are reviewed" }
        return db.withTransaction {
            // A dismissed message, or one of a deleted Transaction, never comes back (Requirements 30.4, 32.7).
            if (db.transactionDao().tombstoneCount(ref.value) > 0) return@withTransaction ReviewAddResult.TOMBSTONED
            val id = dao.insert(
                ReviewItemEntity(
                    sourceRef = ref.value,
                    redactedText = redactedText,
                    origin = origin.value,
                    receivedAt = receivedAt.toEpochMilli(),
                    fallbackAttempted = fallbackAttempted,
                    proposalJson = proposalJson,
                ),
            )
            if (id == IGNORED) ReviewAddResult.ALREADY_OPEN else ReviewAddResult.ADDED
        }
    }

    override suspend fun get(id: ReviewItemId): ReviewItem? = dao.item(id.value)?.toDomain()

    override fun openItemsNewestFirst(): Flow<List<ReviewItem>> = dao.observe().map { list -> list.map { it.toDomain() } }

    override fun openCount(): Flow<Int> = dao.observeCount()

    override suspend fun resolve(id: ReviewItemId, input: ManualTransactionInput): Outcome<TransactionId, EditFailure> {
        // Checked before anything is touched, so a rejected save leaves the item and its text as they were (Requirement 30.6).
        val errors = TransactionValidator.validateNew(input, time.today(), time.zone())
        if (!errors.isValid) return Outcome.Err(EditFailure.Invalid(errors))
        return db.withTransaction {
            val item = dao.item(id.value) ?: return@withTransaction Outcome.Err(EditFailure.NotFound)
            val ref = SourceRef(item.sourceRef)
            val type = checkNotNull(input.type)
            val amount: Paise = checkNotNull(input.amount)
            val at = checkNotNull(input.at)
            val merchant = input.merchant.trim()
            val fact = ParsedFact(
                sourceRef = ref,
                origin = MessageOrigin(item.origin),
                sourceKind = if (ref.source == DataSource.SMS) SourceKind.SMS else SourceKind.APP,
                type = type,
                amount = amount,
                at = at,
                receivedAt = Instant.ofEpochMilli(item.receivedAt),
                reference = null,
                account4 = null,
                merchantOrVpa = merchant,
                balance = null,
                isReversal = false,
            )
            val stored = transactions.ingest(fact, FORMAT_MANUAL_REVIEW)
            if (stored is Outcome.Err) {
                // The Source_Ref was tombstoned after the item was added; the item has no purpose left.
                dao.delete(id.value)
                return@withTransaction Outcome.Err(EditFailure.NotFound)
            }
            val transaction = (stored as Outcome.Ok).value.transaction
            // The fields the User typed are theirs: no message may change them later (Requirement 30.3).
            val edited = transactions.edit(transaction, TransactionEdit(type = type, amount = amount, at = at, merchant = merchant))
            check(edited is Outcome.Ok) { "Input was validated, so the edit cannot fail" }
            dao.delete(id.value)
            Outcome.Ok(transaction)
        }
    }

    override suspend fun dismiss(id: ReviewItemId) {
        db.withTransaction {
            val item = dao.item(id.value) ?: return@withTransaction
            db.transactionDao().putTombstones(listOf(TombstoneEntity(item.sourceRef, reason = REVIEW_DISMISSED, at = time.now().toEpochMilli())))
            dao.delete(id.value)
        }
    }

    override suspend fun markFallbackAttempted(id: ReviewItemId, proposalJson: String?) {
        dao.markFallbackAttempted(id.value, proposalJson)
    }

    private companion object {
        const val IGNORED = -1L
        const val REVIEW_DISMISSED = "REVIEW_DISMISSED"

        /** The Bank_Format id stored for a fact the User typed in from a Review_Queue item. */
        const val FORMAT_MANUAL_REVIEW = "manual-review"
    }
}

internal fun ReviewItemEntity.toDomain() = ReviewItem(
    id = ReviewItemId(id),
    sourceRef = SourceRef(sourceRef),
    redactedText = redactedText,
    origin = MessageOrigin(origin),
    receivedAt = Instant.ofEpochMilli(receivedAt),
    fallbackAttempted = fallbackAttempted,
    proposalJson = proposalJson,
)

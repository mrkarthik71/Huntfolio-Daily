package app.assistant.data.repo

import androidx.room.withTransaction
import app.assistant.data.db.AppDatabase
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.SubscriptionChargeEntity
import app.assistant.data.db.entity.SubscriptionEntity
import app.assistant.data.db.entity.SuggestionEntity
import app.assistant.data.db.entity.UsageCheckEntity
import app.assistant.domain.engine.subscriptions.Charge
import app.assistant.domain.engine.subscriptions.DueDateRule
import app.assistant.domain.engine.subscriptions.SubscriptionDetector
import app.assistant.domain.engine.subscriptions.SubscriptionSnapshot
import app.assistant.domain.engine.subscriptions.UsageCheckRules
import app.assistant.domain.engine.suggestions.ProposedChange
import app.assistant.domain.engine.suggestions.SuggestionKind
import app.assistant.domain.engine.suggestions.SuggestionManager
import app.assistant.domain.engine.suggestions.SuggestionSnapshot
import app.assistant.domain.error.FieldErrors
import app.assistant.domain.error.Outcome
import app.assistant.domain.model.MerchantId
import app.assistant.domain.model.Paise
import app.assistant.domain.model.SubscriptionInterval
import app.assistant.domain.model.SubscriptionStatus
import app.assistant.domain.model.TransactionId
import app.assistant.domain.port.DueUsageCheck
import app.assistant.domain.port.ManualSubscriptionField
import app.assistant.domain.port.ManualSubscriptionInput
import app.assistant.domain.port.ManualSubscriptionValidator
import app.assistant.domain.port.SubscriptionRepository
import app.assistant.domain.port.SubscriptionView
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores Subscriptions and runs their lifecycle (Requirements 37-41, 50). The detector is pure; this class feeds it the
 * stored debit charges, writes the proposals, and applies confirm/reject/manual/usage/suggestion changes in Room
 * transactions. Nothing is applied until the User acts (Requirement 2.4). Cancellation records a date only — no external
 * request (Requirement 40.3).
 */
@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val holder: DbProvider,
    private val time: TimeSource,
) : SubscriptionRepository {

    private fun db(): AppDatabase = holder.database()

    override fun proposals(): Flow<List<SubscriptionView>> =
        db().subscriptionDao().observeByStatus(SubscriptionStatus.PROPOSED.name).map { rows -> rows.map { view(it) } }

    override fun active(): Flow<List<SubscriptionView>> =
        db().subscriptionDao().observeByStatus(SubscriptionStatus.ACTIVE.name).map { rows -> rows.map { view(it) } }

    override fun totalYearlyCost(): Flow<Paise> =
        db().subscriptionDao().observeByStatus(SubscriptionStatus.ACTIVE.name).map { rows -> Paise.sum(rows.map { SubscriptionDetector.yearlyCost(Paise(it.amountPaise), interval(it.interval)) }) }

    override fun suggestions(): Flow<List<SuggestionSnapshot>> =
        db().subscriptionDao().observeSuggestions().map { rows -> SuggestionManager.visible(rows.map { it.toSnapshot() }, time.now()) }

    override fun dueUsageChecks(): Flow<List<DueUsageCheck>> =
        db().subscriptionDao().observeByStatus(SubscriptionStatus.ACTIVE.name).map { subs ->
            val today = time.today()
            subs.filter { UsageCheckRules.isDue(LocalDate.parse(it.activatedOn), it.lastUsageCheck?.let(LocalDate::parse), today) }
                .map { DueUsageCheck(it.id, merchantName(it.merchantId), Paise(it.amountPaise)) }
        }

    override suspend fun redetect() {
        val dao = db().subscriptionDao()
        db().withTransaction {
            val charges = loadDebitCharges()
            val existing = dao.all().map { it.toSnapshot() }
            val proposals = SubscriptionDetector.detect(charges, existing)
            for (p in proposals) {
                dao.insert(
                    SubscriptionEntity(
                        merchantId = p.merchantId.value,
                        amountPaise = p.amount.value,
                        interval = p.interval.name,
                        anchorDay = p.anchorDay,
                        nextDue = p.nextDue.toString(),
                        activatedOn = time.today().toString(),
                        lastUsageCheck = null,
                        cancelledOn = null,
                        consecutiveNo = 0,
                        status = SubscriptionStatus.PROPOSED.name,
                    ),
                )
            }
        }
    }

    override suspend fun confirmProposal(subscriptionId: Long) = updateStatus(subscriptionId, SubscriptionStatus.PROPOSED, SubscriptionStatus.ACTIVE) {
        it.copy(status = SubscriptionStatus.ACTIVE.name, activatedOn = time.today().toString())
    }

    override suspend fun rejectProposal(subscriptionId: Long) = updateStatus(subscriptionId, SubscriptionStatus.PROPOSED, SubscriptionStatus.REJECTED) {
        it.copy(status = SubscriptionStatus.REJECTED.name)
    }

    override suspend fun addManual(input: ManualSubscriptionInput): Outcome<Unit, FieldErrors<ManualSubscriptionField>> {
        val errors = ManualSubscriptionValidator.validate(input, time.today())
        if (!errors.isValid) return Outcome.Err(errors)
        val merchantId = MerchantResolver(db().merchantCatalogDao()).resolve(input.merchant)
            ?: return Outcome.Err(FieldErrors(mapOf(ManualSubscriptionField.MERCHANT to app.assistant.domain.error.ValidationError.EMPTY)))
        db().subscriptionDao().insert(
            SubscriptionEntity(
                merchantId = merchantId,
                amountPaise = input.amount.value,
                interval = input.interval.name,
                anchorDay = input.nextDue.dayOfMonth,
                nextDue = input.nextDue.toString(),
                activatedOn = time.today().toString(),
                lastUsageCheck = null,
                cancelledOn = null,
                consecutiveNo = 0,
                status = SubscriptionStatus.ACTIVE.name,
            ),
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun answerUsageCheck(subscriptionId: Long, usedIt: Boolean) {
        val dao = db().subscriptionDao()
        db().withTransaction {
            val sub = dao.byId(subscriptionId) ?: return@withTransaction
            val outcome = UsageCheckRules.afterAnswer(sub.consecutiveNo, usedIt)
            dao.update(sub.copy(lastUsageCheck = time.today().toString(), consecutiveNo = outcome.newConsecutiveNo))
            // Close any open usage-check rows for this subscription (answered now).
            dao.openUsageChecks(subscriptionId).forEach { dao.updateUsageCheck(it.copy(answeredAt = time.now().toEpochMilli(), usedIt = usedIt)) }
            if (outcome.raiseReviewSuggestion) {
                raiseSuggestion(
                    kind = SuggestionKind.REVIEW_NON_USE,
                    text = "You marked ${merchantName(sub.merchantId)} unused twice. Cancel to save ${SubscriptionDetector.yearlyCost(Paise(sub.amountPaise), interval(sub.interval)).value / PAISE_PER_RUPEE} a year?",
                    reason = "Two consecutive \"No\" usage answers",
                    change = ProposedChange.SubscriptionCancel(subscriptionId),
                )
            }
        }
    }

    override suspend fun markCancelled(subscriptionId: Long) {
        val dao = db().subscriptionDao()
        db().withTransaction {
            val sub = dao.byId(subscriptionId) ?: return@withTransaction
            dao.update(sub.copy(status = SubscriptionStatus.ENDED.name, cancelledOn = time.today().toString()))
            dao.deleteOpenUsageChecks(subscriptionId) // no further usage checks once ended (Requirement 39.6)
        }
    }

    override suspend fun acceptSuggestion(suggestionId: Long) {
        val dao = db().subscriptionDao()
        db().withTransaction {
            val row = dao.suggestionById(suggestionId) ?: return@withTransaction
            row.changeJson?.let { ProposedChangeCodec.decode(it) }?.let { applyChangeInternal(it) }
            dao.updateSuggestion(row.copy(response = app.assistant.domain.model.SuggestionResponse.ACCEPTED.name, respondedAt = time.now().toEpochMilli()))
        }
    }

    override suspend fun dismissSuggestion(suggestionId: Long) {
        val dao = db().subscriptionDao()
        dao.suggestionById(suggestionId)?.let {
            dao.updateSuggestion(it.copy(response = app.assistant.domain.model.SuggestionResponse.DISMISSED.name, respondedAt = time.now().toEpochMilli()))
        }
    }

    override suspend fun applyChange(change: ProposedChange) {
        db().withTransaction { applyChangeInternal(change) }
    }

    override suspend fun runDailyMaintenance() {
        val dao = db().subscriptionDao()
        val today = time.today()
        db().withTransaction {
            val active = dao.active().map { it.toSnapshot() }
            // Missed charges: advance the due date and raise a "not seen" Suggestion (Requirement 38.7).
            for (sub in SubscriptionDetector.missedCharges(active, today)) {
                val id = sub.id ?: continue
                val advanced = DueDateRule.next(sub.nextDue, sub.interval, sub.anchorDay)
                dao.byId(id)?.let { dao.update(it.copy(nextDue = advanced.toString())) }
                raiseSuggestion(
                    kind = SuggestionKind.CHARGE_NOT_SEEN,
                    text = "Expected charge from ${merchantName(sub.merchantId.value)} was not seen.",
                    reason = "No ${intervalWord(sub.interval.name)} charge near ${sub.nextDue}",
                    change = ProposedChange.SubscriptionCancel(id),
                )
            }
            // Usage checks: mark one due for any active Subscription that is due with none already open (Requirement 39.1).
            for (sub in dao.active()) {
                val snap = sub.toSnapshot()
                val due = UsageCheckRules.isDue(snap.activatedOn, snap.lastUsageCheck, today)
                if (due && dao.openUsageChecks(sub.id).isEmpty()) {
                    dao.insertUsageCheck(UsageCheckEntity(subscriptionId = sub.id, dueOn = today.toString(), answeredAt = null, usedIt = null))
                }
            }
        }
    }

    // --- internals ---

    private suspend fun applyChangeInternal(change: ProposedChange) {
        val dao = db().subscriptionDao()
        when (change) {
            is ProposedChange.SubscriptionPriceChange -> dao.byId(change.subscriptionId)?.let {
                dao.update(it.copy(amountPaise = change.newAmount.value, interval = change.newInterval.name))
            }
            is ProposedChange.SubscriptionCancel -> dao.byId(change.subscriptionId)?.let {
                dao.update(it.copy(status = SubscriptionStatus.ENDED.name, cancelledOn = time.today().toString()))
            }
            is ProposedChange.SubscriptionReactivate -> dao.byId(change.subscriptionId)?.let {
                dao.update(it.copy(status = SubscriptionStatus.ACTIVE.name, cancelledOn = null, nextDue = change.nextDue.toString()))
            }
            is ProposedChange.SubscriptionAdvanceDue -> dao.byId(change.subscriptionId)?.let {
                dao.update(it.copy(nextDue = change.newNextDue.toString()))
            }
        }
    }

    private suspend fun raiseSuggestion(kind: SuggestionKind, text: String, reason: String, change: ProposedChange?) {
        db().subscriptionDao().insertSuggestion(
            SuggestionEntity(
                kind = kind.name,
                promptType = null,
                text = text,
                reason = reason,
                shownAt = time.now().toEpochMilli(),
                respondedAt = null,
                response = null,
                changeJson = change?.let(ProposedChangeCodec::encode),
            ),
        )
    }

    private suspend fun updateStatus(id: Long, from: SubscriptionStatus, to: SubscriptionStatus, change: (SubscriptionEntity) -> SubscriptionEntity) {
        val dao = db().subscriptionDao()
        db().withTransaction {
            val sub = dao.byId(id) ?: return@withTransaction
            if (sub.status == from.name) dao.update(change(sub))
            if (to == SubscriptionStatus.ENDED) Unit // reject keeps the row so it is never re-proposed (Requirement 37.3)
        }
    }

    private suspend fun loadDebitCharges(): List<Charge> =
        db().transactionDao().debitChargesForSubscriptions().mapNotNull { row ->
            val merchantId = row.merchantId ?: return@mapNotNull null
            Charge(TransactionId(row.id), MerchantId(merchantId), Paise(row.amountPaise), Instant.ofEpochMilli(row.at).atZone(time.zone()).toLocalDate())
        }

    private suspend fun view(e: SubscriptionEntity): SubscriptionView =
        SubscriptionView(e.toSnapshot(), merchantName(e.merchantId), SubscriptionDetector.yearlyCost(Paise(e.amountPaise), interval(e.interval)))

    private suspend fun merchantName(merchantId: Long): String = db().merchantCatalogDao().name(merchantId) ?: "Unknown"

    private fun interval(name: String): SubscriptionInterval = SubscriptionInterval.valueOf(name)

    private fun intervalWord(name: String): String = SubscriptionInterval.valueOf(name).name.lowercase()

    private fun SubscriptionEntity.toSnapshot() = SubscriptionSnapshot(
        id = id,
        merchantId = MerchantId(merchantId),
        amount = Paise(amountPaise),
        interval = interval(interval),
        anchorDay = anchorDay,
        nextDue = LocalDate.parse(nextDue),
        activatedOn = LocalDate.parse(activatedOn),
        lastUsageCheck = lastUsageCheck?.let(LocalDate::parse),
        cancelledOn = cancelledOn?.let(LocalDate::parse),
        status = SubscriptionStatus.valueOf(status),
    )

    private fun SuggestionEntity.toSnapshot() = SuggestionSnapshot(
        id = id,
        kind = SuggestionKind.valueOf(kind),
        promptType = promptType?.let(app.assistant.domain.model.PromptType::valueOf),
        text = text,
        reason = reason,
        shownAt = Instant.ofEpochMilli(shownAt),
        response = response?.let(app.assistant.domain.model.SuggestionResponse::valueOf),
        change = changeJson?.let(ProposedChangeCodec::decode),
    )

    private companion object {
        const val PAISE_PER_RUPEE = 100
    }
}

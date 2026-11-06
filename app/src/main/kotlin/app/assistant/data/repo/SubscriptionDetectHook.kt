package app.assistant.data.repo

import app.assistant.domain.port.PersistedMessage
import app.assistant.domain.port.PostCommitHook
import app.assistant.domain.port.SubscriptionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a Money_Message is stored, re-run subscription detection so a new debit that completes a Recurring_Pattern
 * surfaces a proposal (Requirement 37.1). Detection is idempotent (37.5), so running it per stored message is safe. A
 * failure here never changes the ingest outcome (the pipeline swallows hook errors).
 */
@Singleton
class SubscriptionDetectHook @Inject constructor(private val subscriptions: SubscriptionRepository) : PostCommitHook {
    override suspend fun onPersisted(event: PersistedMessage) {
        subscriptions.redetect()
    }
}

package app.assistant.domain.port

import app.assistant.domain.model.TransactionId

/** What a post-commit hook is told about a message that has just been stored. */
data class PersistedMessage(val transaction: TransactionId, val created: Boolean, val origin: String, val formatId: String)

/** Work that follows a stored message: the Bill paid matcher, Subscription_Detector and Quality_Monitor plug in here. */
fun interface PostCommitHook {
    suspend fun onPersisted(event: PersistedMessage)
}

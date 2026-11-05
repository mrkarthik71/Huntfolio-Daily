package app.assistant.data.ai

import app.assistant.domain.port.CloudCredential
import app.assistant.domain.port.CloudKeySource
import app.assistant.data.db.DatabaseHolder
import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal flavour: the Cloud AI key is entered on the device and stored encrypted in the `secret`
 * table (Requirement 45.1). This reads it, decrypts it with the Key_Manager for the duration of one
 * call, and never logs or copies it. A missing key means Cloud is not configured, so the gateway
 * refuses Cloud until the user sets one.
 */
@Singleton
class PersonalCloudKeySource @Inject constructor(
    private val holder: DatabaseHolder,
    private val keyManager: KeyManager,
) : CloudKeySource {
    override suspend fun credential(): CloudCredential? {
        val secret = holder.database().secretDao().get(SECRET_NAME) ?: return null
        return when (val plain = keyManager.decryptSecret(secret.blob)) {
            is Outcome.Ok -> {
                val key = String(plain.value, Charsets.UTF_8)
                plain.value.fill(0)
                if (key.isBlank()) null else CloudCredential.Direct(key)
            }
            is Outcome.Err -> null
        }
    }

    companion object {
        const val SECRET_NAME = "cloud_ai_key"
    }
}

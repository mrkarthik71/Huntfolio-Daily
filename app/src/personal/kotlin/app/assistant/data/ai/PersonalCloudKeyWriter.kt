package app.assistant.data.ai

import app.assistant.domain.port.CloudKeyWriter
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.SecretEntity
import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyManager
import javax.inject.Inject
import javax.inject.Singleton

/** Personal flavour: writes the Cloud AI key encrypted into the `secret` table (Requirement 45.1, 45.5). */
@Singleton
class PersonalCloudKeyWriter @Inject constructor(
    private val holder: DatabaseHolder,
    private val keyManager: KeyManager,
) : CloudKeyWriter {
    override val supported: Boolean = true

    override suspend fun setKey(key: String): Boolean {
        val dao = holder.database().secretDao()
        if (key.isBlank()) {
            dao.delete(PersonalCloudKeySource.SECRET_NAME)
            return true
        }
        return when (val blob = keyManager.encryptSecret(key.toByteArray(Charsets.UTF_8))) {
            is Outcome.Ok -> {
                dao.put(SecretEntity(PersonalCloudKeySource.SECRET_NAME, blob.value))
                true
            }
            is Outcome.Err -> false
        }
    }

    override suspend fun hasKey(): Boolean = holder.database().secretDao().get(PersonalCloudKeySource.SECRET_NAME) != null
}

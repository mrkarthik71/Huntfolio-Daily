package app.assistant.data.mail

import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.SecretEntity
import app.assistant.domain.error.Outcome
import app.assistant.security.keys.KeyManager
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and reads the IMAP credentials (Requirement 51.2, 51.5): the config (host/port/user, stored
 * as an encrypted JSON blob) and the password, both in the `secret` table through the Key_Manager.
 * Credentials are written only after a successful Connection_Test (the caller enforces that) and the
 * password is returned as a fresh CharArray the caller clears.
 */
@Singleton
class SecretMailCredentialStore @Inject constructor(
    private val holder: DatabaseHolder,
    private val keyManager: KeyManager,
) : MailCredentialStore {
    private val gson = Gson()

    override suspend fun config(): ImapConfig? {
        val secret = holder.database().secretDao().get(CONFIG_SECRET) ?: return null
        return when (val plain = keyManager.decryptSecret(secret.blob)) {
            is Outcome.Ok -> runCatching { gson.fromJson(String(plain.value, Charsets.UTF_8), ImapConfig::class.java) }.getOrNull().also { plain.value.fill(0) }
            is Outcome.Err -> null
        }
    }

    override suspend fun password(): CharArray? {
        val secret = holder.database().secretDao().get(PASSWORD_SECRET) ?: return null
        return when (val plain = keyManager.decryptSecret(secret.blob)) {
            is Outcome.Ok -> String(plain.value, Charsets.UTF_8).toCharArray().also { plain.value.fill(0) }
            is Outcome.Err -> null
        }
    }

    /** Stores both after a successful Connection_Test. A blank password clears the stored mailbox. */
    suspend fun save(config: ImapConfig, password: String): Boolean {
        val dao = holder.database().secretDao()
        if (password.isBlank()) {
            dao.delete(CONFIG_SECRET)
            dao.delete(PASSWORD_SECRET)
            return true
        }
        val configBlob = keyManager.encryptSecret(gson.toJson(config).toByteArray(Charsets.UTF_8))
        val pwBlob = keyManager.encryptSecret(password.toByteArray(Charsets.UTF_8))
        if (configBlob !is Outcome.Ok || pwBlob !is Outcome.Ok) return false
        dao.put(SecretEntity(CONFIG_SECRET, configBlob.value))
        dao.put(SecretEntity(PASSWORD_SECRET, pwBlob.value))
        return true
    }

    suspend fun hasCredentials(): Boolean = holder.database().secretDao().get(PASSWORD_SECRET) != null

    companion object {
        const val CONFIG_SECRET = "mail_imap_config"
        const val PASSWORD_SECRET = "mail_imap_password"
    }
}

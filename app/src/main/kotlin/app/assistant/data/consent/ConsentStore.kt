package app.assistant.data.consent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the shared-build consent record (Requirement 66.6) in a small SharedPreferences file, NOT
 * the Encrypted_Store, because consent must be recorded before the database or its key may be
 * created (Requirement 66.1). It holds no personal data — only the accepted Consent_Text_Version and
 * the acceptance time. SharedPreferences avoids a new dependency (the project has only proto
 * DataStore) and matches how permission state is stored.
 */
@Singleton
class ConsentStore @Inject constructor(@param:ApplicationContext private val context: Context) : app.assistant.domain.port.ConsentRecord {
    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun acceptedVersion(): Int? = prefs.getInt(VERSION_KEY, -1).takeIf { it >= 0 }

    fun acceptedAtEpochMs(): Long? = prefs.getLong(AT_KEY, -1).takeIf { it >= 0 }

    override fun accept(version: Int, atEpochMs: Long) {
        prefs.edit().putInt(VERSION_KEY, version).putLong(AT_KEY, atEpochMs).commit()
    }

    private companion object {
        const val FILE = "consent_state"
        const val VERSION_KEY = "consent_version"
        const val AT_KEY = "consent_at"
    }
}

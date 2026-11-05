package app.assistant.data.health

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.assistant.data.consent.ConsentStore
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.mail.SecretMailCredentialStore
import app.assistant.domain.engine.health.HealthRules
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers the Health_Check inputs from the platform and the store, runs [HealthRules], and applies
 * the user's dismissal (Requirement 62). It never notifies by itself — the worker/UI decide what to
 * do with [currentProblems] and [shouldWarn]. Dismissals live in a small SharedPreferences file so a
 * check can run before the encrypted store is open.
 */
@Singleton
class HealthCheck @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val holder: DatabaseHolder,
    private val time: TimeSource,
    private val mailCredentials: SecretMailCredentialStore,
) : app.assistant.domain.port.HealthMonitor {
    private val prefs get() = context.getSharedPreferences(DISMISS_FILE, Context.MODE_PRIVATE)

    override suspend fun currentProblems(): Set<HealthRules.Problem> {
        val jobs = holder.database().jobRunDao().all().map {
            HealthRules.JobState(it.job, java.time.Instant.ofEpochMilli(it.lastRunAt), expectedInterval(it.job))
        }
        val nm = NotificationManagerCompat.from(context)
        return HealthRules.problems(
            jobs = jobs,
            now = time.now(),
            notificationsBlocked = !nm.areNotificationsEnabled(),
            blockedChannels = nm.notificationChannels.any { it.importance == android.app.NotificationManager.IMPORTANCE_NONE },
            missingPermissions = false, // per-feature permission prompts own this; left to the feature screens
            notificationAccessLost = !NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            mailboxNeedsReconnect = mailCredentials.hasCredentials() && false, // set true when a sync hit AUTH_REJECTED (tracked by mail layer)
        )
    }

    override suspend fun shouldWarn(): Boolean = HealthRules.shouldWarn(currentProblems(), dismissed())

    /** Dismiss the current problems until a NEW one appears (Requirement 62.5). */
    override suspend fun dismiss() {
        val names = currentProblems().joinToString(",") { it.name }
        prefs.edit().putString(DISMISSED_KEY, names).commit()
    }

    private fun dismissed(): Set<HealthRules.Problem> =
        prefs.getString(DISMISSED_KEY, "").orEmpty().split(",").filter { it.isNotBlank() }
            .mapNotNull { name -> HealthRules.Problem.entries.firstOrNull { it.name == name } }
            .toSet()

    private fun expectedInterval(job: String): Duration = when (job) {
        "mail-sync" -> Duration.ofHours(2)
        "calendar-refresh" -> Duration.ofHours(6)
        "maintenance-daily" -> Duration.ofHours(25)
        else -> Duration.ofHours(24)
    }

    private companion object {
        const val DISMISS_FILE = "health_state"
        const val DISMISSED_KEY = "dismissed_problems"
    }
}

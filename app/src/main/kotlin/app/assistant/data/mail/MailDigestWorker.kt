package app.assistant.data.mail

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.assistant.data.db.DatabaseHolder
import app.assistant.domain.engine.mail.DigestBuilder
import app.assistant.domain.model.MailCategory
import app.assistant.domain.model.NotificationType
import app.assistant.R
import app.assistant.domain.port.ImmediateAlert
import app.assistant.domain.port.NotificationScheduling
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * The Mail_Digest worker (Requirement 56). Fired at the digest time, it builds the digest from the
 * kept Inbox_Items and posts one notification. It is SKIPPED when there is nothing to show AND the
 * mailbox is connected (Requirement 56.3); when reconnection is needed it still posts, with the
 * sync-stopped line so the user knows the list may be stale.
 */
@HiltWorker
class MailDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val holder: DatabaseHolder,
    private val credentials: SecretMailCredentialStore,
    private val notifications: NotificationScheduling,
    private val time: app.assistant.domain.port.TimeSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { post() }
        return Result.success()
    }

    private suspend fun post() {
        val items = holder.database().mailDao().items().first().map {
            DigestBuilder.DigestItem(
                itemId = it.id,
                sender = it.sender,
                subject = it.subject,
                category = MailCategory.entries.firstOrNull { c -> c.name == it.category } ?: MailCategory.OTHER,
                receivedAt = Instant.ofEpochMilli(it.receivedAt),
            )
        }
        val connected = credentials.hasCredentials()
        // Skip only when empty AND connected; a disconnected mailbox still gets a reminder to reconnect.
        if (DigestBuilder.isEmpty(items) && connected) return

        val sections = DigestBuilder.build(items)
        val ctx = applicationContext
        val body = if (sections.isEmpty() && !connected) {
            ctx.getString(R.string.mail_digest_stopped)
        } else {
            sections.joinToString("\n") { s -> ctx.getString(R.string.mail_digest_line, s.category.name.lowercase().replaceFirstChar(Char::uppercase), s.items.size) } +
                if (!connected) "\n" + ctx.getString(R.string.mail_digest_stopped_short) else ""
        }
        notifications.offerNow(
            ImmediateAlert(
                type = NotificationType.MAIL_DIGEST,
                refKey = "digest:${time.today()}",
                title = ctx.getString(R.string.mail_digest_title),
                text = body,
                route = "settings/mail",
            ),
        )
    }

    companion object {
        const val UNIQUE_NAME = "mail-digest"

        /** Schedules the digest to fire daily at [digestMinuteOfDay] (minutes after local midnight). */
        fun schedule(context: Context, digestMinuteOfDay: Int, now: java.time.LocalTime) {
            val target = java.time.LocalTime.of(digestMinuteOfDay / MIN_PER_HOUR, digestMinuteOfDay % MIN_PER_HOUR)
            var delayMin = java.time.Duration.between(now, target).toMinutes()
            if (delayMin < 0) delayMin += MIN_PER_DAY
            val request = androidx.work.PeriodicWorkRequestBuilder<MailDigestWorker>(MIN_PER_DAY, java.util.concurrent.TimeUnit.MINUTES)
                .setInitialDelay(delayMin, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        private const val MIN_PER_HOUR = 60
        private const val MIN_PER_DAY = 1_440L
    }
}

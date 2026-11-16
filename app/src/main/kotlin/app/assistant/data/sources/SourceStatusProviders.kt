package app.assistant.data.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.DataSource
import app.assistant.domain.port.MailboxService
import app.assistant.domain.port.ReaderStateStore
import app.assistant.domain.port.SettingsStore
import app.assistant.domain.port.SourceStatusProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Whether each Data_Source is connected, for Settings and the Today "Connect your accounts" panel.
 * Each is checked every few seconds while someone watches it, so returning from Android's settings
 * shows the new state; it reads only switches and permission grants, never any message.
 */
private fun polled(check: suspend () -> Boolean): Flow<Boolean> = flow {
    while (true) {
        emit(runCatching { check() }.getOrDefault(false))
        delay(POLL_MS)
    }
}.distinctUntilChanged()

private const val POLL_MS = 3_000L

private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

class SmsSourceStatus @Inject constructor(@param:ApplicationContext private val context: Context, private val reader: ReaderStateStore) : SourceStatusProvider {
    override val source = DataSource.SMS
    override val connected: Flow<Boolean> = polled { reader.flags.first().smsEnabled && granted(context, Manifest.permission.READ_SMS) }
}

class AppNotificationSourceStatus @Inject constructor(@param:ApplicationContext private val context: Context, private val reader: ReaderStateStore) :
    SourceStatusProvider {
    override val source = DataSource.APP_NOTIFICATIONS
    override val connected: Flow<Boolean> = polled {
        reader.flags.first().notificationsEnabled && NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}

class CalendarSourceStatus @Inject constructor(@param:ApplicationContext private val context: Context) : SourceStatusProvider {
    override val source = DataSource.CALENDAR
    override val connected: Flow<Boolean> = polled { granted(context, Manifest.permission.READ_CALENDAR) }
}

class MailSourceStatus @Inject constructor(private val mailbox: MailboxService) : SourceStatusProvider {
    override val source = DataSource.MAILBOX
    override val connected: Flow<Boolean> = polled { mailbox.isConnected() }
}

class AiSourceStatus @Inject constructor(private val settings: SettingsStore) : SourceStatusProvider {
    override val source = DataSource.AI_PROVIDER
    override val connected: Flow<Boolean> = polled { settings.current().aiMode != AiMode.NONE }
}

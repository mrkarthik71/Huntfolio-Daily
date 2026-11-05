package app.assistant.app.di

import android.content.Context
import app.assistant.data.mail.ImapMailConnector
import app.assistant.data.mail.MailAccountId
import app.assistant.data.mail.MailCredentialStore
import app.assistant.data.mail.MailNotifier
import app.assistant.data.mail.MailOtpPatterns
import app.assistant.data.mail.SecretMailCredentialStore
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.MailConnector
import app.assistant.domain.port.SettingsStore
import app.assistant.R
import app.assistant.domain.port.ImmediateAlert
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.model.NotificationType
import com.google.gson.JsonParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

/**
 * Wires the Phase 6 mail layer. The connector is read-only IMAP; the OTP patterns come from assets;
 * the notifier raises the important-mail notification through the existing Notifier; the account id
 * is the IMAP username, so Source_Refs are stable across syncs.
 */
@Module
@InstallIn(SingletonComponent::class)
object MailModule {

    @Provides
    @Singleton
    fun credentialStore(impl: SecretMailCredentialStore): MailCredentialStore = impl

    @Provides
    @Singleton
    fun mailConnector(credentials: MailCredentialStore, dispatchers: DispatcherProvider, settings: SettingsStore): MailConnector =
        ImapMailConnector(credentials, dispatchers) { runBlocking { settings.current().mailSnippetLength } }

    @Provides
    @Singleton
    fun otpPatterns(@ApplicationContext context: Context): MailOtpPatterns {
        val patterns = runCatching {
            val json = context.assets.open("otp_mail_patterns.json").bufferedReader().use { it.readText() }
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("patterns").map { Regex(it.asString, RegexOption.IGNORE_CASE) }
        }.getOrDefault(emptyList())
        return MailOtpPatterns { patterns }
    }

    @Provides
    @Singleton
    fun mailNotifier(@ApplicationContext context: Context, notifications: NotificationScheduling): MailNotifier = MailNotifier { itemId, subject, _ ->
        // Through the Notification_Governor like every other notification (quiet hours, caps, "Why" log).
        notifications.offerNow(
            ImmediateAlert(
                type = NotificationType.IMPORTANT_MAIL,
                refKey = "mail:$itemId",
                title = context.getString(R.string.mail_important_title),
                text = subject,
                route = "settings/mail",
            ),
        )
    }

    @Provides
    @Singleton
    fun mailAccountId(credentials: MailCredentialStore): MailAccountId = MailAccountId {
        runBlocking { credentials.config()?.username }.orEmpty()
    }
}

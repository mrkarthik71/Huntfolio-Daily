package app.assistant.ingest.notifications

import app.assistant.ingest.pipeline.RawMessage
import java.time.Instant

/**
 * What the Notification_Reader takes from a posted notification: the package, its key, and only the title and text extras
 * (Requirements 2.3, 27.1). It carries no actions, intents, icons or other extras, so nothing here can be used to act on
 * the other app's notification. [toString] hides the content (Requirement 3.5).
 */
class PostedNotification(val packageName: String, val key: String, val title: CharSequence?, val text: CharSequence?, val postedAt: Instant) {
    override fun toString(): String = "PostedNotification"
}

object NotificationMapper {
    /**
     * The message the pipeline reads, or null for a notification with an empty title and empty text, which is discarded
     * without reaching the parser or the Review_Queue (Requirement 27.6).
     */
    fun toMessage(posted: PostedNotification): RawMessage.Notification? {
        val message = RawMessage.Notification(
            posted.packageName,
            posted.key,
            posted.title?.toString().orEmpty(),
            posted.text?.toString().orEmpty(),
            posted.postedAt,
        )
        return message.takeIf { it.hasContent }
    }
}

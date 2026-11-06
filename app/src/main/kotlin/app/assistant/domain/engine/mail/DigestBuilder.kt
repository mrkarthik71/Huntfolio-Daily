package app.assistant.domain.engine.mail

import app.assistant.domain.model.MailCategory
import java.time.Instant

/**
 * Builds the Mail_Digest (Requirement 56): the kept Inbox_Items grouped and ordered by category,
 * newest first within each. Pure — the caller supplies the items and decides whether to show the
 * digest (skipped when empty and connected, Requirement 56.3).
 */
object DigestBuilder {
    /** One line in the digest: what the notification/screen shows, carrying no snippet or links. */
    data class DigestItem(val itemId: Long, val sender: String, val subject: String, val category: MailCategory, val receivedAt: Instant)

    data class DigestSection(val category: MailCategory, val items: List<DigestItem>)

    /** Category order from the design; items newest first within each. Empty categories are dropped. */
    fun build(items: List<DigestItem>): List<DigestSection> = CATEGORY_ORDER.mapNotNull { category ->
        val inCategory = items.filter { it.category == category }.sortedByDescending { it.receivedAt }
        if (inCategory.isEmpty()) null else DigestSection(category, inCategory)
    }

    /** True when there is nothing to show; the digest is then skipped while connected (Requirement 56.3). */
    fun isEmpty(items: List<DigestItem>): Boolean = items.isEmpty()

    private val CATEGORY_ORDER = listOf(
        MailCategory.INTERVIEW,
        MailCategory.DEADLINE,
        MailCategory.BILL,
        MailCategory.DELIVERY,
        MailCategory.OTHER,
    )
}

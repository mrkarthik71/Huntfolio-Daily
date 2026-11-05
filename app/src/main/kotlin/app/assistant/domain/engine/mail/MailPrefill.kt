package app.assistant.domain.engine.mail

import app.assistant.domain.model.MailCategory
import java.time.LocalDate

/**
 * Pure mapping from an Inbox_Item to the initial values of the Task and Bill forms (Requirement 57).
 * It only ever PREFILLS: creating nothing, so a cancelled form leaves no Task or Bill. Only valid
 * dates and positive amounts are carried over; anything else is left blank for the user to fill.
 */
object MailPrefill {
    /** The facts a prefill needs from an Inbox_Item (no snippet is stored, so the subject carries the title). */
    data class Source(val subject: String, val category: MailCategory, val suggestedDate: LocalDate?, val suggestedAmountPaise: Long?)

    /** Initial values for the Task form. The title is the subject; a valid date becomes the planned date. */
    data class TaskPrefill(val title: String, val plannedDate: LocalDate?)

    /** Initial values for the Bill form. Only populated for BILL-category mail with a positive amount. */
    data class BillPrefill(val name: String, val dueDate: LocalDate?, val amountPaise: Long?)

    fun toTask(source: Source): TaskPrefill = TaskPrefill(title = source.subject.trim(), plannedDate = source.suggestedDate)

    /** Null when the mail is not a bill or has no positive amount, so no bill form is offered. */
    fun toBill(source: Source): BillPrefill? {
        if (source.category != MailCategory.BILL) return null
        val amount = source.suggestedAmountPaise?.takeIf { it > 0 }
        return BillPrefill(name = source.subject.trim(), dueDate = source.suggestedDate, amountPaise = amount)
    }
}

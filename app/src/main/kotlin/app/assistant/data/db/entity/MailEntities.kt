package app.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One shortlisted-or-important email kept for the Inbox and digest (Requirement 54.5). It holds NO
 * snippet and NO links at rest (Requirement 54.7) — only the header facts, the scores and the
 * category. [sourceRef] is the stable Source_Ref; a unique index makes Inbox_Items unique per email.
 */
@Entity(tableName = "inbox_item", indices = [Index(value = ["source_ref"], unique = true), Index("received_at")])
data class InboxItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "source_ref") val sourceRef: String,
    val sender: String,
    val subject: String,
    val category: String,
    @androidx.room.ColumnInfo(name = "rule_score") val ruleScore: Int,
    @androidx.room.ColumnInfo(name = "final_score") val finalScore: Int,
    @androidx.room.ColumnInfo(name = "received_at") val receivedAt: Long,
    @androidx.room.ColumnInfo(name = "notified") val notified: Boolean,
    /** True once the user tapped "Not important" on this item's sender, counted once as a Wrong_Mail_Alert. */
    @androidx.room.ColumnInfo(name = "wrong_alert") val wrongAlert: Boolean,
)

/** A user Shortlist_Rule (Requirement 53.2). */
@Entity(tableName = "shortlist_rule")
data class ShortlistRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "sender_contains") val senderContains: String,
    @androidx.room.ColumnInfo(name = "subject_contains") val subjectContains: String,
    val score: Int,
)

/** A per-sender penalty built up from "Not important" taps (Requirement 55.5). */
@Entity(tableName = "sender_penalty")
data class SenderPenaltyEntity(
    @PrimaryKey val sender: String,
    val penalty: Int,
)

/** The resume cursor per folder (Requirement 52.4). */
@Entity(tableName = "mail_cursor")
data class MailCursorEntity(
    @PrimaryKey val folder: String,
    @androidx.room.ColumnInfo(name = "uid_validity") val uidValidity: Long,
    @androidx.room.ColumnInfo(name = "last_uid") val lastUid: Long,
)

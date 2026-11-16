package app.assistant.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val pinned: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "journal_entry")
data class JournalEntryEntity(@PrimaryKey val date: String, val text: String)

@Entity(tableName = "list_item", indices = [Index("list_name")])
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "list_name") val listName: String,
    val title: String,
    val done: Boolean,
    @ColumnInfo(name = "repeat_days") val repeatDays: Int?,
    @ColumnInfo(name = "done_on") val doneOn: String?,
)

@Entity(tableName = "keep_in_touch")
data class KeepInTouchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "every_days") val everyDays: Int,
    @ColumnInfo(name = "last_talked") val lastTalked: String?,
)

@Entity(tableName = "trip", indices = [Index(value = ["source_ref"], unique = true)])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "ref_code") val refCode: String?,
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "from_place") val fromPlace: String?,
    @ColumnInfo(name = "to_place") val toPlace: String?,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
)

@Entity(tableName = "order_item", indices = [Index(value = ["order_ref"], unique = true)])
data class OrderItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    @ColumnInfo(name = "order_ref") val orderRef: String,
    val status: String,
    @ColumnInfo(name = "expected_on") val expectedOn: String?,
    @ColumnInfo(name = "return_by") val returnBy: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** How an open task repeats; when it is done the next one is created and this row moves to it. */
@Entity(tableName = "task_repeat")
data class TaskRepeatEntity(@PrimaryKey @ColumnInfo(name = "task_id") val taskId: Long, val rule: String, val every: Int)

@Entity(tableName = "iou", indices = [Index("person")])
data class IouEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val person: String,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    @ColumnInfo(name = "they_owe") val theyOwe: Boolean,
    val note: String,
    @ColumnInfo(name = "created_on") val createdOn: String,
    @ColumnInfo(name = "settled_on") val settledOn: String?,
)

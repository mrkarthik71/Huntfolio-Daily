package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.assistant.data.db.entity.JournalEntryEntity
import app.assistant.data.db.entity.KeepInTouchEntity
import app.assistant.data.db.entity.ListItemEntity
import app.assistant.data.db.entity.NoteEntity
import app.assistant.data.db.entity.OrderItemEntity
import app.assistant.data.db.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // one DAO for the six small personal tables
interface PersonalDao {
    @Query("SELECT * FROM note ORDER BY pinned DESC, updated_at DESC")
    fun notes(): Flow<List<NoteEntity>>

    @Upsert
    suspend fun upsertNote(n: NoteEntity): Long

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM journal_entry ORDER BY date DESC")
    fun journal(): Flow<List<JournalEntryEntity>>

    @Upsert
    suspend fun upsertJournal(e: JournalEntryEntity)

    @Query("DELETE FROM journal_entry WHERE date = :date")
    suspend fun deleteJournal(date: String)

    @Query("SELECT * FROM list_item ORDER BY list_name, done, id")
    fun listItems(): Flow<List<ListItemEntity>>

    @Upsert
    suspend fun upsertListItem(i: ListItemEntity): Long

    @Query("UPDATE list_item SET done = :done, done_on = :on WHERE id = :id")
    suspend fun setListItemDone(id: Long, done: Boolean, on: String?)

    @Query("DELETE FROM list_item WHERE id = :id")
    suspend fun deleteListItem(id: Long)

    @Query("SELECT * FROM keep_in_touch ORDER BY name")
    fun people(): Flow<List<KeepInTouchEntity>>

    @Query("SELECT * FROM keep_in_touch WHERE id = :id")
    suspend fun person(id: Long): KeepInTouchEntity?

    @Upsert
    suspend fun upsertPerson(p: KeepInTouchEntity): Long

    @Query("UPDATE keep_in_touch SET last_talked = :on WHERE id = :id")
    suspend fun talkedTo(id: Long, on: String)

    @Query("DELETE FROM keep_in_touch WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Query("SELECT * FROM trip ORDER BY starts_at")
    fun trips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip WHERE source_ref = :ref")
    suspend fun tripByRef(ref: String): TripEntity?

    @Upsert
    suspend fun upsertTrip(t: TripEntity): Long

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("SELECT * FROM order_item ORDER BY updated_at DESC")
    fun orders(): Flow<List<OrderItemEntity>>

    @Query("SELECT * FROM order_item WHERE order_ref = :ref")
    suspend fun orderByRef(ref: String): OrderItemEntity?

    @Upsert
    suspend fun upsertOrder(o: OrderItemEntity): Long

    @Query("DELETE FROM order_item WHERE id = :id")
    suspend fun deleteOrder(id: Long)

    @Query("SELECT * FROM task_repeat")
    fun repeats(): Flow<List<app.assistant.data.db.entity.TaskRepeatEntity>>

    @Query("SELECT * FROM task_repeat WHERE task_id = :taskId")
    suspend fun repeatOf(taskId: Long): app.assistant.data.db.entity.TaskRepeatEntity?

    @Upsert
    suspend fun upsertRepeat(r: app.assistant.data.db.entity.TaskRepeatEntity)

    @Query("DELETE FROM task_repeat WHERE task_id = :taskId")
    suspend fun deleteRepeat(taskId: Long)

    @Query("SELECT * FROM iou ORDER BY created_on DESC, id DESC")
    fun ious(): Flow<List<app.assistant.data.db.entity.IouEntity>>

    @Upsert
    suspend fun upsertIou(i: app.assistant.data.db.entity.IouEntity): Long

    @Query("UPDATE iou SET settled_on = :on WHERE id = :id AND settled_on IS NULL")
    suspend fun settleIou(id: Long, on: String)

    @Query("UPDATE iou SET settled_on = :on WHERE LOWER(TRIM(person)) = LOWER(TRIM(:person)) AND settled_on IS NULL")
    suspend fun settlePerson(person: String, on: String): Int

    @Query("DELETE FROM iou WHERE id = :id")
    suspend fun deleteIou(id: Long)
}

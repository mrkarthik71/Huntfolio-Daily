package app.assistant.domain.port

import app.assistant.domain.model.JournalEntry
import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.ListItem
import app.assistant.domain.model.Note
import app.assistant.domain.model.Order
import app.assistant.domain.model.Trip
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** Notes, journal, lists, keep-in-touch, trips and orders, all kept in the Encrypted_Store. */
@Suppress("TooManyFunctions") // six small collections behind one store
interface PersonalStore {
    fun notes(): Flow<List<Note>>

    suspend fun saveNote(id: Long, title: String, body: String, pinned: Boolean, at: Instant): Long

    suspend fun deleteNote(id: Long)

    fun journal(): Flow<List<JournalEntry>>

    suspend fun saveJournal(date: LocalDate, text: String)

    fun listItems(): Flow<List<ListItem>>

    suspend fun addListItem(list: String, title: String, repeatDays: Int?): Long

    suspend fun setListItemDone(id: Long, done: Boolean, on: LocalDate)

    suspend fun deleteListItem(id: Long)

    fun people(): Flow<List<KeepInTouch>>

    suspend fun savePerson(id: Long, name: String, everyDays: Int)

    suspend fun talkedTo(id: Long, on: LocalDate)

    suspend fun deletePerson(id: Long)

    fun trips(): Flow<List<Trip>>

    suspend fun upsertTrip(trip: Trip): Long

    suspend fun deleteTrip(id: Long)

    fun orders(): Flow<List<Order>>

    suspend fun upsertOrder(order: Order): Long

    suspend fun deleteOrder(id: Long)

    fun ious(): Flow<List<app.assistant.domain.engine.tasks.Iou>>

    suspend fun addIou(person: String, amount: app.assistant.domain.model.Paise, theyOwe: Boolean, note: String, on: LocalDate): Long

    suspend fun settleIou(id: Long, on: LocalDate)

    /** Settles everything open with [person]; returns how many entries were settled. */
    suspend fun settlePerson(person: String, on: LocalDate): Int

    suspend fun deleteIou(id: Long)
}

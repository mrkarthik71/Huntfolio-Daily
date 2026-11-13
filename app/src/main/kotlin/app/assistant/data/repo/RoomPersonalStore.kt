package app.assistant.data.repo

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.JournalEntryEntity
import app.assistant.data.db.entity.KeepInTouchEntity
import app.assistant.data.db.entity.ListItemEntity
import app.assistant.data.db.entity.NoteEntity
import app.assistant.data.db.entity.OrderItemEntity
import app.assistant.data.db.entity.TripEntity
import app.assistant.domain.model.JournalEntry
import app.assistant.domain.model.KeepInTouch
import app.assistant.domain.model.ListItem
import app.assistant.domain.model.Note
import app.assistant.domain.model.Order
import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.Trip
import app.assistant.domain.model.TripKind
import app.assistant.domain.port.PersonalStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** Room-backed [PersonalStore]. Text is trimmed and length-limited on the way in. */
@Suppress("TooManyFunctions") // six small collections behind one store
class RoomPersonalStore @Inject constructor(private val holder: DbProvider) : PersonalStore {
    private val dao get() = holder.database().personalDao()

    override fun notes(): Flow<List<Note>> = dao.notes().map { l -> l.map { Note(it.id, it.title, it.body, it.pinned, Instant.ofEpochMilli(it.updatedAt)) } }

    override suspend fun saveNote(id: Long, title: String, body: String, pinned: Boolean, at: Instant): Long =
        keep(id, dao.upsertNote(NoteEntity(id, title.trim().take(SHORT), body.trim().take(NOTE_MAX), pinned, at.toEpochMilli())))

    override suspend fun deleteNote(id: Long) = dao.deleteNote(id)

    override fun journal(): Flow<List<JournalEntry>> = dao.journal().map { l -> l.map { JournalEntry(LocalDate.parse(it.date), it.text) } }

    override suspend fun saveJournal(date: LocalDate, text: String) {
        val t = text.trim().take(NOTE_MAX)
        if (t.isEmpty()) dao.deleteJournal(date.toString()) else dao.upsertJournal(JournalEntryEntity(date.toString(), t))
    }

    override fun listItems(): Flow<List<ListItem>> = dao.listItems().map { l ->
        l.map { ListItem(it.id, it.listName, it.title, it.done, it.repeatDays, it.doneOn?.let(LocalDate::parse)) }
    }

    override suspend fun addListItem(list: String, title: String, repeatDays: Int?): Long =
        dao.upsertListItem(ListItemEntity(0, list.trim().take(LIST_MAX), title.trim().take(SHORT), false, repeatDays?.coerceIn(1, MAX_REPEAT), null))

    override suspend fun setListItemDone(id: Long, done: Boolean, on: LocalDate) = dao.setListItemDone(id, done, if (done) on.toString() else null)

    override suspend fun deleteListItem(id: Long) = dao.deleteListItem(id)

    override fun people(): Flow<List<KeepInTouch>> = dao.people().map { l ->
        l.map { KeepInTouch(it.id, it.name, it.everyDays, it.lastTalked?.let(LocalDate::parse)) }
    }

    override suspend fun savePerson(id: Long, name: String, everyDays: Int) {
        val last = if (id != 0L) dao.person(id)?.lastTalked else null
        dao.upsertPerson(KeepInTouchEntity(id, name.trim().take(LIST_MAX), everyDays.coerceIn(1, MAX_REPEAT), last))
    }

    override suspend fun talkedTo(id: Long, on: LocalDate) = dao.talkedTo(id, on.toString())

    override suspend fun deletePerson(id: Long) = dao.deletePerson(id)

    override fun trips(): Flow<List<Trip>> = dao.trips().map { l ->
        l.map {
            Trip(
                it.id,
                runCatching { TripKind.valueOf(it.kind) }.getOrDefault(TripKind.TRAIN),
                it.title,
                it.refCode,
                Instant.ofEpochMilli(it.startsAt),
                it.fromPlace,
                it.toPlace,
                it.sourceRef,
            )
        }
    }

    /** One row per booking: a second mail about the same booking updates it instead of adding a copy. */
    override suspend fun upsertTrip(trip: Trip): Long {
        val existing = dao.tripByRef(trip.sourceRef)?.id ?: trip.id
        return keep(
            existing,
            dao.upsertTrip(
                TripEntity(
                    existing,
                    trip.kind.name,
                    trip.title.trim().take(SHORT),
                    trip.ref?.take(REF_MAX),
                    trip.startsAt.toEpochMilli(),
                    trip.from?.take(LIST_MAX),
                    trip.to?.take(LIST_MAX),
                    trip.sourceRef.take(SHORT),
                ),
            ),
        )
    }

    override suspend fun deleteTrip(id: Long) = dao.deleteTrip(id)

    override fun orders(): Flow<List<Order>> = dao.orders().map { l ->
        l.map {
            Order(
                it.id,
                it.merchant,
                it.orderRef,
                runCatching { OrderStatus.valueOf(it.status) }.getOrDefault(OrderStatus.ORDERED),
                it.expectedOn?.let(LocalDate::parse),
                it.returnBy?.let(LocalDate::parse),
                Instant.ofEpochMilli(it.updatedAt),
            )
        }
    }

    /** Updates land on the order with the same number; a status never moves backwards (a late "shipped" mail after delivery). */
    override suspend fun upsertOrder(order: Order): Long {
        val old = dao.orderByRef(order.orderRef)
        val oldStatus = old?.let { runCatching { OrderStatus.valueOf(it.status) }.getOrNull() }
        val status = if (oldStatus != null && oldStatus.ordinal > order.status.ordinal) oldStatus else order.status
        val id = old?.id ?: order.id
        return keep(
            id,
            dao.upsertOrder(
                OrderItemEntity(
                    id,
                    order.merchant.trim().take(LIST_MAX),
                    order.orderRef.take(REF_MAX),
                    status.name,
                    (order.expectedOn ?: old?.expectedOn?.let(LocalDate::parse))?.toString(),
                    (order.returnBy ?: old?.returnBy?.let(LocalDate::parse))?.toString(),
                    order.updatedAt.toEpochMilli(),
                ),
            ),
        )
    }

    override suspend fun deleteOrder(id: Long) = dao.deleteOrder(id)

    override fun ious(): Flow<List<app.assistant.domain.engine.tasks.Iou>> = dao.ious().map { l ->
        l.map {
            app.assistant.domain.engine.tasks.Iou(
                it.id,
                it.person,
                app.assistant.domain.model.Paise(it.amountPaise),
                it.theyOwe,
                it.note,
                LocalDate.parse(it.createdOn),
                it.settledOn?.let(LocalDate::parse),
            )
        }
    }

    override suspend fun addIou(person: String, amount: app.assistant.domain.model.Paise, theyOwe: Boolean, note: String, on: LocalDate): Long = dao.upsertIou(
        app.assistant.data.db.entity.IouEntity(0, person.trim().take(LIST_MAX), amount.value, theyOwe, note.trim().take(SHORT), on.toString(), null),
    )

    override suspend fun settleIou(id: Long, on: LocalDate) = dao.settleIou(id, on.toString())

    override suspend fun settlePerson(person: String, on: LocalDate): Int = dao.settlePerson(person, on.toString())

    override suspend fun deleteIou(id: Long) = dao.deleteIou(id)

    private fun keep(existing: Long, result: Long): Long = if (existing != 0L) existing else result

    private companion object {
        const val SHORT = 200
        const val LIST_MAX = 60
        const val REF_MAX = 40
        const val NOTE_MAX = 10_000
        const val MAX_REPEAT = 365
    }
}

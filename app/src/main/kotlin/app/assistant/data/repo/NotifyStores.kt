package app.assistant.data.repo

import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.AlarmQueueEntity
import app.assistant.data.db.entity.NotificationLogEntity
import app.assistant.domain.engine.notify.GovernorState
import app.assistant.domain.engine.notify.ScheduleRule
import app.assistant.domain.model.NotificationType
import app.assistant.domain.port.AlarmStore
import app.assistant.domain.port.LogDecision
import app.assistant.domain.port.LogEntry
import app.assistant.domain.port.NotificationLog
import app.assistant.domain.port.QueuedAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAlarmStore @Inject constructor(private val holder: DbProvider) : AlarmStore {
    private val dao get() = holder.database().alarmDao()

    override suspend fun put(type: NotificationType, refKey: String, rule: ScheduleRule, dueAt: Instant, isSnooze: Boolean) {
        val (kind, date, minute) = when (rule) {
            is ScheduleRule.AtLocalTime -> Triple(AT_LOCAL, rule.date.toString(), rule.time.hour * 60 + rule.time.minute)
            is ScheduleRule.AtInstant -> Triple(AT_INSTANT, null, null)
        }
        // The unique (type, ref_key) index makes REPLACE overwrite an existing row for the same item.
        dao.put(
            AlarmQueueEntity(
                type = type.name,
                refKey = refKey,
                rule = kind,
                localDate = date,
                localMinute = minute,
                dueAt = dueAt.toEpochMilli(),
                isSnooze = isSnooze,
            ),
        )
    }

    override suspend fun all() = dao.all().map(::toDomain)

    override suspend fun due(upTo: Instant) = dao.due(upTo.toEpochMilli()).map(::toDomain)

    override suspend fun earliest() = dao.earliest()?.let(::toDomain)

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun deleteType(type: NotificationType) = dao.deleteType(type.name)

    override suspend fun setDue(id: Long, dueAt: Instant) = dao.setDue(id, dueAt.toEpochMilli())

    private fun toDomain(e: AlarmQueueEntity): QueuedAlarm {
        val rule = if (e.rule == AT_LOCAL && e.localDate != null && e.localMinute != null) {
            ScheduleRule.AtLocalTime(LocalDate.parse(e.localDate), LocalTime.of(e.localMinute / 60, e.localMinute % 60))
        } else {
            ScheduleRule.AtInstant(Instant.ofEpochMilli(e.dueAt))
        }
        return QueuedAlarm(e.id, NotificationType.valueOf(e.type), e.refKey, rule, Instant.ofEpochMilli(e.dueAt), e.isSnooze)
    }

    private companion object {
        const val AT_LOCAL = "AT_LOCAL_TIME"
        const val AT_INSTANT = "AT_INSTANT"
    }
}

@Singleton
class RoomNotificationLog @Inject constructor(private val holder: DbProvider) : NotificationLog {
    private val dao get() = holder.database().notificationLogDao()

    override suspend fun record(entry: LogEntry): Long = dao.insert(
        NotificationLogEntity(
            day = entry.day,
            type = entry.type.name,
            refKey = entry.refKey,
            decision = entry.decision.name,
            ruleId = entry.ruleId,
            at = entry.at.toEpochMilli(),
            counted = entry.counted,
            typeCounted = entry.typeCounted,
            summary = entry.summary,
        ),
    )

    override suspend fun get(id: Long): LogEntry? = dao.get(id)?.let(::toDomain)

    override suspend fun stateFor(day: LocalDate): GovernorState {
        val d = day.toString()
        val byType = dao.typeCountedOn(d).mapNotNull { c -> NotificationType.entries.firstOrNull { it.name == c.type }?.let { it to c.n } }.toMap()
        return GovernorState(day, dao.countedOn(d), byType)
    }

    override suspend fun handledRefKeys(type: NotificationType): Set<String> = dao.refKeysOf(type.name).toSet()

    override fun inbox(): Flow<List<LogEntry>> = dao.inbox().map { rows -> rows.map(::toDomain) }

    private fun toDomain(e: NotificationLogEntity) = LogEntry(
        id = e.id,
        day = e.day,
        type = NotificationType.valueOf(e.type),
        refKey = e.refKey,
        decision = LogDecision.valueOf(e.decision),
        ruleId = e.ruleId,
        at = Instant.ofEpochMilli(e.at),
        counted = e.counted,
        typeCounted = e.typeCounted,
        summary = e.summary,
    )
}

package app.assistant.data.repo

import app.assistant.data.db.entity.GoalEntity
import app.assistant.data.db.entity.TaskEntity
import app.assistant.data.db.entity.TimeBlockEntity
import app.assistant.domain.model.Goal
import app.assistant.domain.model.GoalId
import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.InstantRange
import app.assistant.domain.model.Quantity
import app.assistant.domain.model.Task
import app.assistant.domain.model.TaskId
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.model.TimeBlock
import app.assistant.domain.model.TimeBlockId
import app.assistant.domain.model.TimeBlockKind
import java.time.Instant
import java.time.LocalDate

internal fun GoalEntity.toDomain() = Goal(
    id = GoalId(id),
    name = name,
    period = GoalPeriod.valueOf(period),
    startDate = LocalDate.parse(startDate),
    target = Quantity(targetHundredths),
    unit = unit,
    parentId = parentGoalId?.let(::GoalId),
    createdAt = Instant.ofEpochMilli(createdAt),
    archivedAt = archivedAt?.let(Instant::ofEpochMilli),
)

internal fun TaskEntity.toDomain() = Task(
    id = TaskId(id),
    title = title,
    status = TaskStatus.valueOf(status),
    dueDate = dueDate?.let(LocalDate::parse),
    plannedDate = plannedDate?.let(LocalDate::parse),
    estimateMinutes = estimateMinutes,
    goalId = goalId?.let(::GoalId),
    createdAt = Instant.ofEpochMilli(createdAt),
    completedAt = completedAt?.let(Instant::ofEpochMilli),
)

internal fun TimeBlockEntity.toDomain() = TimeBlock(
    id = TimeBlockId(id),
    taskId = taskId?.let(::TaskId),
    range = InstantRange(Instant.ofEpochMilli(startAt), Instant.ofEpochMilli(endAt)),
    kind = TimeBlockKind.valueOf(kind),
    conflicting = conflicting,
)

internal fun TimeBlock.toEntity(date: LocalDate) = TimeBlockEntity(
    date = date.toString(),
    startAt = range.start.toEpochMilli(),
    endAt = range.end.toEpochMilli(),
    taskId = taskId?.value,
    kind = kind.name,
    conflicting = conflicting,
)

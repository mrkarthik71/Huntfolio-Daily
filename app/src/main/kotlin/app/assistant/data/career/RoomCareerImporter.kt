package app.assistant.data.career

import androidx.room.withTransaction
import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.GoalEntity
import app.assistant.data.db.entity.ImportRecordEntity
import app.assistant.data.db.entity.TaskEntity
import app.assistant.domain.model.DataSource
import app.assistant.domain.model.TaskStatus
import app.assistant.domain.port.AuditLog
import app.assistant.domain.port.TimeSource
import app.assistant.parse.huntfolio.CareerImporter
import app.assistant.parse.huntfolio.HuntfolioImport
import app.assistant.parse.huntfolio.HuntfolioRecord
import app.assistant.parse.huntfolio.ImportPreview
import app.assistant.parse.huntfolio.ImportResult
import app.assistant.parse.huntfolio.SkillChoice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed Career_Importer (Requirement 59). [preview] counts each record type and how many keys
 * are already imported; [commit] creates Tasks for new follow-ups and Goals for the chosen skills in
 * ONE transaction, skips keys already in `import_record`, records the new keys, and writes one
 * Audit_Log read entry. Re-importing the same file is a no-op (Property 78).
 */
@Singleton
class RoomCareerImporter @Inject constructor(
    private val holder: DatabaseHolder,
    private val audit: AuditLog,
    private val time: TimeSource,
) : CareerImporter {

    override suspend fun preview(import: HuntfolioImport): ImportPreview {
        val known = holder.database().importRecordDao().allKeys().toHashSet()
        val followUps = import.records.filterIsInstance<HuntfolioRecord.FollowUp>()
        val skills = import.records.filterIsInstance<HuntfolioRecord.Skill>()
        return ImportPreview(
            followUps = followUps.size,
            skills = skills.size,
            followUpsAlreadyImported = followUps.count { it.key in known },
            skillsAlreadyImported = skills.count { it.key in known },
        )
    }

    override suspend fun commit(import: HuntfolioImport, skills: List<SkillChoice>): ImportResult {
        val db = holder.database()
        val importDao = db.importRecordDao()
        val taskDao = db.taskDao()
        val goalDao = db.goalDao()
        val now = time.now()
        val chosen = skills.associateBy { it.key }

        val result = db.withTransaction {
            val known = importDao.allKeys().toHashSet()
            var tasks = 0
            var goals = 0
            var skipped = 0
            for (record in import.records) {
                if (record.key in known) {
                    skipped++
                    continue
                }
                when (record) {
                    is HuntfolioRecord.FollowUp -> {
                        taskDao.insert(
                            TaskEntity(
                                title = record.title,
                                status = TaskStatus.OPEN.name,
                                dueDate = record.dueDate?.toString(),
                                plannedDate = null,
                                estimateMinutes = null,
                                goalId = null,
                                createdAt = now.toEpochMilli(),
                                completedAt = null,
                                completedDate = null,
                                sourceRef = null,
                            ),
                        )
                        importDao.insert(ImportRecordEntity(record.key, now.toEpochMilli()))
                        tasks++
                    }
                    is HuntfolioRecord.Skill -> {
                        val choice = chosen[record.key]
                        if (choice == null) {
                            // A skill the user did not select is not imported and not marked known,
                            // so it can be chosen on a later import.
                            continue
                        }
                        goalDao.insert(
                            GoalEntity(
                                name = record.name,
                                period = choice.period.name,
                                startDate = time.today().toString(),
                                targetHundredths = choice.target.hundredths,
                                unit = choice.unit,
                                parentGoalId = null,
                                createdAt = now.toEpochMilli(),
                                archivedAt = null,
                            ),
                        )
                        importDao.insert(ImportRecordEntity(record.key, now.toEpochMilli()))
                        goals++
                    }
                }
            }
            ImportResult(tasksCreated = tasks, goalsCreated = goals, skipped = skipped)
        }
        audit.recordRead(DataSource.HUNTFOLIO, now, itemCount = import.records.size, success = true)
        return result
    }
}

package app.assistant.domain.career

import app.assistant.domain.model.GoalPeriod
import app.assistant.domain.model.Quantity

/**
 * The Career_Importer (Requirement 59, design: Career_Importer). It previews an import (counts, and
 * how many are already imported) and commits it in one transaction: Tasks for new follow-ups, Goals
 * for the selected skills, skipping keys already imported, and an Audit_Log read entry. Importing the
 * same file twice creates nothing new (Property 78). Lives in the parse layer because it bridges the
 * parsed Huntfolio records to domain types; the Room-backed implementation is in the data layer.
 */
interface CareerImporter {
    suspend fun preview(import: HuntfolioImport): ImportPreview

    suspend fun commit(import: HuntfolioImport, skills: List<SkillChoice>): ImportResult
}

/** One skill the user chose to turn into a Goal, with its Goal_Period, target and unit (Requirement 59.4). */
data class SkillChoice(val key: String, val period: GoalPeriod, val target: Quantity, val unit: String)

data class ImportPreview(val followUps: Int, val skills: Int, val followUpsAlreadyImported: Int, val skillsAlreadyImported: Int)

data class ImportResult(val tasksCreated: Int, val goalsCreated: Int, val skipped: Int)

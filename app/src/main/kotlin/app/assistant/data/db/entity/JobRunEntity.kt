package app.assistant.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last run of each scheduled job (Requirement 62.1), so the Health_Check can tell whether a job
 * is missing its window. One row per job name, updated each run; no history is kept.
 */
@Entity(tableName = "job_run")
data class JobRunEntity(@PrimaryKey val job: String, @androidx.room.ColumnInfo(name = "last_run_at") val lastRunAt: Long)

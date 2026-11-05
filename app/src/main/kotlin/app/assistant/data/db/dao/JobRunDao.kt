package app.assistant.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.assistant.data.db.entity.JobRunEntity

@Dao
interface JobRunDao {
    @Upsert
    suspend fun record(run: JobRunEntity)

    @Query("SELECT * FROM job_run")
    suspend fun all(): List<JobRunEntity>

    @Query("SELECT last_run_at FROM job_run WHERE job = :job")
    suspend fun lastRun(job: String): Long?
}

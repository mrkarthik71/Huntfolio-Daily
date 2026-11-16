package app.assistant.data.health

import app.assistant.data.db.DatabaseHolder
import app.assistant.data.db.entity.JobRunEntity
import app.assistant.domain.port.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/** Records a scheduled job's last successful run so the Health_Check can tell if it is missing its window. */
@Singleton
class JobRunRecorder @Inject constructor(private val holder: DatabaseHolder, private val time: TimeSource) {
    suspend fun record(job: String) {
        runCatching { holder.database().jobRunDao().record(JobRunEntity(job, time.now().toEpochMilli())) }
    }
}

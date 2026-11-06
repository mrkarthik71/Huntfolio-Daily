package app.assistant.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.assistant.domain.port.BodySignals
import app.assistant.domain.port.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last night's sleep and today's steps from Health Connect, read-only. Nothing is stored: the
 * numbers are read when a screen or the day planner asks. Without Health Connect or the two read
 * permissions every value is null.
 */
@Singleton
class HealthConnectSignals @Inject constructor(@param:ApplicationContext private val context: Context, private val time: TimeSource) : BodySignals {
    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    override suspend fun connected(): Boolean = runCatching {
        client()?.permissionController?.getGrantedPermissions()?.containsAll(PERMISSIONS) == true
    }.getOrDefault(false)

    override suspend fun lastNightSleepMinutes(): Int? = runCatching {
        val c = client() ?: return null
        val zone = time.zone()
        val today = time.today()
        // The night that ended this morning: sessions that ended between 18:00 yesterday and 14:00 today.
        val from = today.minusDays(1).atTime(EVENING, 0).atZone(zone).toInstant()
        val to = today.atTime(AFTERNOON, 0).atZone(zone).toInstant()
        val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records
        sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt().takeIf { it > 0 }
    }.getOrNull()

    override suspend fun stepsToday(): Long? = runCatching {
        val c = client() ?: return null
        val start = time.today().atStartOfDay(time.zone()).toInstant()
        c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, time.now())))[StepsRecord.COUNT_TOTAL]
    }.getOrNull()

    companion object {
        val PERMISSIONS = setOf(HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(SleepSessionRecord::class))
        private const val EVENING = 18
        private const val AFTERNOON = 14
    }
}

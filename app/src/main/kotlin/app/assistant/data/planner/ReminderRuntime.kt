package app.assistant.data.planner

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.assistant.R
import app.assistant.data.db.DbProvider
import app.assistant.data.db.entity.SmartReminderEntity
import app.assistant.domain.engine.schedule.ReminderRules
import app.assistant.domain.model.DeviceTrigger
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.ReminderId
import app.assistant.domain.model.ReminderKind
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.ImmediateAlert
import app.assistant.domain.port.NotificationScheduling
import app.assistant.domain.port.TimeSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms and fires smart reminders (Module C), all on the phone:
 * - TIME: a one-off WorkManager job per reminder;
 * - PLACE: the platform proximity alert (LocationManager, no Google services), only with location permission;
 * - DEVICE: charging through a WorkManager job that only runs while charging; car Bluetooth through
 *   the ACL_CONNECTED broadcast.
 * Every fire goes through the Notification_Governor ([NotificationScheduling.offerNow]).
 */
@Singleton
class ReminderRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val holder: DbProvider,
    private val notifications: NotificationScheduling,
    private val time: TimeSource,
) : app.assistant.domain.port.ReminderScheduler {
    private val dao get() = holder.database().apexDao()

    /** Re-arms everything from the stored reminders; safe to call any time (idempotent). */
    override suspend fun sync() {
        val wm = WorkManager.getInstance(context)
        val now = time.now()
        dao.activeReminders(ReminderKind.TIME.name).forEach { r ->
            val at = r.fireAt?.let(Instant::ofEpochMilli) ?: return@forEach
            if (r.lastFiredAt != null) return@forEach
            val delay = Duration.between(now, at).toMillis().coerceAtLeast(0)
            wm.enqueueUniqueWork(
                "reminder-time-${r.id}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TimeReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(workDataOf(KEY_ID to r.id)).build(),
            )
        }
        val charging = dao.activeReminders(ReminderKind.DEVICE.name).any { it.device == DeviceTrigger.CHARGING.name }
        if (charging) {
            wm.enqueueUniquePeriodicWork(
                CHARGING_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ChargingReminderWorker>(CHARGING_PERIOD_MIN, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                    .build(),
            )
        } else {
            wm.cancelUniqueWork(CHARGING_WORK)
        }
        armPlaces()
    }

    /** Cancels whatever was armed for one reminder (after it is deleted or switched off). */
    override fun disarm(id: ReminderId) {
        WorkManager.getInstance(context).cancelUniqueWork("reminder-time-${id.value}")
        runCatching { locationManager()?.removeProximityAlert(placeIntent(id.value)) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun armPlaces() {
        val lm = locationManager() ?: return
        if (!hasLocation()) return
        dao.activeReminders(ReminderKind.PLACE.name).forEach { r ->
            val lat = r.latitude ?: return@forEach
            val lng = r.longitude ?: return@forEach
            val radius = (r.radiusMeters ?: ReminderRules.MIN_RADIUS_M).coerceIn(ReminderRules.MIN_RADIUS_M, ReminderRules.MAX_RADIUS_M)
            runCatching { lm.addProximityAlert(lat, lng, radius.toFloat(), NO_EXPIRY, placeIntent(r.id)) }
        }
    }

    suspend fun fireTime(id: Long) {
        val r = dao.activeReminders(ReminderKind.TIME.name).firstOrNull { it.id == id } ?: return
        if (ReminderRules.timeDue(r.toRule(), time.now(), r.lastFiredAt?.let(Instant::ofEpochMilli))) fire(r)
    }

    suspend fun onDevice(trigger: DeviceTrigger) {
        val zone = time.zone()
        val now = time.now()
        val local = now.atZone(zone)
        dao.activeReminders(ReminderKind.DEVICE.name).forEach { r ->
            if (ReminderRules.deviceFires(
                    r.toRule(),
                    trigger,
                    local.toLocalTime(),
                    local.toLocalDate(),
                    r.lastFiredAt?.let(Instant::ofEpochMilli),
                    zone,
                )
            ) {
                fire(r)
            }
        }
    }

    suspend fun onPlace(id: Long, entering: Boolean) {
        val r = dao.activeReminders(ReminderKind.PLACE.name).firstOrNull { it.id == id } ?: return
        if (ReminderRules.placeFires(r.toRule(), entering, time.now(), r.lastFiredAt?.let(Instant::ofEpochMilli))) fire(r)
    }

    private suspend fun fire(r: SmartReminderEntity) {
        val now = time.now()
        notifications.offerNow(
            ImmediateAlert(
                type = NotificationType.SMART_REMINDER,
                refKey = "reminder:${r.id}:${now.atZone(time.zone()).toLocalDate()}",
                title = context.getString(R.string.reminder_alert_title),
                text = r.message,
                route = ROUTE,
            ),
        )
        dao.markReminderFired(r.id, now.toEpochMilli())
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationManager(): LocationManager? = context.getSystemService(LocationManager::class.java)

    private fun placeIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderEventReceiver::class.java).setAction(ACTION_PLACE).putExtra(KEY_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    companion object {
        const val KEY_ID = "reminder_id"
        const val ACTION_PLACE = "app.apex.action.REMINDER_PLACE"
        const val ROUTE = "settings/reminders"
        private const val CHARGING_WORK = "reminder-charging"
        private const val CHARGING_PERIOD_MIN = 15L
        private const val NO_EXPIRY = -1L
    }
}

private fun SmartReminderEntity.toRule() = app.assistant.domain.model.SmartReminder(
    id = ReminderId(id),
    message = message,
    kind = ReminderKind.valueOf(kind),
    fireAt = fireAt?.let(Instant::ofEpochMilli),
    onEnter = onEnter,
    device = device?.let { d -> runCatching { DeviceTrigger.valueOf(d) }.getOrNull() },
    notBefore = notBeforeMinute?.let { LocalTime.ofSecondOfDay(it * 60L) },
    active = active,
)

@HiltWorker
class TimeReminderWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters, private val runtime: ReminderRuntime) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { runtime.fireTime(inputData.getLong(ReminderRuntime.KEY_ID, -1)) }
        return Result.success()
    }
}

/** Runs only while the phone is charging (a WorkManager constraint), so its run is the "charging" signal. */
@HiltWorker
class ChargingReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runtime: ReminderRuntime,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { runtime.onDevice(DeviceTrigger.CHARGING) }
        return Result.success()
    }
}

/** Proximity alerts and car-Bluetooth connections. It reads no message content and stores nothing itself. */
@AndroidEntryPoint
class ReminderEventReceiver : BroadcastReceiver() {
    @Inject lateinit var runtime: ReminderRuntime

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ReminderRuntime.ACTION_PLACE -> runtime.onPlace(
                        intent.getLongExtra(ReminderRuntime.KEY_ID, -1),
                        intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true),
                    )
                    android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = androidx.core.content.IntentCompat.getParcelableExtra(
                            intent,
                            android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                            android.bluetooth.BluetoothDevice::class.java,
                        )
                        val cls = runCatching { device?.bluetoothClass?.deviceClass }.getOrNull()
                        val car =
                            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                                cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
                        if (car) runtime.onDevice(DeviceTrigger.CAR_BLUETOOTH)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

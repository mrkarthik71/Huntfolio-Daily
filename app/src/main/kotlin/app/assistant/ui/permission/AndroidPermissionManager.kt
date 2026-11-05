package app.assistant.ui.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation. The single activity calls [attach] in onCreate so runtime requests
 * can use an ActivityResultLauncher. Nothing is requested at first launch (Requirement 5.1):
 * requests happen only when the User turns a feature on.
 */
@Singleton
class AndroidPermissionManager @Inject constructor(@param:ApplicationContext private val context: Context) : PermissionManager {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var activityRef: WeakReference<ComponentActivity>? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pendingRuntime: CompletableDeferred<Unit>? = null
    private val _revocations = MutableSharedFlow<Feature>(extraBufferCapacity = 16)

    override val revocations: Flow<Feature> = _revocations.asSharedFlow()

    fun attach(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingRuntime?.complete(Unit)
        }
    }

    fun grantFor(feature: Feature): Grant = when (feature) {
        Feature.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Grant.RuntimeGroup(listOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                Grant.Implicit
            }
        Feature.CALENDAR -> Grant.RuntimeGroup(listOf(Manifest.permission.READ_CALENDAR))
        Feature.SMS -> Grant.RuntimeGroup(listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
        Feature.MICROPHONE -> Grant.RuntimeGroup(listOf(Manifest.permission.RECORD_AUDIO))
        Feature.NOTIFICATION_ACCESS -> Grant.NotificationAccessPage
        Feature.EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Grant.ExactAlarmPage else Grant.Implicit
    }

    private fun isGranted(feature: Feature): Boolean = when (val g = grantFor(feature)) {
        is Grant.RuntimeGroup -> g.permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } &&
            (feature != Feature.NOTIFICATIONS || NotificationManagerCompat.from(context).areNotificationsEnabled())
        Grant.NotificationAccessPage ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        Grant.ExactAlarmPage ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Grant.Implicit ->
            feature != Feature.NOTIFICATIONS || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override fun status(feature: Feature): PermissionStatus {
        val granted = isGranted(feature)
        val requested = prefs.getBoolean(requestedKey(feature), false)
        val activity = activityRef?.get()
        val willShow = when (val g = grantFor(feature)) {
            is Grant.RuntimeGroup ->
                activity != null &&
                    g.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            // Settings pages can always be opened again.
            else -> true
        }
        return PermissionLogic.status(granted, requested, willShow)
    }

    override suspend fun request(feature: Feature): PermissionStatus {
        when (val g = grantFor(feature)) {
            is Grant.RuntimeGroup -> {
                val l = launcher ?: return status(feature)
                val wait = CompletableDeferred<Unit>()
                pendingRuntime = wait
                l.launch(g.permissions.toTypedArray())
                wait.await()
                pendingRuntime = null
            }
            Grant.NotificationAccessPage -> openNotificationAccessSettings()
            Grant.ExactAlarmPage -> openExactAlarmSettings()
            Grant.Implicit -> Unit
        }
        prefs.edit().putBoolean(requestedKey(feature), true).apply()
        rememberGranted()
        return status(feature)
    }

    override fun openAppSettings() = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    override fun openNotificationAccessSettings() = start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
        }
    }

    override fun checkRevocations() {
        val previous = prefs.getStringSet(GRANTED_KEY, emptySet()).orEmpty()
            .mapNotNull { name -> Feature.entries.firstOrNull { it.name == name } }.toSet()
        val now = Feature.entries.filter(::isGranted).toSet()
        PermissionLogic.revoked(previous, now).forEach { _revocations.tryEmit(it) }
        prefs.edit().putStringSet(GRANTED_KEY, now.map { it.name }.toSet()).apply()
    }

    private fun rememberGranted() {
        val now = Feature.entries.filter(::isGranted).map { it.name }.toSet()
        prefs.edit().putStringSet(GRANTED_KEY, now).apply()
    }

    private fun start(intent: Intent) {
        val activity = activityRef?.get()
        if (activity != null) {
            runCatching { activity.startActivity(intent) }
        } else {
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun requestedKey(feature: Feature) = "requested.${feature.name}"

    private companion object {
        // Holds only feature names and booleans; no user data.
        const val PREFS = "permission_state"
        const val GRANTED_KEY = "granted"
    }
}

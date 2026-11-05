package app.assistant.data.reader

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import app.assistant.domain.port.InstalledApp
import app.assistant.domain.port.InstalledApps
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Lists the apps with a launcher entry by label. It reads labels only, never icons, notifications or app data. */
@Singleton
class AndroidInstalledApps @Inject constructor(@ApplicationContext private val context: Context) : InstalledApps {
    override suspend fun launcherApps(): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return launcherActivities(pm, launcher)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    override suspend fun label(packageName: String): String? {
        val pm = context.packageManager
        return try {
            applicationInfo(pm, packageName).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun launcherActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }

    @Throws(PackageManager.NameNotFoundException::class)
    private fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }
}

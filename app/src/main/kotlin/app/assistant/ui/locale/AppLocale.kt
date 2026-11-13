package app.assistant.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, chosen in Settings, independent of the phone's. On Android 13 and newer
 * it is Android's per-app language (the same one Settings › Apps › Apex › Language shows); before
 * that, Apex keeps the choice in a private preference and applies it to every context it creates.
 * An empty tag means "follow the phone".
 */
object AppLocale {
    const val SYSTEM = ""
    val CHOICES = listOf(SYSTEM, "en", "hi", "ta")
    private const val PREFS = "app_language"
    private const val KEY = "tag"

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language ?: SYSTEM
    } else {
        saved(context)
    }

    /** Applies [tag] and redraws the screen in it. */
    fun set(activity: Activity, tag: String) {
        val clean = tag.takeIf { it in CHOICES } ?: SYSTEM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android stores it and recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (clean == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(clean)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean).commit()
            // The application's own resources (used for notifications) follow at once too.
            val app = activity.applicationContext
            val locale = if (clean == SYSTEM) LocaleList.getDefault()[0] else Locale.forLanguageTag(clean)
            Locale.setDefault(locale)
            val cfg = Configuration(app.resources.configuration).apply { setLocale(locale) }
            @Suppress("DEPRECATION") // the only way to refresh the application's resources in place before Android 13
            app.resources.updateConfiguration(cfg, app.resources.displayMetrics)
            activity.recreate()
        }
    }

    /** For attachBaseContext of the Application and activities: the context in the chosen language (before Android 13). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = saved(base).takeIf { it != SYSTEM } ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(cfg)
    }

    private fun saved(context: Context): String = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
    }.getOrDefault(SYSTEM)
}

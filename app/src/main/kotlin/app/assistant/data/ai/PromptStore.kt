package app.assistant.data.ai

import android.content.Context
import app.assistant.domain.model.PromptType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the versioned prompt body for a Prompt_Type from `assets/prompts/<TYPE>/v1.md` (Requirement
 * 48.5, task 30.1). The schema version is pinned here so a prompt change ships with a matching
 * schema; a missing or unreadable prompt yields null and the feature uses the rule-based result.
 */
@Singleton
class PromptStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = HashMap<PromptType, String?>()

    fun prompt(type: PromptType, version: Int = CURRENT_VERSION): String? = synchronized(cache) {
        cache.getOrPut(type) {
            runCatching { context.assets.open("prompts/${type.name}/v$version.md").bufferedReader().use { it.readText() } }.getOrNull()
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

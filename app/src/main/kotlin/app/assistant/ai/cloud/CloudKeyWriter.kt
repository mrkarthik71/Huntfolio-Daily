package app.assistant.ai.cloud

/**
 * Stores or clears the Cloud AI key (Requirement 45.1). Only the `personal` flavour implements a
 * real write (into the encrypted `secret` table); the `shared` flavour is a no-op because it never
 * holds a key. Kept separate from [CloudKeySource] so a screen can offer key entry without reading
 * the key back.
 */
interface CloudKeyWriter {
    /** True only in a build that can hold a key (personal). The settings UI hides key entry otherwise. */
    val supported: Boolean

    /** Stores [key] encrypted. Blank clears it. Returns true on success. */
    suspend fun setKey(key: String): Boolean

    /** True when a key is currently stored. */
    suspend fun hasKey(): Boolean
}

package app.assistant.domain.port

import app.assistant.domain.error.Outcome
import app.assistant.domain.model.AiMode
import app.assistant.domain.model.PromptType

// AI ports implemented by the data layer and consumed by the ai layer and the settings UI. They
// live in domain so data and ui never depend on ai (design: Dependency rules).

/** Everything the gateway needs to know about the live AI configuration, read fresh per call. */
interface AiConfig {
    /** The active AI_Mode. */
    suspend fun mode(): AiMode

    /**
     * A monotonically-increasing epoch that changes whenever AI_Mode or the key changes. The gateway
     * captures it before a send and drops a result whose epoch no longer matches (Requirement 42.7).
     */
    suspend fun epoch(): Long

    /** The configured monthly cap in paise; 0 means no cap set. */
    suspend fun capPaise(): Long

    /** False when Cloud cannot run: no cap, or (in the personal flavour) no key (Requirement 45.1, 45.2). */
    suspend fun cloudConfigured(): Boolean

    /** The per-Prompt_Type model name, or null for the adapter default. */
    suspend fun model(type: PromptType): String?
}

/** Reports whether the device currently has a usable network (Requirement 43.1). */
fun interface Connectivity {
    fun isOnline(): Boolean
}

/**
 * Where the Cloud AI key comes from, abstracted so the two flavours differ only here (Requirement
 * 42.6, 45.5): the `personal` flavour reads the key from the `secret` table via the Key_Manager; the
 * `shared` flavour has NO key and returns [Proxy], so the request goes through the Proxy_Server. The
 * key, when present, is held only for the duration of one call and never logged or stored elsewhere.
 */
sealed interface CloudCredential {
    /** A direct vendor call with a bearer key (personal flavour). */
    data class Direct(val apiKey: String) : CloudCredential

    /** Route through the Proxy_Server; no key on the device (shared flavour). */
    data object Proxy : CloudCredential
}

fun interface CloudKeySource {
    /** Returns the credential for a Cloud call, or null when Cloud is not usable (no key configured). */
    suspend fun credential(): CloudCredential?
}

/** The `shared`-flavour transport: forwards an already-redacted request body to the Proxy_Server. */
fun interface ProxyClient {
    /** Sends [body] for [model] and returns the raw response text, or an [AiFailure]. */
    suspend fun forward(model: String?, body: String): Outcome<String, AiFailure>
}

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

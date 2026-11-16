package app.assistant.data.ai

import app.assistant.domain.port.CloudKeyWriter
import javax.inject.Inject
import javax.inject.Singleton

/** Shared flavour: no device key, so key entry is not supported (Requirement 45.2). */
@Singleton
class SharedCloudKeyWriter @Inject constructor() : CloudKeyWriter {
    override val supported: Boolean = false

    override suspend fun setKey(key: String): Boolean = false

    override suspend fun hasKey(): Boolean = false
}

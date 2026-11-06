package app.assistant.data.health

import app.assistant.domain.port.BodySignals
import javax.inject.Inject

/** Placeholder until Health Connect is connected: no sleep or step data. */
class NoBodySignals @Inject constructor() : BodySignals {
    override suspend fun connected(): Boolean = false

    override suspend fun lastNightSleepMinutes(): Int? = null

    override suspend fun stepsToday(): Long? = null
}

package app.assistant.data.money

import app.assistant.domain.port.DuplicateWindowProvider
import java.time.Duration
import javax.inject.Inject

/** The design's default Duplicate_Window, 15 minutes. Replace this binding when the setting exists. */
class FixedDuplicateWindow @Inject constructor() : DuplicateWindowProvider {
    override suspend fun window(): Duration = DEFAULT

    companion object {
        val DEFAULT: Duration = Duration.ofMinutes(15)
    }
}

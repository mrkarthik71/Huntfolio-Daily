package app.assistant.testing

import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Fixed, controllable clock for tests. Defaults to 2026-11-03 09:00 IST. */
class FakeTimeSource(var instant: Instant = Instant.parse("2026-11-03T03:30:00Z"), var zoneId: ZoneId = ZoneId.of("Asia/Kolkata")) : TimeSource {
    private val changes = MutableSharedFlow<ZoneId>(extraBufferCapacity = 8)

    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId

    override val zoneChanges: Flow<ZoneId> = changes

    fun advance(by: Duration) {
        instant = instant.plus(by)
    }

    fun changeZone(to: ZoneId) {
        zoneId = to
        changes.tryEmit(to)
    }
}

/** All three dispatchers share one test dispatcher, so `advanceUntilIdle` drives everything. */
class TestDispatcherProvider(val dispatcher: CoroutineDispatcher = StandardTestDispatcher()) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

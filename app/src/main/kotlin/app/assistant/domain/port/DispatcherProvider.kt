package app.assistant.domain.port

import kotlinx.coroutines.CoroutineDispatcher

/** Injected dispatchers, so tests can replace them with a StandardTestDispatcher. */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

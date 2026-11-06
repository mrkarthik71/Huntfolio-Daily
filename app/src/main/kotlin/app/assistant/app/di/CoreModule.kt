package app.assistant.app.di

import app.assistant.data.time.SystemTimeSource
import app.assistant.domain.port.ApplicationScope
import app.assistant.domain.port.DispatcherProvider
import app.assistant.domain.port.TimeSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindings {
    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource

    @Binds
    abstract fun crashReports(impl: app.assistant.security.crash.CrashRecorder): app.assistant.domain.port.CrashReports
}

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun dispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Main
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(dispatchers: DispatcherProvider, crashes: app.assistant.security.crash.CrashRecorder): CoroutineScope =
        // An error in one background job (a receiver, the notification reader, a reminder) is recorded and dropped:
        // it must never take the whole app down.
        CoroutineScope(SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, e -> crashes.record(e) })
}

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindings {
    @Binds
    abstract fun timeSource(impl: SystemTimeSource): TimeSource
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
    fun applicationScope(dispatchers: DispatcherProvider): CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
}

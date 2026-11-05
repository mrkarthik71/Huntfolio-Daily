package app.assistant.app.di

import app.assistant.data.calendar.AndroidCalendarSource
import app.assistant.data.calendar.CalendarRepositoryImpl
import app.assistant.domain.port.CalendarRepository
import app.assistant.domain.port.CalendarSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase 2 Calendar bindings (design: Calendar_Reader). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarModule {
    @Binds
    @Singleton
    abstract fun bindCalendarSource(impl: AndroidCalendarSource): CalendarSource

    @Binds
    @Singleton
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository
}

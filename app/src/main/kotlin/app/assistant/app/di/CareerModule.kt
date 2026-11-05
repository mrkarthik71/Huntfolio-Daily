package app.assistant.app.di

import app.assistant.data.career.RoomCareerImporter
import app.assistant.parse.huntfolio.CareerImporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Phase 7 career import wiring. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CareerModule {
    @Binds
    abstract fun careerImporter(impl: RoomCareerImporter): CareerImporter
}

package app.assistant.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.assistant.BuildConfig
import app.assistant.data.db.RoomDiagnosticSink
import app.assistant.security.lock.AppLock
import app.assistant.security.log.LogcatSink
import app.assistant.security.log.SafeLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application entry point. Hilt builds the dependency graph from here. */
@HiltAndroidApp
class AssistantApplication :
    Application(),
    Configuration.Provider {

    @Inject lateinit var safeLog: SafeLog

    @Inject lateinit var diagnosticSink: RoomDiagnosticSink

    @Inject lateinit var appLock: AppLock

    @Inject lateinit var startup: AppStartup

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** WorkManager is initialised on demand with the Hilt factory (see the manifest provider removal). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        safeLog.addSink(diagnosticSink)
        if (BuildConfig.DEBUG) safeLog.addSink(LogcatSink())
        // The whole process going to the background starts the Lock_Timeout (Requirement 6.2).
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLock)
        // Notification channels + scheduler realignment (Requirements 17.6, 18.9, 20.7).
        startup.run()
    }
}

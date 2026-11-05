package app.assistant.domain.port

import javax.inject.Qualifier

/**
 * Qualifier for the application-wide CoroutineScope (SupervisorJob) that runs work started by
 * receivers and the notification listener. JSR-330 annotations are allowed in the domain layer.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

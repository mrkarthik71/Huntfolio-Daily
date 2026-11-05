package app.assistant.security.lock

import java.time.Duration

/**
 * Pure rule for re-locking (Requirement 6.2). Times are elapsed-realtime milliseconds, so
 * changing the wall clock cannot skip the lock.
 */
object LockPolicy {
    /**
     * True when the app has been in the background for longer than [lockTimeout], or when the
     * elapsed clock went backwards (which only happens after a reboot), or when no background
     * time is known.
     */
    fun requiresAuth(backgroundedAtElapsed: Long?, nowElapsed: Long, lockTimeout: Duration): Boolean {
        if (backgroundedAtElapsed == null) return true
        val away = nowElapsed - backgroundedAtElapsed
        if (away < 0) return true
        return away > lockTimeout.toMillis()
    }
}

enum class LockState {
    /** User data is hidden; the lock screen is shown. */
    LOCKED,

    /** An authentication prompt is on screen. */
    UNLOCKING,

    UNLOCKED,

    /** The device has no screen lock, so the app cannot be unlocked (Requirement 6.4). */
    NO_DEVICE_LOCK,
}

enum class AuthResult { SUCCESS, FAILED, CANCELLED, NO_CREDENTIAL }

/**
 * Lock state machine without Android dependencies. It always starts locked (Requirement 6.1).
 */
class LockStateMachine(initial: LockState = LockState.LOCKED) {
    var state: LockState = initial
        private set
    private var backgroundedAt: Long? = null

    fun onBackground(nowElapsed: Long) {
        if (state == LockState.UNLOCKED) backgroundedAt = nowElapsed
    }

    fun onForeground(nowElapsed: Long, lockTimeout: Duration) {
        if (state == LockState.UNLOCKED && LockPolicy.requiresAuth(backgroundedAt, nowElapsed, lockTimeout)) {
            state = LockState.LOCKED
        }
        backgroundedAt = null
    }

    fun onAuthStarted() {
        if (state == LockState.LOCKED) state = LockState.UNLOCKING
    }

    fun onAuthResult(result: AuthResult) {
        state = when (result) {
            AuthResult.SUCCESS -> LockState.UNLOCKED
            AuthResult.NO_CREDENTIAL -> LockState.NO_DEVICE_LOCK
            AuthResult.FAILED, AuthResult.CANCELLED -> LockState.LOCKED
        }
    }

    /** Used after the User sets a screen lock, so the next attempt can run. */
    fun reset() {
        state = LockState.LOCKED
        backgroundedAt = null
    }
}

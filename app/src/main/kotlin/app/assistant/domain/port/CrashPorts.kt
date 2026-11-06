package app.assistant.domain.port

/** The last crash, kept on this phone only so the User can copy it into a bug report. Nothing is ever sent. */
interface CrashReports {
    fun last(): String?

    fun clear()
}

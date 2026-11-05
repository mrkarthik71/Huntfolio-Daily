package app.assistant.domain.engine.backup

/**
 * Validates an Export_Passphrase (Requirement 60.1). A weak passphrase makes the whole export weak,
 * so the rule is a minimum length and a ban on the obviously empty/whitespace case. The export is
 * unrecoverable if forgotten, which the UI warns about separately.
 */
object ExportPassphrase {
    const val MIN_LENGTH = 10

    enum class Error { TOO_SHORT, MISMATCH }

    /** Null when valid; an [Error] otherwise. [repeat] is the second entry the UI asks for. */
    fun validate(passphrase: CharArray, repeat: CharArray): Error? = when {
        passphrase.size < MIN_LENGTH -> Error.TOO_SHORT
        !passphrase.contentEquals(repeat) -> Error.MISMATCH
        else -> null
    }
}

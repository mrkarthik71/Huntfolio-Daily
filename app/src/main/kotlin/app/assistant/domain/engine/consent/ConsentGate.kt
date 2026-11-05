package app.assistant.domain.engine.consent

/**
 * The Consent_Gate for the shared build (Requirement 66). In the `shared` flavour the start
 * sequence must show the Consent_Screen BEFORE creating the database, key or settings; a decline
 * stores nothing and shows it again next launch; acceptance records the date and the
 * Consent_Text_Version. The personal flavour never shows it. This is pure — the caller supplies
 * whether this is a shared build and the stored accepted version.
 */
object ConsentGate {
    /** The version of the consent text currently shipped; a bump re-prompts even an earlier accepter. */
    const val CURRENT_VERSION = 1

    /**
     * True when consent must be shown before anything else runs. Shared builds require an accepted
     * record at the current version; personal builds never do.
     */
    fun mustShow(isSharedBuild: Boolean, acceptedVersion: Int?): Boolean =
        isSharedBuild && acceptedVersion != CURRENT_VERSION
}

package app.assistant.buildlogic

/**
 * Pure checks used by the release verification tasks, kept free of Gradle types so they can
 * be unit tested (task 5.6).
 */
object ReleaseChecks {
    /** Never allowed in any build: the app is read-only (Requirement 2.1). */
    val ALWAYS_FORBIDDEN = setOf(
        "android.permission.SEND_SMS",
        "android.permission.CALL_PHONE",
        "android.permission.WRITE_CALENDAR",
    )

    /** Not allowed in the Sms_Free_Build (Requirement 67.2). */
    val SMS_PERMISSIONS = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_WAP_PUSH",
    )

    private val usesPermission = Regex("""<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"""")

    fun declaredPermissions(manifestXml: String): Set<String> =
        usesPermission.findAll(manifestXml).map { it.groupValues[1] }.toSet()

    /** Returns the forbidden permissions found; empty means the manifest passes. */
    fun forbiddenPermissions(manifestXml: String, smsFree: Boolean): Set<String> {
        val declared = declaredPermissions(manifestXml)
        val banned = if (smsFree) ALWAYS_FORBIDDEN + SMS_PERMISSIONS else ALWAYS_FORBIDDEN
        return declared intersect banned
    }

    /**
     * Requirement 65.4: a release needs a version code strictly greater than the last one
     * distributed. Returns null when it passes, or the reason it fails.
     */
    fun versionCodeProblem(newCode: Int, lastReleasedText: String?): String? {
        if (newCode <= 0) return "versionCode must be a positive integer, was $newCode"
        val last = lastReleasedText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lastCode = last.toIntOrNull() ?: return "release/last-version-code.txt does not hold a number"
        return if (newCode > lastCode) null else "versionCode $newCode must be greater than the last released $lastCode"
    }
}

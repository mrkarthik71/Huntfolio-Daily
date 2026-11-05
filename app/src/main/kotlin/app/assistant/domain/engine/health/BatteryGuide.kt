package app.assistant.domain.engine.health

/**
 * The Battery_Guide (Requirement 61). Pure: it maps a device manufacturer to the steps that stop
 * aggressive battery optimisation from killing the app's scheduled work. A manufacturer match is
 * case-insensitive; an unknown brand gets the Standard_Android_Steps. Manufacturer_Steps are only
 * listed for brands actually checked on a device — an unverified brand falls back rather than
 * showing steps that might be wrong. The optimisation check and the open-settings action are the
 * caller's (platform) job; this supplies the words.
 */
object BatteryGuide {
    data class Guide(val manufacturer: String, val steps: List<String>, val isStandard: Boolean)

    /** Steps for [manufacturer] (case-insensitive, trimmed); Standard_Android_Steps when not matched. */
    fun stepsFor(manufacturer: String): Guide {
        val key = manufacturer.trim().lowercase()
        val match = MANUFACTURER_STEPS.entries.firstOrNull { key.contains(it.key) }
        return if (match != null) {
            Guide(manufacturer = match.key, steps = match.value, isStandard = false)
        } else {
            Guide(manufacturer = "android", steps = STANDARD_ANDROID_STEPS, isStandard = true)
        }
    }

    /** Checked brands only (Requirement 61.3). Keys are lowercase substrings of `Build.MANUFACTURER`. */
    private val MANUFACTURER_STEPS: Map<String, List<String>> = linkedMapOf(
        "xiaomi" to listOf(
            "Open Settings, then Apps, then Manage apps, then this app.",
            "Set Battery saver to No restrictions.",
            "Turn on Autostart.",
        ),
        "samsung" to listOf(
            "Open Settings, then Battery, then Background usage limits.",
            "Remove this app from Sleeping and Deep sleeping apps.",
            "Set this app to Unrestricted under Battery usage.",
        ),
        "oneplus" to listOf(
            "Open Settings, then Battery, then Battery optimisation.",
            "Set this app to Don't optimise.",
            "Turn off Advanced optimisation for this app.",
        ),
        "oppo" to listOf(
            "Open Settings, then Battery, then this app.",
            "Allow background activity and turn off Sleep standby optimisation.",
        ),
    )

    /** The generic path that exists on every Android (Requirement 61.2). */
    private val STANDARD_ANDROID_STEPS: List<String> = listOf(
        "Open Settings, then Apps, then this app.",
        "Open Battery, then set it to Unrestricted (or Don't optimise).",
    )
}

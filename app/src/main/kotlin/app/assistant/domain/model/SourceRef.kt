package app.assistant.domain.model

/**
 * The Data_Sources listed on the Settings_Screen, plus MANUAL for facts the User typed in.
 * The prefix is the first part of every Source_Ref from that source.
 */
enum class DataSource(val prefix: String) {
    SMS("sms"),
    APP_NOTIFICATIONS("ntf"),
    CALENDAR("cal"),
    MAILBOX("mail"),
    AI_PROVIDER("ai"),
    HUNTFOLIO("hf"),
    MANUAL("man"),
    ;

    companion object {
        private val byPrefix = entries.associateBy { it.prefix }

        fun fromPrefix(prefix: String): DataSource = byPrefix[prefix] ?: throw IllegalArgumentException("Unknown Source_Ref prefix")

        fun fromPrefixOrNull(prefix: String): DataSource? = byPrefix[prefix]
    }
}

/**
 * Pointer to where a stored fact came from, in the form `<prefix>:v1:<hmac>` (ADR 0006).
 * It contains no message text. It is the idempotency key for ingest and the key for tombstones.
 */
@JvmInline
value class SourceRef(val value: String) {
    init {
        require(DataSource.fromPrefixOrNull(value.substringBefore(':')) != null) {
            "Source_Ref has an unknown prefix"
        }
    }

    val source: DataSource get() = DataSource.fromPrefix(value.substringBefore(':'))

    /** Never print the value in logs or exceptions; it is linkable to a message on the device. */
    override fun toString(): String = "SourceRef(${source.prefix}:…)"
}

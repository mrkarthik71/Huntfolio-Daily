package app.assistant.domain.model

/**
 * One Bank_Sender_List entry (design: SenderEntry). Indian DLT-style headers such as `AX-HDFCBK`
 * or `VM-HDFCBK-S` are covered by storing the core header (`HDFCBK`) with an optional operator
 * [prefixPattern] and [suffixPattern] instead of every exact sender string.
 *
 * A pattern is literal text in which `?` stands for exactly one letter or digit, so `??-` is
 * "two characters then a hyphen" and `-?` is "a hyphen then one character". Nothing else is
 * special, which keeps matching linear and makes a pattern safe to take from the User.
 */
data class SenderEntry(val coreHeader: String, val prefixPattern: String? = null, val suffixPattern: String? = null) {
    init {
        require(coreHeader.isNotBlank() && coreHeader == coreHeader.trim()) { "Sender core header must be non-blank and trimmed" }
        require('?' !in coreHeader) { "Sender core header cannot contain '?'" }
    }
}

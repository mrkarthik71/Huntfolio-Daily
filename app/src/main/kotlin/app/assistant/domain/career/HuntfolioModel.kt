package app.assistant.domain.career

import java.time.LocalDate

/**
 * The Huntfolio import format (Requirement 59, design: Career_Importer). A proposed JSON shape with
 * a format version and a stable per-record key, to be agreed with Huntfolio. The parser is pure and
 * streaming-friendly; the connector/file layer supplies the already-read text and enforces the 5 MB
 * limit before calling in.
 */

const val HUNTFOLIO_FORMAT_VERSION = 1

/** The record keys the importer understands. A record with an unknown `type` is skipped, not an error. */
enum class HuntfolioRecordType { FOLLOW_UP, SKILL }

/** One imported record. [key] is the stable id used for idempotent import (Requirement 59.3, 59.5). */
sealed interface HuntfolioRecord {
    val key: String

    /** A follow-up becomes a Task (Requirement 59.4). */
    data class FollowUp(override val key: String, val title: String, val dueDate: LocalDate?) : HuntfolioRecord

    /** A skill the user may turn into a Goal (Requirement 59.4); target/period/unit are chosen in the UI. */
    data class Skill(override val key: String, val name: String) : HuntfolioRecord
}

/** The whole parsed import: the format version and the records, in file order. */
data class HuntfolioImport(val formatVersion: Int, val records: List<HuntfolioRecord>)

/** Why a Huntfolio file could not be parsed. [FirstInvalidRecord] names the first bad record (Requirement 59.2). */
sealed interface HuntfolioError {
    data object TooLarge : HuntfolioError
    data object NotJson : HuntfolioError
    data object MissingVersion : HuntfolioError
    data class UnsupportedVersion(val found: Int) : HuntfolioError
    data class FirstInvalidRecord(val index: Int, val reason: String) : HuntfolioError
}

const val HUNTFOLIO_MAX_BYTES = 5 * 1024 * 1024

package app.assistant.parse.huntfolio

import app.assistant.domain.career.HuntfolioRecordType
import app.assistant.domain.career.HuntfolioRecord
import app.assistant.domain.career.HuntfolioImport
import app.assistant.domain.career.HuntfolioError
import app.assistant.domain.career.HUNTFOLIO_FORMAT_VERSION
import app.assistant.domain.error.Outcome
import java.time.LocalDate

/**
 * Turns already-decoded Huntfolio rows into typed [HuntfolioRecord]s, naming the first invalid one
 * (Requirement 59.2). It is pure — JSON decoding lives in the data layer, which hands this a version
 * and a list of string maps. [HuntfolioFormatter] does the reverse for the round-trip property test.
 */
object HuntfolioParser {

    /** A decoded row: the raw string fields the data layer read from one JSON record. */
    data class Row(val fields: Map<String, String?>)

    fun parse(formatVersion: Int?, rows: List<Row>): Outcome<HuntfolioImport, HuntfolioError> {
        if (formatVersion == null) return Outcome.Err(HuntfolioError.MissingVersion)
        if (formatVersion != HUNTFOLIO_FORMAT_VERSION) return Outcome.Err(HuntfolioError.UnsupportedVersion(formatVersion))
        val records = ArrayList<HuntfolioRecord>(rows.size)
        rows.forEachIndexed { index, row ->
            when (val r = record(row)) {
                is Outcome.Ok -> r.value?.let(records::add) // null = unknown type, skipped (not an error)
                is Outcome.Err -> return Outcome.Err(HuntfolioError.FirstInvalidRecord(index, r.error))
            }
        }
        return Outcome.Ok(HuntfolioImport(formatVersion, records))
    }

    private fun record(row: Row): Outcome<HuntfolioRecord?, String> {
        val key = row.fields["key"]?.trim().orEmpty()
        if (key.isBlank()) return Outcome.Err("missing key")
        return when (row.fields["type"]?.trim()?.uppercase()) {
            HuntfolioRecordType.FOLLOW_UP.name -> {
                val title = row.fields["title"]?.trim().orEmpty()
                if (title.isBlank()) return Outcome.Err("follow-up has no title")
                val due = row.fields["due_date"]?.trim()?.takeIf { it.isNotBlank() }
                val date = due?.let { runCatching { LocalDate.parse(it) }.getOrElse { return Outcome.Err("invalid due_date '$it'") } }
                Outcome.Ok(HuntfolioRecord.FollowUp(key, title, date))
            }
            HuntfolioRecordType.SKILL.name -> {
                val name = row.fields["name"]?.trim().orEmpty()
                if (name.isBlank()) return Outcome.Err("skill has no name")
                Outcome.Ok(HuntfolioRecord.Skill(key, name))
            }
            else -> Outcome.Ok(null) // unknown type: skipped
        }
    }
}

/** The reverse mapping, for the Property 77 round trip. Produces the canonical row for each record. */
object HuntfolioFormatter {
    fun format(import: HuntfolioImport): List<HuntfolioParser.Row> = import.records.map { record ->
        when (record) {
            is HuntfolioRecord.FollowUp -> HuntfolioParser.Row(
                linkedMapOf("type" to HuntfolioRecordType.FOLLOW_UP.name, "key" to record.key, "title" to record.title, "due_date" to record.dueDate?.toString()),
            )
            is HuntfolioRecord.Skill -> HuntfolioParser.Row(
                linkedMapOf("type" to HuntfolioRecordType.SKILL.name, "key" to record.key, "name" to record.name),
            )
        }
    }
}

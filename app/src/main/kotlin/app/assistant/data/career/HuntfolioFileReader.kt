package app.assistant.data.career

import app.assistant.domain.error.Outcome
import app.assistant.domain.career.HuntfolioError
import app.assistant.domain.career.HuntfolioImport
import app.assistant.parse.huntfolio.HuntfolioParser
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a Huntfolio JSON file into rows for the pure [HuntfolioParser] (Requirement 59.2, 59.6). It
 * enforces the 5 MB limit before parsing, converts each record object to a string-map row, and lets
 * the parser name the first invalid record. JSON decoding stays in the data layer (the parse layer
 * forbids a JSON library), matching the money parser's split.
 */
@Singleton
class HuntfolioFileReader @Inject constructor() : app.assistant.domain.port.CareerFileReader {

    override fun read(stream: InputStream, sizeBytes: Long): Outcome<HuntfolioImport, HuntfolioError> {
        if (sizeBytes > app.assistant.domain.career.HUNTFOLIO_MAX_BYTES) return Outcome.Err(HuntfolioError.TooLarge)
        val root = try {
            JsonParser.parseReader(stream.reader()).asJsonObject
        } catch (_: JsonSyntaxException) {
            return Outcome.Err(HuntfolioError.NotJson)
        } catch (_: IllegalStateException) {
            return Outcome.Err(HuntfolioError.NotJson)
        }
        val version = root.get("format_version")?.takeIf { it.isJsonPrimitive }?.asInt
        val recordsEl = root.get("records")
        val rows = if (recordsEl != null && recordsEl.isJsonArray) {
            recordsEl.asJsonArray.map { el ->
                val obj = el.asJsonObject
                HuntfolioParser.Row(obj.keySet().associateWith { k -> obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString })
            }
        } else {
            emptyList()
        }
        return HuntfolioParser.parse(version, rows)
    }
}

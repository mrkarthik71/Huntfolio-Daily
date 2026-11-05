package app.assistant.domain.engine.mail

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Builds the snippet shown for an email (Requirement 52.2, 52.5, 52.6). It prefers a plain-text
 * part; failing that it strips an HTML part to visible text, dropping tags, scripts and styles.
 * Bytes are decoded with the declared charset, falling back to a replacement marker for undecodable
 * input rather than throwing. The result is trimmed to [maxChars]. The snippet is the ONLY body text
 * the app keeps; the raw body is never stored.
 */
object SnippetExtractor {
    const val DEFAULT_MAX_CHARS = 300

    /** A decoded email part: its bytes, declared charset name (nullable) and whether it is HTML. */
    data class Part(val bytes: ByteArray, val charsetName: String?, val isHtml: Boolean)

    /**
     * Extracts a snippet from the available [parts]. A plain-text part wins; otherwise the first HTML
     * part is stripped. Returns an empty string when nothing readable is present.
     */
    fun extract(parts: List<Part>, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val plain = parts.firstOrNull { !it.isHtml }
        val text = when {
            plain != null -> decode(plain)
            else -> parts.firstOrNull { it.isHtml }?.let { stripHtml(decode(it)) } ?: ""
        }
        return normalise(text).take(maxChars)
    }

    private fun decode(part: Part): String {
        val charset = runCatching { part.charsetName?.let(Charset::forName) }.getOrNull() ?: StandardCharsets.UTF_8
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(part.bytes)).toString()
    }

    /** Removes scripts, styles and tags, decodes a few common entities, leaving visible text only. */
    private fun stripHtml(html: String): String {
        var out = SCRIPT_STYLE.replace(html, " ")
        out = TAG.replace(out, " ")
        for ((entity, ch) in ENTITIES) out = out.replace(entity, ch)
        return out
    }

    private fun normalise(text: String): String = WHITESPACE.replace(text, " ").trim()

    private val SCRIPT_STYLE = Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("""<[^>]{0,4000}>""")
    private val WHITESPACE = Regex("""\s+""")
    private val ENTITIES = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'")
}

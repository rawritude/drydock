package io.github.rawritude.drydock.util

import java.net.URLDecoder

/** RFC 6266 Content-Disposition filename extraction, including RFC 5987 `filename*`. */
object ContentDisposition {

    private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("""filename\s*=\s*("(?:[^"\\]|\\.)*"|[^;]+)""", RegexOption.IGNORE_CASE)
    private val ESCAPED = Regex("""\\(.)""")

    fun parse(header: String?): String? {
        if (header.isNullOrBlank()) return null
        extractExtended(header)?.let { return it }
        val match = FILENAME.find(header) ?: return null
        var value = match.groupValues[1].trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1)
        }
        value = ESCAPED.replace(value) { it.groupValues[1] }
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun extractExtended(header: String): String? {
        val match = FILENAME_STAR.find(header) ?: return null
        var value = match.groupValues[1].trim().trim('"')
        val firstQuote = value.indexOf('\'')
        if (firstQuote >= 0) {
            val secondQuote = value.indexOf('\'', firstQuote + 1)
            if (secondQuote > firstQuote) value = value.substring(secondQuote + 1)
        }
        val decoded = runCatching {
            // '+' is a literal in RFC 5987, but URLDecoder treats it as a space.
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrElse { return value }
        return decoded.trim().takeIf { it.isNotEmpty() }
    }
}

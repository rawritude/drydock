package io.github.rawritude.drydock.engine

/** Parsed `Content-Range: bytes start-end/total` header. */
data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?)

object ContentRangeParser {

    private val RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun parse(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val match = RANGE.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = if (match.groupValues[3] == "*") null else match.groupValues[3].toLongOrNull() ?: return null
        if (start > end) return null
        if (total != null && end >= total) return null
        return ContentRange(start, end, total)
    }

    /**
     * A 206 response to a single-range request must start exactly where we
     * asked and must not extend past the requested range. Returns false when
     * the response cannot be trusted for positioned writes.
     */
    fun coversRequest(cr: ContentRange, requestedStart: Long, requestedEndExclusive: Long, expectedTotal: Long?): Boolean {
        if (cr.start != requestedStart) return false
        if (cr.endInclusive >= requestedEndExclusive) return false
        if (expectedTotal != null && cr.total != null && cr.total != expectedTotal) return false
        return true
    }
}

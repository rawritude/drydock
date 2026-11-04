package io.github.rawritude.drydock.engine

/**
 * Splits a file into parallel segments.
 *
 * Follows aria2's `--split` / `--min-split-size` behavior: a file is never
 * split smaller than [minSplitSize] per connection, and files smaller than
 * `2 * minSplitSize` are downloaded with a single connection.
 */
object SegmentPlanner {

    const val DEFAULT_MIN_SPLIT_SIZE = 8L * 1024 * 1024

    fun plan(
        totalBytes: Long?,
        acceptRanges: Boolean,
        maxConnections: Int,
        minSplitSize: Long = DEFAULT_MIN_SPLIT_SIZE,
    ): List<SegmentState> {
        require(maxConnections >= 1)
        require(minSplitSize > 0)
        if (totalBytes == null || totalBytes <= 0 || !acceptRanges) {
            return listOf(SegmentState(start = 0, endExclusive = totalBytes ?: -1L))
        }
        val splittable = (totalBytes / minSplitSize).toInt().coerceAtLeast(1)
        val count = minOf(maxConnections, splittable)
        val base = totalBytes / count
        val remainder = totalBytes % count
        val segments = ArrayList<SegmentState>(count)
        var offset = 0L
        for (i in 0 until count) {
            val length = base + if (i < remainder) 1 else 0
            segments += SegmentState(start = offset, endExclusive = offset + length)
            offset += length
        }
        return segments
    }
}

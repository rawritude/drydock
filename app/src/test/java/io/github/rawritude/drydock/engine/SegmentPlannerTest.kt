package io.github.rawritude.drydock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {

    private val mb = 1L shl 20

    @Test
    fun `unknown or empty totals produce a single segment`() {
        val unknown = SegmentPlanner.plan(totalBytes = null, acceptRanges = true, maxConnections = 4)
        assertEquals(1, unknown.size)
        assertTrue(unknown[0].isUnknownLength)

        val empty = SegmentPlanner.plan(totalBytes = 0, acceptRanges = true, maxConnections = 4)
        assertEquals(1, empty.size)
    }

    @Test
    fun `servers without range support produce one sequential segment`() {
        val segments = SegmentPlanner.plan(totalBytes = 256 * mb, acceptRanges = false, maxConnections = 4)
        assertEquals(1, segments.size)
        assertFalse(segments[0].isUnknownLength)
        assertEquals(256 * mb, segments[0].endExclusive)
    }

    @Test
    fun `small files are never split`() {
        val segments = SegmentPlanner.plan(
            totalBytes = 10 * mb,
            acceptRanges = true,
            maxConnections = 4,
            minSplitSize = 8 * mb,
        )
        assertEquals(1, segments.size)
    }

    @Test
    fun `large files split into requested connection count`() {
        val total = 100 * mb
        val segments = SegmentPlanner.plan(total, acceptRanges = true, maxConnections = 4, minSplitSize = 8 * mb)
        assertEquals(4, segments.size)
        assertTiles(segments, total)
    }

    @Test
    fun `split count is capped by min split size`() {
        val total = 20 * mb
        val segments = SegmentPlanner.plan(total, acceptRanges = true, maxConnections = 8, minSplitSize = 8 * mb)
        assertEquals(2, segments.size)
        assertTiles(segments, total)
    }

    @Test
    fun `segments tile the file exactly even with remainders`() {
        val total = 100 * mb + 12345
        val segments = SegmentPlanner.plan(total, acceptRanges = true, maxConnections = 4, minSplitSize = 1 * mb)
        assertTiles(segments, total)
    }

    @Test
    fun `single connection keeps exact boundaries`() {
        val total = 5 * mb
        val segments = SegmentPlanner.plan(total, acceptRanges = true, maxConnections = 1, minSplitSize = 1 * mb)
        assertEquals(1, segments.size)
        assertEquals(0, segments[0].start)
        assertEquals(total, segments[0].endExclusive)
    }

    private fun assertTiles(segments: List<SegmentState>, total: Long) {
        var expected = 0L
        segments.forEach { segment ->
            assertEquals(expected, segment.start)
            assertTrue(segment.endExclusive > segment.start)
            expected = segment.endExclusive
        }
        assertEquals(total, expected)
    }
}

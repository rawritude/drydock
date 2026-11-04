package io.github.rawritude.drydock.engine

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {

    private val mb = 1L shl 20

    private fun info(
        total: Long? = 100 * mb,
        acceptRanges: Boolean = true,
        etag: String? = "\"v1\"",
        lastModified: String? = null,
    ) = RemoteFileInfo(
        finalUrl = "https://example.com/file".toHttpUrl(),
        contentLength = total,
        acceptRanges = acceptRanges,
        etag = etag,
        lastModified = lastModified,
        contentDispositionFileName = null,
        mimeType = null,
    )

    private fun control(
        total: Long = 100 * mb,
        etag: String? = "\"v1\"",
        lastModified: String? = null,
        segments: List<SegmentState> = SegmentPlanner.plan(total, true, 4, minSplitSize = 8 * mb)
            .mapIndexed { index, segment -> if (index == 0) segment.copy(done = 1 * mb) else segment },
    ) = ControlFile(
        sourceUrl = "https://example.com/file",
        finalUrl = "https://example.com/file",
        totalBytes = total,
        etag = etag,
        lastModified = lastModified,
        acceptRanges = true,
        segments = segments,
    )

    @Test
    fun `fresh when no previous control exists`() {
        assertTrue(ResumePolicy.decide(null, info()) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `resumes when everything matches`() {
        val decision = ResumePolicy.decide(control(), info())
        assertTrue(decision is ResumePolicy.Decision.Resume)
    }

    @Test
    fun `fresh when etag changed`() {
        assertTrue(ResumePolicy.decide(control(etag = "\"v1\""), info(etag = "\"v2\"")) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `fresh when size changed`() {
        assertTrue(ResumePolicy.decide(control(total = 100 * mb), info(total = 101 * mb)) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `fresh when server no longer supports ranges`() {
        assertTrue(ResumePolicy.decide(control(), info(acceptRanges = false)) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `fresh when last-modified changed and no etags`() {
        val old = control(etag = null, lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        val new = info(etag = null, lastModified = "Thu, 22 Oct 2015 07:28:00 GMT")
        assertTrue(ResumePolicy.decide(old, new) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `resumes without validators when total matches`() {
        assertTrue(ResumePolicy.decide(control(etag = null), info(etag = null)) is ResumePolicy.Decision.Resume)
    }

    @Test
    fun `fresh when segments have gaps`() {
        val broken = control(
            segments = listOf(
                SegmentState(0, 10 * mb),
                SegmentState(11 * mb, 100 * mb),
            ),
        )
        assertTrue(ResumePolicy.decide(broken, info()) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `fresh when segment progress is out of bounds`() {
        val broken = control(
            segments = SegmentPlanner.plan(100 * mb, true, 4, minSplitSize = 8 * mb)
                .mapIndexed { index, segment ->
                    if (index == 0) segment.copy(done = segment.length + 1) else segment
                },
        )
        assertTrue(ResumePolicy.decide(broken, info()) is ResumePolicy.Decision.Fresh)
    }

    @Test
    fun `resume refreshes final url and validators`() {
        val decision = ResumePolicy.decide(control(etag = "\"v1\""), info(etag = "\"v1\""))
        decision as ResumePolicy.Decision.Resume
        assertEquals("https://example.com/file", decision.control.finalUrl)
        assertEquals("\"v1\"", decision.control.etag)
    }
}

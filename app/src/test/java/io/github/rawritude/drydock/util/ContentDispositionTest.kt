package io.github.rawritude.drydock.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentDispositionTest {

    @Test
    fun `parses plain filename`() {
        assertEquals("report.pdf", ContentDisposition.parse("attachment; filename=report.pdf"))
    }

    @Test
    fun `parses quoted filename with spaces`() {
        assertEquals("my report.pdf", ContentDisposition.parse("attachment; filename=\"my report.pdf\""))
    }

    @Test
    fun `parses escaped quotes`() {
        assertEquals("we\"ird.txt", ContentDisposition.parse("attachment; filename=\"we\\\"ird.txt\""))
    }

    @Test
    fun `prefers rfc5987 filename star`() {
        val header = "attachment; filename=fallback.txt; filename*=UTF-8''caf%C3%A9%20menu.pdf"
        assertEquals("café menu.pdf", ContentDisposition.parse(header))
    }

    @Test
    fun `keeps plus signs literal in filename star`() {
        assertEquals("a+b.txt", ContentDisposition.parse("attachment; filename*=UTF-8'en'a+b.txt"))
    }

    @Test
    fun `returns null for missing or blank headers`() {
        assertNull(ContentDisposition.parse(null))
        assertNull(ContentDisposition.parse("inline"))
        assertNull(ContentDisposition.parse("attachment"))
    }
}

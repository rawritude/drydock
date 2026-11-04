package io.github.rawritude.drydock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRangeTest {

    @Test
    fun `parses valid header`() {
        val range = ContentRangeParser.parse("bytes 0-99/1000")
        assertEquals(ContentRange(0, 99, 1000), range)
    }

    @Test
    fun `parses unknown total`() {
        val range = ContentRangeParser.parse("bytes 10-19/*")
        assertEquals(ContentRange(10, 19, null), range)
    }

    @Test
    fun `rejects malformed headers`() {
        assertNull(ContentRangeParser.parse(null))
        assertNull(ContentRangeParser.parse("items 0-99/1000"))
        assertNull(ContentRangeParser.parse("bytes 99-0/1000"))
        assertNull(ContentRangeParser.parse("bytes 0-1000/1000"))
        assertNull(ContentRangeParser.parse("bytes 0-99"))
    }

    @Test
    fun `coversRequest accepts exact ranges`() {
        assertTrue(
            ContentRangeParser.coversRequest(ContentRange(100, 199, 1000), 100, 200, 1000),
        )
    }

    @Test
    fun `coversRequest rejects shifted or oversized ranges`() {
        assertFalse(ContentRangeParser.coversRequest(ContentRange(101, 199, 1000), 100, 200, 1000))
        assertFalse(ContentRangeParser.coversRequest(ContentRange(100, 200, 1000), 100, 200, 1000))
        assertFalse(ContentRangeParser.coversRequest(ContentRange(100, 199, 999), 100, 200, 1000))
    }

    @Test
    fun `coversRequest tolerates missing total`() {
        assertTrue(ContentRangeParser.coversRequest(ContentRange(100, 199, null), 100, 200, 1000))
    }
}

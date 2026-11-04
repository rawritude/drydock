package io.github.rawritude.drydock.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPatternsTest {

    @Test
    fun `matches extension glob`() {
        val patterns = LinkPatterns.parse("*.zip")
        assertTrue(LinkPatterns.matches("https://example.com/files/archive.ZIP", patterns))
        assertFalse(LinkPatterns.matches("https://example.com/files/archive.rar", patterns))
    }

    @Test
    fun `matches domain scoped patterns`() {
        val patterns = LinkPatterns.parse("https://cdn.example.com/*")
        assertTrue(LinkPatterns.matches("https://cdn.example.com/a/b.bin", patterns))
        assertFalse(LinkPatterns.matches("https://example.com/a.bin", patterns))
    }

    @Test
    fun `question mark matches single character`() {
        val patterns = LinkPatterns.parse("https://x.test/v?/file.bin")
        assertTrue(LinkPatterns.matches("https://x.test/v1/file.bin", patterns))
        assertFalse(LinkPatterns.matches("https://x.test/v12/file.bin", patterns))
    }

    @Test
    fun `empty list matches every url`() {
        val patterns = LinkPatterns.parse("")
        assertTrue(LinkPatterns.matches("https://anything.example/file", patterns))
    }

    @Test
    fun `ignores comments and blank lines`() {
        val patterns = LinkPatterns.parse("# comment\n\n*.iso\n")
        assertTrue(LinkPatterns.matches("https://example.com/disc.iso", patterns))
        assertFalse(LinkPatterns.matches("https://example.com/disc.zip", patterns))
    }

    @Test
    fun `regex special characters are literal`() {
        val patterns = LinkPatterns.parse("https://example.com/a+b(c).bin")
        assertTrue(LinkPatterns.matches("https://example.com/a+b(c).bin", patterns))
    }
}

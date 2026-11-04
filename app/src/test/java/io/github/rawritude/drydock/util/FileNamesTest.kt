package io.github.rawritude.drydock.util

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {

    @Test
    fun `strips path traversal`() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.zip", FileNames.sanitize("..\\..\\evil.zip"))
    }

    @Test
    fun `strips control characters`() {
        assertEquals("clean.txt", FileNames.sanitize("cl\u0000ea\u001Fn.txt"))
    }

    @Test
    fun `replaces illegal characters`() {
        assertEquals("a_c_d_e_f_g_h_i", FileNames.sanitize("a:c*d?e\"f<g>h|i"))
    }

    @Test
    fun `trims dots and whitespace`() {
        assertEquals("file.txt", FileNames.sanitize("  file.txt . . "))
    }

    @Test
    fun `falls back for empty or dot names`() {
        assertEquals("download", FileNames.sanitize(null))
        assertEquals("download", FileNames.sanitize("   "))
        assertEquals("download", FileNames.sanitize("."))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("fallback", FileNames.sanitize("..", fallback = "fallback"))
    }

    @Test
    fun `truncates long names preserving extension`() {
        val name = "x".repeat(400) + ".tar.gz"
        val sanitized = FileNames.sanitize(name)
        assertEquals(FileNames.MAX_LENGTH, sanitized.length)
        assertEquals(".tar.gz", sanitized.takeLast(7))
    }

    @Test
    fun `guesses name from url path`() {
        assertEquals("file.zip", FileNames.guessFromUrl("https://example.com/a/file.zip?token=x".toHttpUrl()))
        assertEquals("file.zip", FileNames.guessFromUrl("https://example.com/file.zip".toHttpUrl()))
        assertEquals(null, FileNames.guessFromUrl("https://example.com/".toHttpUrl()))
    }

    @Test
    fun `withSuffix mirrors aria auto renaming`() {
        assertEquals("file (1).zip", FileNames.withSuffix("file.zip", 1))
        assertEquals("file (12).tar.gz", FileNames.withSuffix("file.tar.gz", 12))
        assertEquals("README (3)", FileNames.withSuffix("README", 3))
        assertEquals("file.zip", FileNames.withSuffix("file.zip", 0))
    }
}

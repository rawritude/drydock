package io.github.rawritude.drydock.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {

    @Test
    fun `accepts https`() {
        val result = UrlSafety.validate("https://example.com/file.zip", allowInsecureHttp = false)
        assertTrue(result is UrlSafety.Result.Ok)
    }

    @Test
    fun `rejects http unless explicitly allowed`() {
        val rejected = UrlSafety.validate("http://example.com/file.zip", allowInsecureHttp = false)
        assertTrue(rejected is UrlSafety.Result.Rejected)

        val accepted = UrlSafety.validate("http://example.com/file.zip", allowInsecureHttp = true)
        assertTrue(accepted is UrlSafety.Result.Ok)
    }

    @Test
    fun `rejects non-http schemes`() {
        assertTrue(UrlSafety.validate("ftp://example.com/file", true) is UrlSafety.Result.Rejected)
        assertTrue(UrlSafety.validate("file:///etc/passwd", true) is UrlSafety.Result.Rejected)
        assertTrue(UrlSafety.validate("javascript:alert(1)", true) is UrlSafety.Result.Rejected)
        assertTrue(UrlSafety.validate("content://media/external/images/1", true) is UrlSafety.Result.Rejected)
    }

    @Test
    fun `rejects embedded credentials`() {
        assertTrue(
            UrlSafety.validate("https://user:pass@example.com/file", true) is UrlSafety.Result.Rejected,
        )
    }

    @Test
    fun `rejects empty and oversized input`() {
        assertTrue(UrlSafety.validate("   ", true) is UrlSafety.Result.Rejected)
        val long = "https://example.com/" + "a".repeat(UrlSafety.MAX_URL_LENGTH)
        assertTrue(UrlSafety.validate(long, true) is UrlSafety.Result.Rejected)
    }

    @Test
    fun `extracts first url from shared text`() {
        assertEquals(
            "https://example.com/file.zip",
            UrlSafety.firstUrlIn("Check this out https://example.com/file.zip it is great"),
        )
        assertEquals(
            "https://example.com/file.zip",
            UrlSafety.firstUrlIn("https://example.com/file.zip."),
        )
        assertEquals(null, UrlSafety.firstUrlIn("no links here"))
        assertEquals(null, UrlSafety.firstUrlIn(null))
    }
}

package io.github.rawritude.drydock.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Central URL policy. HTTPS is the default; plain HTTP requires an explicit
 * per-download opt-in. Links with embedded credentials are always rejected —
 * the app has no UI to review what would be sent where.
 */
object UrlSafety {

    const val MAX_URL_LENGTH = 8_192

    sealed interface Result {
        data class Ok(val url: HttpUrl) : Result
        data class Rejected(val reason: String) : Result
    }

    fun validate(raw: String, allowInsecureHttp: Boolean): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Rejected("Enter a URL")
        if (trimmed.length > MAX_URL_LENGTH) return Result.Rejected("URL is too long")
        val url = trimmed.toHttpUrlOrNull()
            ?: return Result.Rejected("Only HTTP and HTTPS links are supported")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.Rejected("Links with embedded credentials are not supported")
        }
        if (url.scheme == "http" && !allowInsecureHttp) {
            return Result.Rejected("Insecure HTTP is off; enable it explicitly to continue")
        }
        return Result.Ok(url)
    }

    /** Finds the first http(s) URL inside shared text. */
    fun firstUrlIn(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""https?://\S+""").find(text) ?: return null
        return match.value.trimEnd('.', ',', ';', ')', ']', '}')
    }
}

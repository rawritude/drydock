package io.github.rawritude.drydock.util

import okhttp3.HttpUrl

/**
 * Sanitizes file names so they can never escape the downloads directory or
 * confuse the media store: no path separators, no traversal, no control
 * characters, no reserved names.
 */
object FileNames {

    const val MAX_LENGTH = 180
    private const val DEFAULT_NAME = "download"

    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|]")
    private val WHITESPACE = Regex("\\s+")

    fun sanitize(raw: String?, fallback: String = DEFAULT_NAME): String {
        if (raw.isNullOrBlank()) return fallback
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = CONTROL_CHARS.replace(name, "")
        name = ILLEGAL_CHARS.replace(name, "_")
        name = WHITESPACE.replace(name, " ").trim().trim('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") return fallback
        return truncatePreservingExtension(name)
    }

    fun guessFromUrl(url: HttpUrl): String? {
        val candidate = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        return sanitize(candidate)
    }

    /** Mirrors aria2's auto-renaming: `file.zip` -> `file (1).zip`, `file.tar.gz` -> `file (1).tar.gz`. */
    fun withSuffix(name: String, index: Int): String {
        if (index <= 0) return name
        val extensionStart = extensionStart(name)
        return if (extensionStart >= 0) {
            name.substring(0, extensionStart) + " ($index)" + name.substring(extensionStart)
        } else {
            "$name ($index)"
        }
    }

    private fun truncatePreservingExtension(name: String): String {
        if (name.length <= MAX_LENGTH) return name
        val extensionStart = extensionStart(name)
        val extension = if (extensionStart >= 0) name.substring(extensionStart) else ""
        if (extension.isEmpty() || extension.length > MAX_EXTENSION_LENGTH) {
            return name.take(MAX_LENGTH)
        }
        return name.take(MAX_LENGTH - extension.length) + extension
    }

    /** Index where the extension begins, treating `.tar.*` archives as one extension. */
    private fun extensionStart(name: String): Int {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0) return -1
        val before = name.substring(0, lastDot)
        if (before.endsWith(".tar", ignoreCase = true) && before.length > 4) {
            return before.length - 4
        }
        return lastDot
    }

    private const val MAX_EXTENSION_LENGTH = 16
}

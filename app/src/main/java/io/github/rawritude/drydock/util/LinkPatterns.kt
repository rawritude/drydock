package io.github.rawritude.drydock.util

/**
 * Glob-style URL patterns for the link-capture feature.
 *
 * One pattern per line; `*` matches any run of characters, `?` matches one.
 * Matching is case-insensitive and anchored to the whole URL. Blank lines and
 * lines starting with `#` are ignored. An empty pattern list matches nothing
 * when used by the capture filter; callers decide what "no patterns" means.
 */
object LinkPatterns {

    fun parse(text: String): List<Regex> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { runCatching { globToRegex(it) }.getOrNull() }
            .toList()

    fun matches(url: String, patterns: List<Regex>): Boolean =
        patterns.isEmpty() || patterns.any { it.matches(url) }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

package io.github.rawritude.drydock.util

import java.util.Locale

/** Human-readable byte/speed/ETA formatting with no Android dependencies. */
object Formatting {

    private const val KB = 1_024.0
    private const val MB = KB * 1_024
    private const val GB = MB * 1_024

    fun bytes(value: Long): String = when {
        value < 0 -> "?"
        value < 1_024 -> "$value B"
        value < 1_048_576 -> String.format(Locale.US, "%.1f KB", value / KB)
        value < 1_073_741_824 -> String.format(Locale.US, "%.1f MB", value / MB)
        else -> String.format(Locale.US, "%.2f GB", value / GB)
    }

    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    fun eta(remainingBytes: Long, bytesPerSecond: Long): String? {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return null
        val seconds = remainingBytes / bytesPerSecond
        return when {
            seconds < 60 -> "${seconds}s left"
            seconds < 3_600 -> "${seconds / 60}m left"
            else -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m left"
        }
    }

    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((done * 100) / total).toInt().coerceIn(0, 100)
    }
}

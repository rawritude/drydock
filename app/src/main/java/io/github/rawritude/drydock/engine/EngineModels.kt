package io.github.rawritude.drydock.engine

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

/**
 * One contiguous byte range of a remote file.
 *
 * [endExclusive] is exclusive; a negative value means the total length is
 * unknown and the range is streamed until EOF (no Range header is sent).
 * [done] counts bytes already written for this segment, measured from [start].
 */
@Serializable
data class SegmentState(
    val start: Long,
    val endExclusive: Long,
    @Volatile var done: Long = 0L,
) {
    val isUnknownLength: Boolean get() = endExclusive < 0

    val length: Long get() = if (isUnknownLength) -1L else endExclusive - start

    val isComplete: Boolean get() = !isUnknownLength && done >= length
}

/**
 * Sidecar metadata persisted while a download is in flight so it can be
 * resumed safely later. Mirrors the role of aria2's `.aria2` control file.
 */
@Serializable
data class ControlFile(
    val version: Int = CURRENT_VERSION,
    val sourceUrl: String,
    val finalUrl: String,
    val totalBytes: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean = false,
    val segments: List<SegmentState> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Result of probing a remote URL. */
data class RemoteFileInfo(
    val finalUrl: HttpUrl,
    val contentLength: Long?,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val contentDispositionFileName: String?,
    val mimeType: String?,
)

sealed class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Non-success HTTP status. [retryAfterSeconds] is from the Retry-After header, if any. */
    class Http(
        val code: Int,
        message: String,
        val retryAfterSeconds: Long? = null,
    ) : EngineException(message)

    /** The server violated HTTP semantics in a way we refuse to write to disk. */
    class Protocol(message: String) : EngineException(message)

    /** Network level failure after retries were exhausted. */
    class Network(message: String, cause: Throwable?) : EngineException(message, cause)

    /**
     * A validator (ETag / Last-Modified) changed while resuming, or the server
     * started ignoring ranges. Safe recovery is to restart from scratch.
     */
    class ValidatorChanged(message: String) : EngineException(message)

    class ChecksumMismatch(val expected: String, val actual: String) :
        EngineException("Checksum mismatch (expected $expected, got $actual)")
}

internal val EngineException.Http.isRetryable: Boolean
    get() = code == 408 || code == 425 || code == 429 || code in 500..599

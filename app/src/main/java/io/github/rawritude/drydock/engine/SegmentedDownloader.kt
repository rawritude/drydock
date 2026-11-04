package io.github.rawritude.drydock.engine

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Segmented, resumable HTTP(S) download engine.
 *
 * Design notes:
 * - All segments write into one pre-sized part file using positioned
 *   [FileChannel] writes, so different workers never move a shared file
 *   pointer and no post-merge pass is needed.
 * - A segment's [SegmentState.done] only advances after a buffer is fully
 *   written; a crash can at worst repeat an idempotent overwrite of the same
 *   byte range.
 * - Resuming is guarded by validators (ETag / Last-Modified) and by strict
 *   Content-Range validation, so bytes from a changed remote file are never
 *   stitched together.
 */
class SegmentedDownloader(
    private val fetcher: HttpFetcher,
    private val maxAttempts: Int = 5,
    private val retryBaseDelayMs: Long = 1_000L,
    private val minSplitSize: Long = SegmentPlanner.DEFAULT_MIN_SPLIT_SIZE,
) {

    constructor(
        client: OkHttpClient,
        maxAttempts: Int = 5,
        retryBaseDelayMs: Long = 1_000L,
        minSplitSize: Long = SegmentPlanner.DEFAULT_MIN_SPLIT_SIZE,
    ) : this(HttpFetcher(client), maxAttempts, retryBaseDelayMs, minSplitSize)

    data class Outcome(
        val info: RemoteFileInfo,
        val control: ControlFile,
        val bytesWritten: Long,
    )

    /**
     * Downloads [sourceUrl] into [partFile]. [previous] carries resume state
     * from an earlier session; the engine restarts cleanly whenever resuming
     * cannot be proven safe. [onProgress] is invoked periodically and once
     * more on every exit path (including cancellation) with live segment
     * counters, so the caller can persist resume state.
     */
    suspend fun download(
        sourceUrl: HttpUrl,
        partFile: File,
        previous: ControlFile?,
        maxConnections: Int,
        allowInsecure: Boolean,
        onValidated: suspend (RemoteFileInfo, ControlFile) -> Unit = { _, _ -> },
        onProgress: suspend (ControlFile) -> Unit = {},
    ): Outcome {
        var pending = previous
        var restartsLeft = 1
        while (true) {
            try {
                return runOnce(
                    sourceUrl = sourceUrl,
                    partFile = partFile,
                    previous = pending,
                    maxConnections = maxConnections,
                    allowInsecure = allowInsecure,
                    onValidated = onValidated,
                    onProgress = onProgress,
                )
            } catch (e: EngineException.ValidatorChanged) {
                if (restartsLeft <= 0) {
                    throw EngineException.Protocol("The remote file kept changing; giving up")
                }
                restartsLeft--
                pending = null
            }
        }
    }

    private suspend fun runOnce(
        sourceUrl: HttpUrl,
        partFile: File,
        previous: ControlFile?,
        maxConnections: Int,
        allowInsecure: Boolean,
        onValidated: suspend (RemoteFileInfo, ControlFile) -> Unit,
        onProgress: suspend (ControlFile) -> Unit,
    ): Outcome {
        val fresh = retry("probe") { fetcher.probe(sourceUrl, allowInsecure) }
        val control = when (val decision = ResumePolicy.decide(previous, fresh)) {
            is ResumePolicy.Decision.Resume -> decision.control
            ResumePolicy.Decision.Fresh -> {
                partFile.delete()
                ControlFile(
                    sourceUrl = sourceUrl.toString(),
                    finalUrl = fresh.finalUrl.toString(),
                    totalBytes = fresh.contentLength,
                    etag = fresh.etag,
                    lastModified = fresh.lastModified,
                    acceptRanges = fresh.acceptRanges,
                    segments = SegmentPlanner.plan(
                        totalBytes = fresh.contentLength,
                        acceptRanges = fresh.acceptRanges,
                        maxConnections = maxConnections,
                        minSplitSize = minSplitSize,
                    ),
                )
            }
        }
        onValidated(fresh, control)

        partFile.parentFile?.mkdirs()
        try {
            RandomAccessFile(partFile, "rw").use { file ->
                control.totalBytes?.let { total ->
                    if (file.length() != total) file.setLength(total)
                }
                val channel = file.channel
                coroutineScope {
                    val workers = control.segments.map { segment ->
                        launch(Dispatchers.IO) {
                            downloadSegment(channel, control, segment, allowInsecure = allowInsecure)
                        }
                    }
                    val ticker = launch {
                        while (isActive) {
                            onProgress(control)
                            delay(PROGRESS_INTERVAL_MS)
                        }
                    }
                    try {
                        workers.joinAll()
                    } finally {
                        ticker.cancel()
                    }
                }
                channel.force(true)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { onProgress(control) }
            }
        }

        val expected = control.totalBytes
        if (expected != null && partFile.length() != expected) {
            throw EngineException.Protocol(
                "Downloaded size ${partFile.length()} does not match expected size $expected",
            )
        }
        if (control.segments.any { !it.isComplete && !it.isUnknownLength }) {
            throw EngineException.Protocol("Not all segments completed")
        }
        return Outcome(info = fresh, control = control, bytesWritten = partFile.length())
    }

    private suspend fun <T> retry(what: String, block: suspend () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++attempts > maxAttempts) {
                    throw EngineException.Network("$what failed after $maxAttempts attempts: ${e.message}", e)
                }
                delay(delayFor(attempts, null))
            } catch (e: EngineException.Http) {
                currentCoroutineContext().ensureActive()
                if (!e.isRetryable || ++attempts > maxAttempts) throw e
                delay(delayFor(attempts, e.retryAfterSeconds))
            }
        }
    }

    private suspend fun downloadSegment(
        channel: FileChannel,
        control: ControlFile,
        segment: SegmentState,
        allowInsecure: Boolean,
    ) {
        var attempts = 0
        while (true) {
            if (!segment.isUnknownLength && segment.isComplete) return
            try {
                fetchSegmentOnce(channel, control, segment, allowInsecure)
                if (segment.isUnknownLength || segment.isComplete) return
                throw IOException("Range response ended before the segment was complete")
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException.ValidatorChanged) {
                throw e
            } catch (e: EngineException.Protocol) {
                throw e
            } catch (e: EngineException.Http) {
                currentCoroutineContext().ensureActive()
                if (!e.isRetryable || ++attempts > maxAttempts) throw e
                delay(delayFor(attempts, e.retryAfterSeconds))
            } catch (e: EngineException.Network) {
                currentCoroutineContext().ensureActive()
                if (++attempts > maxAttempts) throw e
                delay(delayFor(attempts, null))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++attempts > maxAttempts) {
                    throw EngineException.Network("Network error after $maxAttempts attempts: ${e.message}", e)
                }
                delay(delayFor(attempts, null))
            }
        }
    }

    private suspend fun fetchSegmentOnce(
        channel: FileChannel,
        control: ControlFile,
        segment: SegmentState,
        allowInsecure: Boolean,
    ) {
        val url = control.finalUrl.toHttpUrlOrNull()
            ?: throw EngineException.Protocol("Invalid URL in control file")
        val ranged = control.acceptRanges && !segment.isUnknownLength
        val from: Long = if (ranged) {
            segment.start + segment.done
        } else {
            segment.done = 0L
            0L
        }

        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept-Encoding", "identity")

        if (ranged) {
            val to = segment.endExclusive - 1
            if (from > to) return
            builder.header("Range", "bytes=$from-$to")
            ifRangeValidator(control)?.let { validator ->
                if (from > 0L) builder.header("If-Range", validator)
            }
        }

        fetcher.execute(builder.build(), allowInsecure).use { response ->
            requireIdentityEncoding(response)
            when (response.code) {
                206 -> {
                    if (!ranged) throw EngineException.Protocol("Unexpected 206 for a non-ranged request")
                    val range = ContentRangeParser.parse(response.header("Content-Range"))
                        ?: throw EngineException.Protocol("Missing Content-Range in 206 response")
                    if (!ContentRangeParser.coversRequest(range, from, segment.endExclusive, control.totalBytes)) {
                        throw EngineException.Protocol("Untrusted Content-Range: ${response.header("Content-Range")}")
                    }
                    writeBody(channel, response, position = from, limitExclusive = segment.endExclusive, segment = segment)
                }

                200 -> {
                    if (ranged && (from > 0L || control.segments.size > 1)) {
                        throw EngineException.ValidatorChanged("Server stopped honoring ranges")
                    }
                    // Single full stream: either the server does not do ranges
                    // at all, or it answered a whole-file range with 200.
                    segment.done = 0L
                    val limit = if (segment.isUnknownLength) -1L else segment.endExclusive
                    writeBody(channel, response, position = 0L, limitExclusive = limit, segment = segment)
                }

                416 -> throw EngineException.ValidatorChanged("Requested range no longer available")

                else -> throw httpFailure(response)
            }
        }
    }

    private suspend fun writeBody(
        channel: FileChannel,
        response: Response,
        position: Long,
        limitExclusive: Long,
        segment: SegmentState,
    ) {
        val body = response.body ?: throw EngineException.Protocol("Empty response body")
        val source = body.source()
        val buffer = ByteArray(BUFFER_SIZE)
        var cursor = position
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                if (limitExclusive >= 0L) {
                    val room = limitExclusive - cursor
                    if (read.toLong() > room) {
                        throw EngineException.Protocol("Server sent more data than the requested range")
                    }
                }
                var offset = 0
                while (offset < read) {
                    val written = channel.write(ByteBuffer.wrap(buffer, offset, read - offset), cursor + offset)
                    if (written <= 0) throw IOException("File write returned $written")
                    offset += written
                }
                cursor += read
                segment.done += read
            }
        } finally {
            runCatching { source.close() }
        }
    }

    private fun requireIdentityEncoding(response: Response) {
        val encoding = response.header("Content-Encoding")?.trim()?.lowercase()
        if (encoding != null && encoding != "identity") {
            throw EngineException.Protocol("Unsupported Content-Encoding: $encoding")
        }
    }

    private fun httpFailure(response: Response): EngineException.Http {
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            ?.coerceIn(0, HttpFetcher.MAX_RETRY_AFTER_SECONDS)
        val message = when (response.code) {
            401 -> "Authentication required (401)"
            403 -> "Access denied (403)"
            404 -> "Not found (404)"
            in 500..599 -> "Server error (${response.code})"
            else -> "HTTP ${response.code} ${response.message}"
        }
        return EngineException.Http(response.code, message, retryAfter)
    }

    private fun ifRangeValidator(control: ControlFile): String? {
        val etag = control.etag
        if (etag != null && !etag.startsWith("W/")) return etag
        return control.lastModified
    }

    private fun delayFor(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null) return (retryAfterSeconds * 1_000L).coerceAtLeast(0L)
        val exponential = retryBaseDelayMs shl (attempt - 1).coerceAtMost(5)
        return exponential.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }
}

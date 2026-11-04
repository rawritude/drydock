package io.github.rawritude.drydock.engine

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import io.github.rawritude.drydock.util.ContentDisposition

/**
 * Minimal HTTP layer for the engine.
 *
 * Redirects are followed manually so that every hop can be checked:
 * scheme allow-listing, downgrade protection, and a hard hop limit.
 * Automatic redirect following must be disabled on the client.
 */
class HttpFetcher(private val client: OkHttpClient) {

    suspend fun probe(url: okhttp3.HttpUrl, allowInsecure: Boolean): RemoteFileInfo {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .build()

        execute(request, allowInsecure).use { response ->
            return when (response.code) {
                206 -> {
                    val range = ContentRangeParser.parse(response.header("Content-Range"))
                        ?: throw EngineException.Protocol("Invalid Content-Range on probe")
                    if (range.start != 0L) {
                        throw EngineException.Protocol("Unexpected Content-Range start: ${range.start}")
                    }
                    info(response, total = range.total, acceptRanges = true)
                }

                200 -> info(
                    response,
                    total = response.body?.contentLength()?.takeIf { it >= 0 },
                    acceptRanges = false,
                )

                416 -> {
                    // Some servers reject `bytes=0-0` for empty files; retry without a range.
                    val plain = Request.Builder()
                        .url(response.request.url)
                        .get()
                        .header("Accept-Encoding", "identity")
                        .build()
                    execute(plain, allowInsecure).use { retry ->
                        if (retry.code != 200) throw httpException(retry)
                        info(
                            retry,
                            total = retry.body?.contentLength()?.takeIf { it >= 0 },
                            acceptRanges = true,
                        )
                    }
                }

                else -> throw httpException(response)
            }
        }
    }

    /** Executes [request], following redirects with safety checks. Caller closes the response. */
    suspend fun execute(request: Request, allowInsecure: Boolean): Response {
        if (request.url.scheme == "http" && !allowInsecure) {
            throw EngineException.Protocol("Insecure HTTP is not allowed for this download")
        }
        var current = request
        var hops = 0
        while (true) {
            val response = client.newCall(current).awaitResponse()
            if (!response.isRedirect) return response

            val location = response.header("Location")
            if (location.isNullOrBlank()) return response
            if (++hops > MAX_REDIRECTS) {
                response.close()
                throw EngineException.Protocol("Too many redirects")
            }
            val next = current.url.resolve(location)
            if (next == null) {
                response.close()
                throw EngineException.Protocol("Invalid redirect location")
            }
            if (next.scheme != "https" && !allowInsecure) {
                response.close()
                throw EngineException.Protocol("Redirect to insecure URL blocked")
            }
            if (current.url.isHttps && !next.isHttps) {
                response.close()
                throw EngineException.Protocol("HTTPS to HTTP downgrade blocked")
            }
            current = current.newBuilder().url(next).build()
            response.close()
        }
    }

    private fun info(response: Response, total: Long?, acceptRanges: Boolean): RemoteFileInfo =
        RemoteFileInfo(
            finalUrl = response.request.url,
            contentLength = total,
            acceptRanges = acceptRanges,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            contentDispositionFileName = ContentDisposition.parse(response.header("Content-Disposition")),
            mimeType = response.body?.contentType()?.let { "${it.type}/${it.subtype}" },
        )

    private fun httpException(response: Response): EngineException.Http {
        val message = when (response.code) {
            401 -> "Authentication required (401)"
            403 -> "Access denied (403)"
            404 -> "Not found (404)"
            in 500..599 -> "Server error (${response.code})"
            else -> "HTTP ${response.code} ${response.message}"
        }
        return EngineException.Http(response.code, message, parseRetryAfter(response.header("Retry-After")))
    }

    private fun parseRetryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it.coerceIn(0, MAX_RETRY_AFTER_SECONDS) }
        return runCatching {
            val until = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            java.time.Duration.between(Instant.now(), until.toInstant()).seconds
                .coerceIn(0, MAX_RETRY_AFTER_SECONDS)
        }.getOrNull()
    }

    companion object {
        const val MAX_REDIRECTS = 8
        const val MAX_RETRY_AFTER_SECONDS = 300L
    }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}

internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        chain.proceed(request)
    }
    .build()

internal const val USER_AGENT = "Drydock/0.1 (+https://github.com/rawritude/drydock)"

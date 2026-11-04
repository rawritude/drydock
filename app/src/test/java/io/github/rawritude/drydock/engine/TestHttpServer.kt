package io.github.rawritude.drydock.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 test server built on raw sockets (Android unit tests have
 * no com.sun.net.httpserver). Serves one file with ranged responses, supports
 * failure injection, ETag changes, throttling and an unknown-length mode.
 * Every response closes the connection.
 */
class TestHttpServer(
    @Volatile var content: ByteArray,
    private val supportRanges: Boolean = true,
    private val omitContentLength: Boolean = false,
) {

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val executor = Executors.newCachedThreadPool()
    private val requestCount = AtomicInteger()
    private val rangedCount = AtomicInteger()

    @Volatile var failuresRemaining: Int = 0

    @Volatile var etag: String = "\"v1\""

    @Volatile var lastModified: String = "Wed, 21 Oct 2015 07:28:00 GMT"

    @Volatile var corruptContentRange: Boolean = false

    /** Throttle delivery speed so tests can cancel mid-download deterministically. */
    @Volatile var throttleBytesPerSecond: Long = 0

    fun start() {
        thread(name = "test-http-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break
                }
                executor.execute { handle(socket) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    fun fileUrl(): String = "http://127.0.0.1:${serverSocket.localPort}/file"

    fun totalRequests(): Int = requestCount.get()

    fun rangedRequests(): Int = rangedCount.get()

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                val input = client.getInputStream()
                val out = client.getOutputStream()

                val requestLine = readLine(input) ?: return
                if (!requestLine.startsWith("GET ")) {
                    writeHead(out, 405, "Method Not Allowed", emptyMap(), 0)
                    return
                }
                val headers = HashMap<String, String>()
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    val separator = header.indexOf(':')
                    if (separator > 0) {
                        headers[header.substring(0, separator).trim().lowercase()] = header.substring(separator + 1).trim()
                    }
                }

                requestCount.incrementAndGet()
                if (failuresRemaining > 0) {
                    failuresRemaining--
                    writeHead(out, 500, "Internal Server Error", emptyMap(), 0)
                    return
                }

                val rangeHeader = headers["range"]
                if (rangeHeader == null || !supportRanges) {
                    respondFull(out)
                } else {
                    respondRange(out, rangeHeader)
                }
            } catch (e: IOException) {
                // Client cancelled; nothing to do.
            }
        }
    }

    private fun respondFull(out: OutputStream) {
        val headers = mutableMapOf(
            "Content-Type" to "application/octet-stream",
            "ETag" to etag,
            "Last-Modified" to lastModified,
        )
        if (supportRanges) headers["Accept-Ranges"] = "bytes"
        val length = if (omitContentLength) null else content.size.toLong()
        writeHead(out, 200, "OK", headers, length)
        writeThrottled(out, content, 0, content.size)
    }

    private fun respondRange(out: OutputStream, rangeHeader: String) {
        rangedCount.incrementAndGet()
        val match = RANGE.find(rangeHeader)
        if (match == null || content.isEmpty()) {
            writeHead(out, 416, "Range Not Satisfiable", emptyMap(), 0)
            return
        }
        val requestedStart = match.groupValues[1].toLong()
        val requestedEnd = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong()
        val start = requestedStart.toInt().coerceIn(0, content.size - 1)
        val end = (requestedEnd ?: (content.size - 1).toLong()).toInt().coerceIn(start, content.size - 1)
        val length = end - start + 1

        val contentRange = if (corruptContentRange) {
            "bytes ${start + 1000}-${end + 1000}/${content.size}"
        } else {
            "bytes $start-$end/${content.size}"
        }
        writeHead(
            out,
            206,
            "Partial Content",
            mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Range" to contentRange,
                "Accept-Ranges" to "bytes",
                "ETag" to etag,
                "Last-Modified" to lastModified,
            ),
            length.toLong(),
        )
        writeThrottled(out, content, start, length)
    }

    private fun writeThrottled(out: OutputStream, data: ByteArray, offset: Int, length: Int) {
        val rate = throttleBytesPerSecond
        if (rate <= 0L) {
            out.write(data, offset, length)
            out.flush()
            return
        }
        var written = 0
        while (written < length) {
            val chunk = minOf(THROTTLE_CHUNK, length - written)
            out.write(data, offset + written, chunk)
            out.flush()
            written += chunk
            Thread.sleep(chunk * 1_000L / rate)
        }
    }

    private fun writeHead(
        out: OutputStream,
        code: Int,
        reason: String,
        headers: Map<String, String>,
        contentLength: Long?,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 $code $reason\r\n")
        for ((name, value) in headers) {
            builder.append("$name: $value\r\n")
        }
        if (contentLength != null) builder.append("Content-Length: $contentLength\r\n")
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(byte.toChar())
            if (builder.length > MAX_LINE) return null
        }
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d*)""")
        const val THROTTLE_CHUNK = 32 * 1024
        const val MAX_LINE = 8 * 1024
    }
}

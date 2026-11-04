package io.github.rawritude.drydock.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SegmentedDownloaderTest {

    private lateinit var server: TestHttpServer
    private lateinit var workDir: File

    private val downloader = SegmentedDownloader(
        client = defaultHttpClient(),
        maxAttempts = 4,
        retryBaseDelayMs = 25L,
        minSplitSize = 1L shl 20,
    )

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("drydock-test").toFile()
    }

    @After
    fun tearDown() {
        server.stop()
        workDir.deleteRecursively()
    }

    private fun startServer(content: ByteArray, ranges: Boolean = true, chunked: Boolean = false): TestHttpServer {
        server = TestHttpServer(content, supportRanges = ranges, omitContentLength = chunked)
        server.start()
        return server
    }

    private fun partFile(): File = File(workDir, "download.part")

    private fun randomBytes(size: Int): ByteArray {
        val random = java.util.Random(42)
        return ByteArray(size).also { random.nextBytes(it) }
    }

    @Test
    fun `segmented download matches source bytes`() = runBlocking {
        val content = randomBytes(4L.toInt() shl 20)
        val server = startServer(content)

        val outcome = downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = null,
            maxConnections = 4,
            allowInsecure = true,
        )

        assertArrayEquals(content, partFile().readBytes())
        assertEquals(content.size.toLong(), outcome.bytesWritten)
        assertTrue("expected multiple ranged requests", server.rangedRequests() >= 4)
    }

    @Test
    fun `sequential download when ranges unsupported`() = runBlocking {
        val content = randomBytes(2L.toInt() shl 20)
        val server = startServer(content, ranges = false)

        downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = null,
            maxConnections = 4,
            allowInsecure = true,
        )

        assertArrayEquals(content, partFile().readBytes())
        assertEquals("no ranged requests expected", 0, server.rangedRequests())
    }

    @Test
    fun `unknown length download streams to eof`() = runBlocking {
        val content = randomBytes(512 * 1024)
        val server = startServer(content, ranges = false, chunked = true)

        val outcome = downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = null,
            maxConnections = 4,
            allowInsecure = true,
        )

        assertEquals(null, outcome.control.totalBytes)
        assertArrayEquals(content, partFile().readBytes())
    }

    @Test
    fun `interrupted download resumes and preserves bytes`() = runBlocking {
        val content = randomBytes(4L.toInt() shl 20)
        val server = startServer(content)
        server.throttleBytesPerSecond = 1L shl 20

        var captured: ControlFile? = null
        lateinit var job: Job
        val scope = CoroutineScope(Dispatchers.IO + Job())
        job = scope.launch {
            downloader.download(
                sourceUrl = server.fileUrl().toHttpUrl(),
                partFile = partFile(),
                previous = null,
                maxConnections = 4,
                allowInsecure = true,
                onValidated = { _, control -> captured = control },
                onProgress = { control ->
                    captured = control
                    if (control.segments.sumOf { it.done } >= (1L shl 20)) {
                        scope.coroutineContext[Job]?.cancel()
                    }
                },
            )
        }
        withTimeout(15_000) { job.join() }

        val partial = captured
        assertNotNull("control must be captured before cancellation", partial)
        assertTrue("some bytes should have been written", partial!!.segments.sumOf { it.done } > 0)

        server.throttleBytesPerSecond = 0
        val outcome = downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = partial,
            maxConnections = 4,
            allowInsecure = true,
        )

        assertArrayEquals(content, partFile().readBytes())
        assertEquals(content.size.toLong(), outcome.bytesWritten)
        assertTrue("resume should continue with ranged requests", server.rangedRequests() > 4)
    }

    @Test
    fun `validator change on resume restarts from scratch`() = runBlocking {
        val firstContent = randomBytes(4L.toInt() shl 20)
        val server = startServer(firstContent)
        server.throttleBytesPerSecond = 1L shl 20

        var captured: ControlFile? = null
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val job = scope.launch {
            downloader.download(
                sourceUrl = server.fileUrl().toHttpUrl(),
                partFile = partFile(),
                previous = null,
                maxConnections = 4,
                allowInsecure = true,
                onValidated = { _, control -> captured = control },
                onProgress = { control ->
                    captured = control
                    if (control.segments.sumOf { it.done } >= (1L shl 20)) {
                        scope.coroutineContext[Job]?.cancel()
                    }
                },
            )
        }
        withTimeout(15_000) { job.join() }

        // The remote file changes completely.
        val secondContent = randomBytes(3L.toInt() shl 20)
        server.content = secondContent
        server.etag = "\"v2\""
        server.throttleBytesPerSecond = 0

        downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = captured,
            maxConnections = 4,
            allowInsecure = true,
        )

        assertArrayEquals(secondContent, partFile().readBytes())
    }

    @Test
    fun `transient server errors are retried`() = runBlocking {
        val content = randomBytes(1L.toInt() shl 20)
        val server = startServer(content)
        server.failuresRemaining = 2

        downloader.download(
            sourceUrl = server.fileUrl().toHttpUrl(),
            partFile = partFile(),
            previous = null,
            maxConnections = 2,
            allowInsecure = true,
        )

        assertArrayEquals(content, partFile().readBytes())
    }

    @Test
    fun `untrusted content range is rejected`() = runBlocking {
        val content = randomBytes(1L.toInt() shl 20)
        val server = startServer(content)
        server.corruptContentRange = true

        try {
            downloader.download(
                sourceUrl = server.fileUrl().toHttpUrl(),
                partFile = partFile(),
                previous = null,
                maxConnections = 2,
                allowInsecure = true,
            )
            fail("Expected a protocol error")
        } catch (e: EngineException.Protocol) {
            // expected: the engine refuses to write untrusted bytes
        }
    }

    @Test
    fun `insecure http is rejected unless allowed`() = runBlocking {
        val content = randomBytes(64 * 1024)
        val server = startServer(content)

        try {
            downloader.download(
                sourceUrl = server.fileUrl().toHttpUrl(),
                partFile = partFile(),
                previous = null,
                maxConnections = 1,
                allowInsecure = false,
            )
            fail("Expected insecure scheme to be blocked")
        } catch (e: EngineException.Protocol) {
            // expected
        }
        assertEquals(0, server.totalRequests())
    }
}

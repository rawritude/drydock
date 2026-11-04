package io.github.rawritude.drydock.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.rawritude.drydock.data.DownloadFileStore
import io.github.rawritude.drydock.data.DownloadRepository
import io.github.rawritude.drydock.data.MediaStorePublisher
import io.github.rawritude.drydock.data.SettingsStore
import io.github.rawritude.drydock.data.db.DownloadEntity
import io.github.rawritude.drydock.data.db.DownloadState
import io.github.rawritude.drydock.engine.ControlFile
import io.github.rawritude.drydock.engine.EngineException
import io.github.rawritude.drydock.engine.RemoteFileInfo
import io.github.rawritude.drydock.engine.SegmentedDownloader
import io.github.rawritude.drydock.util.FileNames
import io.github.rawritude.drydock.util.UrlSafety
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Owns the download queue: enqueue, pause/resume/cancel, concurrency limits,
 * Wi-Fi gating, persistence, publishing, notifications.
 *
 * All state transitions funnel through here; the service is only a
 * notification/foreground carrier.
 */
class DownloadCoordinator(
    context: Context,
    private val repository: DownloadRepository,
    private val settings: SettingsStore,
    private val fileStore: DownloadFileStore,
    private val publisher: MediaStorePublisher,
    private val downloader: SegmentedDownloader,
    private val notifier: Notifier,
) {

    private val appContext = context.applicationContext

    data class AddRequest(
        val url: String,
        val fileName: String? = null,
        val sha256: String? = null,
        val allowInsecureHttp: Boolean = false,
    )

    data class ProgressSnapshot(
        val name: String,
        val bytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val speedMeters = ConcurrentHashMap<Long, SpeedMeter>()
    private val startMutex = Mutex()

    private val _progress = MutableStateFlow<Map<Long, ProgressSnapshot>>(emptyMap())
    val progress: StateFlow<Map<Long, ProgressSnapshot>> = _progress.asStateFlow()

    /** Called once from Application.onCreate. */
    fun launch() {
        scope.launch {
            repository.markInterruptedAsPaused()
            registerNetworkObserver()
            maybeStartNext()
        }
    }

    // ---------------------------------------------------------------- queue

    /** Validates and enqueues a new download. Throws [IllegalArgumentException] on invalid input. */
    suspend fun enqueue(request: AddRequest): Long {
        val validated = UrlSafety.validate(request.url, request.allowInsecureHttp)
        if (validated is UrlSafety.Result.Rejected) throw IllegalArgumentException(validated.reason)
        val url = (validated as UrlSafety.Result.Ok).url

        val customName = request.fileName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { FileNames.sanitize(it) }
        val displayName = customName ?: FileNames.guessFromUrl(url) ?: "download"
        val sha256 = request.sha256
            ?.trim()
            ?.lowercase()
            ?.takeIf { SHA256_HEX.matches(it) }

        val id = repository.insert(
            DownloadEntity(
                url = url.toString(),
                fileName = displayName,
                customFileName = customName,
                expectedSha256 = sha256,
                allowInsecureHttp = request.allowInsecureHttp,
            ),
        )
        maybeStartNext()
        return id
    }

    fun pause(id: Long) {
        val job = jobs[id]
        if (job != null) {
            job.cancel()
        } else {
            scope.launch { repository.setState(id, DownloadState.PAUSED, null) }
        }
    }

    fun resume(id: Long) {
        scope.launch {
            val entity = repository.byId(id) ?: return@launch
            if (entity.state != DownloadState.PAUSED && entity.state != DownloadState.FAILED) return@launch
            repository.setState(id, DownloadState.QUEUED, null)
            maybeStartNext()
        }
    }

    /** Removes the download and deletes all partial data. */
    fun cancel(id: Long) {
        scope.launch {
            jobs[id]?.cancelAndJoin()
            fileStore.deleteFiles(id)
            _progress.update { it - id }
            repository.delete(id)
            stopServiceIfIdle()
        }
    }

    fun pauseAll() {
        jobs.values.toList().forEach { it.cancel() }
        scope.launch { repository.pauseQueued() }
    }

    fun resumeAll() {
        scope.launch {
            repository.resumeAllPaused()
            maybeStartNext()
        }
    }

    // ------------------------------------------------------------- internal

    private fun maybeStartNext() {
        scope.launch {
            startMutex.withLock {
                val maxConcurrent = settings.concurrentDownloads.first()
                while (jobs.size < maxConcurrent) {
                    if (!networkAllows()) return@withLock
                    val next = repository.nextQueued() ?: return@withLock
                    val job = scope.launch(start = CoroutineStart.LAZY) { runDownload(next) }
                    jobs[next.id] = job
                    job.start()
                    ensureServiceStarted()
                }
            }
        }
    }

    private suspend fun networkAllows(): Boolean {
        if (!settings.wifiOnly.first()) return true
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return !manager.isActiveNetworkMetered
    }

    private fun registerNetworkObserver() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = maybeStartNext()

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    maybeStartNext()
            })
        }
    }

    private suspend fun runDownload(entity: DownloadEntity) {
        val id = entity.id
        val speedMeter = SpeedMeter()
        speedMeters[id] = speedMeter
        var control: ControlFile? = null
        var lastPersistAt = 0L
        val displayName = entity.customFileName ?: entity.fileName

        try {
            repository.setState(id, DownloadState.DOWNLOADING, null)
            val sourceUrl = entity.url.toHttpUrlOrNull()
                ?: throw EngineException.Protocol("Invalid URL")

            val outcome = downloader.download(
                sourceUrl = sourceUrl,
                partFile = fileStore.partFile(id),
                previous = fileStore.readControl(id),
                maxConnections = settings.connectionsPerDownload.first(),
                allowInsecure = entity.allowInsecureHttp,
                onValidated = { info, freshControl ->
                    control = freshControl
                    repository.setValidated(id, info.contentLength, info.mimeType, resolveFileName(entity, info))
                },
                onProgress = { current ->
                    control = current
                    val bytes = current.segments.sumOf { it.done }
                    val bytesPerSecond = speedMeter.update(bytes)
                    _progress.update {
                        it + (id to ProgressSnapshot(displayName, bytes, current.totalBytes, bytesPerSecond))
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                        lastPersistAt = now
                        repository.setProgress(id, bytes)
                        fileStore.writeControl(id, current)
                    }
                },
            )

            val published = publisher.publish(
                partFile = fileStore.partFile(id),
                displayName = resolveFileName(entity, outcome.info),
                mimeType = outcome.info.mimeType,
                expectedSha256 = entity.expectedSha256,
            )
            repository.setCompleted(id, published.uri.toString(), published.displayName, outcome.bytesWritten)
            fileStore.deleteFiles(id)
            notifier.notifyComplete(id, published.displayName, published.uri, outcome.info.mimeType)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                control?.let { current ->
                    fileStore.writeControl(id, current)
                    runCatching { repository.setProgress(id, current.segments.sumOf { it.done }) }
                }
                runCatching { repository.setState(id, DownloadState.PAUSED, null) }
            }
            throw e
        } catch (e: Throwable) {
            val message = friendlyMessage(e)
            withContext(NonCancellable) {
                if (e is EngineException.ChecksumMismatch || e is EngineException.Protocol) {
                    fileStore.deleteFiles(id)
                }
                runCatching { repository.setState(id, DownloadState.FAILED, message) }
                notifier.notifyFailed(id, displayName, message)
            }
        } finally {
            jobs.remove(id)
            speedMeters.remove(id)
            _progress.update { it - id }
            maybeStartNext()
            stopServiceIfIdle()
        }
    }

    private fun resolveFileName(entity: DownloadEntity, info: RemoteFileInfo): String {
        entity.customFileName?.let { return it }
        info.contentDispositionFileName?.let { serverName ->
            val sanitized = FileNames.sanitize(serverName)
            if (sanitized.isNotBlank()) return sanitized
        }
        FileNames.guessFromUrl(info.finalUrl)?.let { return it }
        return entity.fileName
    }

    private fun friendlyMessage(e: Throwable): String = when (e) {
        is EngineException.ChecksumMismatch -> "Checksum verification failed"
        is EngineException.Http -> e.message ?: "HTTP error"
        is EngineException.Network -> e.message ?: "Network error"
        is EngineException.ValidatorChanged -> "Remote file changed; the download restarted"
        is EngineException.Protocol -> e.message ?: "Protocol error"
        else -> e.message ?: "Download failed"
    }

    private fun ensureServiceStarted() {
        ContextCompat.startForegroundService(appContext, Intent(appContext, DownloadService::class.java))
    }

    private fun stopServiceIfIdle() {
        scope.launch {
            startMutex.withLock {
                if (jobs.isEmpty()) {
                    appContext.stopService(Intent(appContext, DownloadService::class.java))
                }
            }
        }
    }

    private class SpeedMeter {
        private var lastBytes = 0L
        private var lastTime = 0L
        private var ema = 0.0

        fun update(totalBytes: Long): Long {
            val now = System.nanoTime()
            if (lastTime == 0L) {
                lastTime = now
                lastBytes = totalBytes
                return 0L
            }
            val elapsed = now - lastTime
            if (elapsed < MIN_SAMPLE_NANOS) return ema.toLong()
            val instant = (totalBytes - lastBytes) * NANOS_PER_SECOND / elapsed
            ema = if (ema == 0.0) instant.toDouble() else ema * 0.7 + instant * 0.3
            lastBytes = totalBytes
            lastTime = now
            return ema.toLong().coerceAtLeast(0L)
        }

        private companion object {
            const val NANOS_PER_SECOND = 1_000_000_000L
            const val MIN_SAMPLE_NANOS = 500_000_000L
        }
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 1_000L
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

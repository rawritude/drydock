package io.github.rawritude.drydock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rawritude.drydock.AppContainer
import io.github.rawritude.drydock.capture.LinkCaptureManager
import io.github.rawritude.drydock.data.DownloadRepository
import io.github.rawritude.drydock.data.SettingsStore
import io.github.rawritude.drydock.data.db.DownloadEntity
import io.github.rawritude.drydock.service.DownloadCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: DownloadRepository,
    private val coordinator: DownloadCoordinator,
    private val settingsStore: SettingsStore,
    private val linkCapture: LinkCaptureManager,
) : ViewModel() {

    data class Row(
        val entity: DownloadEntity,
        val progress: DownloadCoordinator.ProgressSnapshot?,
    )

    data class UiSettings(
        val wifiOnly: Boolean,
        val connectionsPerDownload: Int,
        val concurrentDownloads: Int,
        val captureEnabled: Boolean,
        val capturePatterns: String,
        val captureAllowHttp: Boolean,
    )

    val rows: StateFlow<List<Row>> = combine(
        repository.observeAll(),
        coordinator.progress,
    ) { entities, progress ->
        entities.map { Row(it, progress[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val coreSettings = combine(
        settingsStore.wifiOnly,
        settingsStore.connectionsPerDownload,
        settingsStore.concurrentDownloads,
    ) { wifiOnly, connections, concurrent -> Triple(wifiOnly, connections, concurrent) }

    private val captureSettings = combine(
        settingsStore.captureEnabled,
        settingsStore.capturePatterns,
        settingsStore.captureAllowHttp,
    ) { enabled, patterns, allowHttp -> Triple(enabled, patterns, allowHttp) }

    val settings: StateFlow<UiSettings> = combine(coreSettings, captureSettings) { core, capture ->
        UiSettings(
            wifiOnly = core.first,
            connectionsPerDownload = core.second,
            concurrentDownloads = core.third,
            captureEnabled = capture.first,
            capturePatterns = capture.second,
            captureAllowHttp = capture.third,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiSettings(
            wifiOnly = false,
            connectionsPerDownload = SettingsStore.DEFAULT_CONNECTIONS,
            concurrentDownloads = SettingsStore.DEFAULT_CONCURRENT,
            captureEnabled = false,
            capturePatterns = "",
            captureAllowHttp = false,
        ),
    )

    fun add(
        url: String,
        fileName: String?,
        sha256: String?,
        allowInsecureHttp: Boolean,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                coordinator.enqueue(
                    DownloadCoordinator.AddRequest(
                        url = url,
                        fileName = fileName,
                        sha256 = sha256,
                        allowInsecureHttp = allowInsecureHttp,
                    ),
                )
                onResult(null)
            } catch (e: IllegalArgumentException) {
                onResult(e.message ?: "Invalid URL")
            }
        }
    }

    fun pause(id: Long) = coordinator.pause(id)

    fun resume(id: Long) = coordinator.resume(id)

    fun cancel(id: Long) = coordinator.cancel(id)

    fun removeCompleted(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun pauseAll() = coordinator.pauseAll()

    fun resumeAll() = coordinator.resumeAll()

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { settingsStore.setWifiOnly(value) }
    }

    fun setConnectionsPerDownload(value: Int) {
        viewModelScope.launch { settingsStore.setConnectionsPerDownload(value) }
    }

    fun setConcurrentDownloads(value: Int) {
        viewModelScope.launch { settingsStore.setConcurrentDownloads(value) }
    }

    fun setCaptureEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsStore.setCaptureEnabled(value)
            linkCapture.setEnabled(value)
        }
    }

    fun setCapturePatterns(value: String) {
        viewModelScope.launch { settingsStore.setCapturePatterns(value) }
    }

    fun setCaptureAllowHttp(value: Boolean) {
        viewModelScope.launch { settingsStore.setCaptureAllowHttp(value) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    repository = container.repository,
                    coordinator = container.coordinator,
                    settingsStore = container.settings,
                    linkCapture = container.linkCapture,
                )
            }
        }
    }
}

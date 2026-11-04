package io.github.rawritude.drydock

import android.app.Application
import android.content.Context
import io.github.rawritude.drydock.capture.LinkCaptureManager
import io.github.rawritude.drydock.data.DownloadFileStore
import io.github.rawritude.drydock.data.DownloadRepository
import io.github.rawritude.drydock.data.MediaStorePublisher
import io.github.rawritude.drydock.data.SettingsStore
import io.github.rawritude.drydock.data.db.DrydockDatabase
import io.github.rawritude.drydock.engine.SegmentedDownloader
import io.github.rawritude.drydock.engine.defaultHttpClient
import io.github.rawritude.drydock.service.DownloadCoordinator
import io.github.rawritude.drydock.service.Notifier

class DrydockApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()
        container.coordinator.launch()
    }
}

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database = DrydockDatabase.open(appContext)
    val repository = DownloadRepository(database.downloads())
    val settings = SettingsStore(appContext)
    val fileStore = DownloadFileStore(appContext)
    val publisher = MediaStorePublisher(appContext)
    val notifier = Notifier(appContext)
    val linkCapture = LinkCaptureManager(appContext)
    val downloader = SegmentedDownloader(defaultHttpClient())
    val coordinator = DownloadCoordinator(
        context = appContext,
        repository = repository,
        settings = settings,
        fileStore = fileStore,
        publisher = publisher,
        downloader = downloader,
        notifier = notifier,
    )
}

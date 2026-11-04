package io.github.rawritude.drydock.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.rawritude.drydock.DrydockApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground keeper while downloads are active. It exists only as long as the
 * coordinator has running jobs — the coordinator starts it when work begins
 * and stops it when the queue drains, so there is never an idle service.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as DrydockApp).container
        container.notifier.ensureChannels()
        ServiceCompat.startForeground(
            this,
            Notifier.ID_ACTIVE,
            container.notifier.buildInitial(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            container.coordinator.progress.collect { snapshots ->
                container.notifier.updateActive(snapshots.values)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as DrydockApp).container
        when (intent?.action) {
            ACTION_PAUSE_ALL -> container.coordinator.pauseAll()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "io.github.rawritude.drydock.action.PAUSE_ALL"
    }
}

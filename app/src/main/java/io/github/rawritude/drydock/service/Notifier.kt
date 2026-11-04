package io.github.rawritude.drydock.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rawritude.drydock.R
import io.github.rawritude.drydock.ui.MainActivity
import io.github.rawritude.drydock.util.Formatting

/**
 * All notification building lives here. Updates are throttled and silently
 * skipped when the user has not granted POST_NOTIFICATIONS.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private var lastPostedAt = 0L

    fun ensureChannels() {
        val active = NotificationChannel(
            CHANNEL_ACTIVE,
            context.getString(R.string.notification_channel_active),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        val done = NotificationChannel(
            CHANNEL_DONE,
            context.getString(R.string.notification_channel_done),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannels(listOf(active, done))
    }

    fun buildInitial(): Notification = baseBuilder(CHANNEL_ACTIVE)
        .setContentTitle(context.getString(R.string.notification_preparing))
        .setProgress(0, 0, true)
        .build()

    @SuppressLint("MissingPermission") // guarded by canPost()
    fun updateActive(snapshots: Collection<DownloadCoordinator.ProgressSnapshot>) {
        if (snapshots.isEmpty() || !canPost()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < UPDATE_INTERVAL_MS) return
        lastPostedAt = now

        val count = snapshots.size
        val bytes = snapshots.sumOf { it.bytes }
        val totals = snapshots.map { it.totalBytes }
        val total = if (totals.all { it != null }) totals.sumOf { it ?: 0L } else null
        val speed = snapshots.sumOf { it.bytesPerSecond }

        val title = context.resources.getQuantityString(R.plurals.notification_active_title, count, count)
        val text = if (count == 1) {
            val snapshot = snapshots.first()
            val progress = if (snapshot.totalBytes != null && snapshot.totalBytes > 0) {
                "${Formatting.bytes(snapshot.bytes)} / ${Formatting.bytes(snapshot.totalBytes)}"
            } else {
                Formatting.bytes(snapshot.bytes)
            }
            "${snapshot.name} · $progress · ${Formatting.speed(speed)}"
        } else {
            "${Formatting.bytes(bytes)} · ${Formatting.speed(speed)}"
        }

        val notification = baseBuilder(CHANNEL_ACTIVE)
            .setContentTitle(title)
            .setContentText(text)
            .apply {
                if (total != null && total > 0) {
                    setProgress(PROGRESS_MAX, ((bytes * PROGRESS_MAX) / total).toInt().coerceIn(0, PROGRESS_MAX), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
        manager.notify(ID_ACTIVE, notification)
    }

    @SuppressLint("MissingPermission") // guarded by canPost()
    fun notifyComplete(downloadId: Long, fileName: String, uri: Uri, mimeType: String?) {
        if (!canPost()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = baseBuilder(CHANNEL_DONE)
            .setContentTitle(context.getString(R.string.notification_complete_title))
            .setContentText(fileName)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(doneNotificationId(downloadId), notification)
    }

    @SuppressLint("MissingPermission") // guarded by canPost()
    fun notifyFailed(downloadId: Long, fileName: String, message: String) {
        if (!canPost()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = baseBuilder(CHANNEL_DONE)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText("$fileName · $message")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(doneNotificationId(downloadId), notification)
    }

    private fun baseBuilder(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                context.getString(R.string.pause_all),
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun doneNotificationId(downloadId: Long): Int =
        DONE_ID_BASE + (downloadId % 100_000L).toInt()

    companion object {
        const val ID_ACTIVE = 1
        private const val DONE_ID_BASE = 1_000
        private const val CHANNEL_ACTIVE = "active"
        private const val CHANNEL_DONE = "done"
        private const val UPDATE_INTERVAL_MS = 900L
        private const val PROGRESS_MAX = 1_000
    }
}

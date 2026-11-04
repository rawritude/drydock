package io.github.rawritude.drydock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rawritude.drydock.R
import io.github.rawritude.drydock.data.db.DownloadEntity
import io.github.rawritude.drydock.data.db.DownloadState
import io.github.rawritude.drydock.service.DownloadCoordinator
import io.github.rawritude.drydock.util.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var prefillUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<MainViewModel.Row?>(null) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            prefillUrl = sharedUrl
            showAddDialog = true
            onSharedUrlConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val hasActive = rows.any {
                        it.entity.state == DownloadState.DOWNLOADING ||
                            it.entity.state == DownloadState.QUEUED
                    }
                    val hasPaused = rows.any { it.entity.state == DownloadState.PAUSED }
                    when {
                        hasActive -> IconButton(onClick = viewModel::pauseAll) {
                            Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause_all))
                        }

                        hasPaused -> IconButton(onClick = viewModel::resumeAll) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.resume_all))
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    prefillUrl = null
                    showAddDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_download))
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.entity.id }) { row ->
                    DownloadCard(
                        row = row,
                        onPause = { viewModel.pause(row.entity.id) },
                        onResume = { viewModel.resume(row.entity.id) },
                        onRemove = {
                            if (row.entity.state == DownloadState.COMPLETED) {
                                viewModel.removeCompleted(row.entity.id)
                            } else {
                                pendingRemoval = row
                            }
                        },
                        onOpen = { openDownload(context, row.entity) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            prefillUrl = prefillUrl,
            onDismiss = {
                showAddDialog = false
                prefillUrl = null
            },
            onAdd = { url, fileName, sha256, allowHttp, report ->
                viewModel.add(url, fileName, sha256, allowHttp, report)
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onWifiOnlyChange = viewModel::setWifiOnly,
            onConnectionsChange = viewModel::setConnectionsPerDownload,
            onConcurrentChange = viewModel::setConcurrentDownloads,
            onCaptureEnabledChange = viewModel::setCaptureEnabled,
            onCapturePatternsChange = viewModel::setCapturePatterns,
            onCaptureAllowHttpChange = viewModel::setCaptureAllowHttp,
            onDismiss = { showSettings = false },
        )
    }

    pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_confirm_title)) },
            text = { Text("${row.entity.fileName}\n\n${stringResource(R.string.remove_confirm_body)}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cancel(row.entity.id)
                        pendingRemoval = null
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadCard(
    row: MainViewModel.Row,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val entity = row.entity
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = stateIcon(entity.state),
                    contentDescription = null,
                    tint = stateTint(entity.state),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entity.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusLine(entity, row.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RowActions(
                    state = entity.state,
                    onPause = onPause,
                    onResume = onResume,
                    onRemove = onRemove,
                    onOpen = onOpen,
                )
            }

            val fraction = progressFraction(entity, row.progress)
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RowActions(
    state: DownloadState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    when (state) {
        DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
            IconButton(onClick = onPause) {
                Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove))
            }
        }

        DownloadState.PAUSED -> {
            IconButton(onClick = onResume) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.resume))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove))
            }
        }

        DownloadState.FAILED -> {
            IconButton(onClick = onResume) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove))
            }
        }

        DownloadState.COMPLETED -> {
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.open))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove))
            }
        }
    }
}

@Composable
private fun statusLine(entity: DownloadEntity, progress: DownloadCoordinator.ProgressSnapshot?): String {
    val context = LocalContext.current
    val bytes = progress?.bytes ?: entity.downloadedBytes
    val total = progress?.totalBytes ?: entity.totalBytes
    val sizeText = if (total != null && total > 0) {
        "${Formatting.bytes(bytes)} / ${Formatting.bytes(total)}"
    } else {
        Formatting.bytes(bytes)
    }
    return when (entity.state) {
        DownloadState.QUEUED -> context.getString(R.string.state_queued)

        DownloadState.DOWNLOADING -> {
            val speed = progress?.bytesPerSecond ?: 0L
            buildString {
                append(context.getString(R.string.state_downloading))
                append(" · ")
                append(sizeText)
                if (speed > 0) append(" · ${Formatting.speed(speed)}")
                if (total != null && speed > 0) {
                    Formatting.eta(total - bytes, speed)?.let { append(" · $it") }
                }
            }
        }

        DownloadState.PAUSED -> "${context.getString(R.string.state_paused)} · $sizeText"

        DownloadState.COMPLETED ->
            "${context.getString(R.string.state_completed)} · ${Formatting.bytes(entity.totalBytes ?: entity.downloadedBytes)}"

        DownloadState.FAILED ->
            "${context.getString(R.string.state_failed)} · ${entity.error.orEmpty()}"
    }
}

private fun progressFraction(
    entity: DownloadEntity,
    progress: DownloadCoordinator.ProgressSnapshot?,
): Float? {
    if (entity.state == DownloadState.COMPLETED) return null
    val total = progress?.totalBytes ?: entity.totalBytes ?: return null
    if (total <= 0) return null
    val bytes = progress?.bytes ?: entity.downloadedBytes
    return (bytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun stateIcon(state: DownloadState): ImageVector = when (state) {
    DownloadState.QUEUED -> Icons.Default.Schedule
    DownloadState.DOWNLOADING -> Icons.Default.Download
    DownloadState.PAUSED -> Icons.Default.Pause
    DownloadState.COMPLETED -> Icons.Default.CheckCircle
    DownloadState.FAILED -> Icons.Default.ErrorOutline
}

@Composable
private fun stateTint(state: DownloadState) = when (state) {
    DownloadState.DOWNLOADING, DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun openDownload(context: Context, entity: DownloadEntity) {
    val uri = entity.savedUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, entity.mimeType ?: "application/octet-stream")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
}

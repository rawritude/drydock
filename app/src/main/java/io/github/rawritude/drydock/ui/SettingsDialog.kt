package io.github.rawritude.drydock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rawritude.drydock.R
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    settings: MainViewModel.UiSettings,
    onWifiOnlyChange: (Boolean) -> Unit,
    onConnectionsChange: (Int) -> Unit,
    onConcurrentChange: (Int) -> Unit,
    onCaptureEnabledChange: (Boolean) -> Unit,
    onCapturePatternsChange: (String) -> Unit,
    onCaptureAllowHttpChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SwitchRow(
                    titleRes = R.string.wifi_only_title,
                    summaryRes = R.string.wifi_only_summary,
                    checked = settings.wifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                )
                SliderRow(
                    titleRes = R.string.connections_title,
                    summaryRes = R.string.connections_summary,
                    value = settings.connectionsPerDownload,
                    min = 1,
                    max = 8,
                    onChange = onConnectionsChange,
                )
                SliderRow(
                    titleRes = R.string.concurrent_title,
                    summaryRes = R.string.concurrent_summary,
                    value = settings.concurrentDownloads,
                    min = 1,
                    max = 4,
                    onChange = onConcurrentChange,
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.capture_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                SwitchRow(
                    titleRes = R.string.capture_enabled_title,
                    summaryRes = R.string.capture_enabled_summary,
                    checked = settings.captureEnabled,
                    onCheckedChange = onCaptureEnabledChange,
                )
                if (settings.captureEnabled) {
                    OutlinedTextField(
                        value = settings.capturePatterns,
                        onValueChange = onCapturePatternsChange,
                        label = { Text(stringResource(R.string.capture_patterns_title)) },
                        supportingText = { Text(stringResource(R.string.capture_patterns_hint)) },
                        minLines = 3,
                    )
                    SwitchRow(
                        titleRes = R.string.capture_allow_http_title,
                        summaryRes = R.string.capture_allow_http_summary,
                        checked = settings.captureAllowHttp,
                        onCheckedChange = onCaptureAllowHttpChange,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun SwitchRow(
    titleRes: Int,
    summaryRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(summaryRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    titleRes: Int,
    summaryRes: Int,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Column {
        Text(
            "${stringResource(titleRes)}: $value",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(summaryRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
        )
    }
}

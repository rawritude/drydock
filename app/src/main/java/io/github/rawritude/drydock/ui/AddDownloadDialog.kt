package io.github.rawritude.drydock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rawritude.drydock.R
import io.github.rawritude.drydock.util.UrlSafety

@Composable
fun AddDownloadDialog(
    prefillUrl: String?,
    onDismiss: () -> Unit,
    onAdd: (
        url: String,
        fileName: String?,
        sha256: String?,
        allowInsecureHttp: Boolean,
        report: (String?) -> Unit,
    ) -> Unit,
) {
    var url by remember { mutableStateOf(prefillUrl.orEmpty()) }
    var fileName by remember { mutableStateOf("") }
    var sha256 by remember { mutableStateOf("") }
    var allowInsecureHttp by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val validation = remember(url, allowInsecureHttp) {
        if (url.isBlank()) null else UrlSafety.validate(url, allowInsecureHttp)
    }
    val canSubmit = validation is UrlSafety.Result.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_download)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it.trim()
                        serverError = null
                    },
                    label = { Text(stringResource(R.string.url_label)) },
                    placeholder = { Text(stringResource(R.string.url_hint)) },
                    singleLine = true,
                    isError = validation is UrlSafety.Result.Rejected,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                clipboard.getText()?.text?.let { text ->
                                    url = UrlSafety.firstUrlIn(text) ?: text.trim()
                                }
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                        }
                    },
                )
                (validation as? UrlSafety.Result.Rejected)?.let { rejected ->
                    Text(
                        text = rejected.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.file_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sha256,
                    onValueChange = { sha256 = it.trim() },
                    label = { Text(stringResource(R.string.sha256_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allowInsecureHttp,
                        onCheckedChange = { allowInsecureHttp = it },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.allow_http_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (allowInsecureHttp) {
                            Text(
                                stringResource(R.string.allow_http_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                serverError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onAdd(
                        url.trim(),
                        fileName.trim().ifEmpty { null },
                        sha256.trim().ifEmpty { null },
                        allowInsecureHttp,
                    ) { error ->
                        if (error == null) onDismiss() else serverError = error
                    }
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

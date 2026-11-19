@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android5.ai

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun AppLoggerCard() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val stats by produceState<AppLogger.Stats?>(null, revision) {
        value = withContext(Dispatchers.IO) { AppLogger.stats(context) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { AppLogger.export(context, uri) }
                    status = resources.getString(if (ok) R.string.app_logger_export_ok else R.string.app_logger_export_failed)
                    revision++
                } finally { busy = false }
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.app_logger_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.app_logger_description), style = MaterialTheme.typography.bodySmall)
            stats?.let { current ->
                Text(stringResource(when {
                    current.capturing -> R.string.app_logger_active
                    current.error != null -> R.string.app_logger_unavailable
                    else -> R.string.app_logger_starting
                }), style = MaterialTheme.typography.labelLarge)
                Text(current.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.app_logger_stats, current.files, Formatter.formatShortFileSize(context, current.bytes)),
                    style = MaterialTheme.typography.bodySmall)
                current.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !busy && (stats?.files ?: 0) > 0,
                    onClick = { export.launch("AIChat-logs-${BuildConfig.BUILD_NUMBER}.zip") }) { Text(stringResource(R.string.app_logger_export)) }
                OutlinedButton(enabled = !busy, onClick = { revision++ }) { Text(stringResource(R.string.app_logger_refresh)) }
            }
            TextButton(enabled = !busy && (stats?.files ?: 0) > 0, onClick = { confirmClear = true }) {
                Text(stringResource(R.string.app_logger_clear), color = if (!busy && (stats?.files ?: 0) > 0)
                    MaterialTheme.colorScheme.error else LocalContentColor.current)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.app_logger_clear)) },
        text = { Text(stringResource(R.string.app_logger_clear_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false; busy = true
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) { AppLogger.clear() }
                    status = resources.getString(if (ok) R.string.app_logger_cleared else R.string.app_logger_clear_failed)
                    revision++
                } finally { busy = false }
            }
        }) { Text(stringResource(R.string.app_logger_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } })
}

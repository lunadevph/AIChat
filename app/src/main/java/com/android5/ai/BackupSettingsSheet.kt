package com.android5.ai

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsSheet(settings: SettingsManager, onDismiss: () -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeChats by rememberSaveable { mutableStateOf(settings.backupIncludeChats) }
    var includeImages by rememberSaveable { mutableStateOf(settings.backupIncludeImages) }
    var includeKeys by rememberSaveable { mutableStateOf(settings.backupIncludeKeys) }
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var exporting by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        if (it != null) { exporting = true; pendingUri = it }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) { exporting = false; pendingUri = it }
    }
    if (pendingUri != null) BackupPasswordDialog(exporting, onDismiss = { pendingUri = null }) { password ->
        val uri = pendingUri!!
        val isExport = exporting
        pendingUri = null
        busy = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    if (isExport) BackupManager.export(context, uri, password,
                        BackupManager.ExportOptions(includeChats, includeChats && includeImages, includeKeys))
                    else BackupManager.import(context, uri, password)
                }
                restored = ok && !isExport
                status = resources.getString(if (isExport) {
                    if (ok) R.string.backup_export_ok else R.string.backup_export_failed
                } else if (ok) R.string.backup_import_ok else R.string.backup_import_failed)
            } finally { password.fill('\u0000'); busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.backup_settings_intro), style = MaterialTheme.typography.bodyMedium)
            SettingsSwitchRow(stringResource(R.string.backup_include_chats), stringResource(R.string.backup_include_chats_sub),
                Icons.Rounded.Chat, includeChats, { includeChats = it; settings.backupIncludeChats = it })
            if (includeChats) SettingsSwitchRow(stringResource(R.string.backup_include_images), stringResource(R.string.backup_include_images_sub),
                Icons.Rounded.Image, includeImages, { includeImages = it; settings.backupIncludeImages = it })
            SettingsSwitchRow(stringResource(R.string.backup_include_keys), stringResource(R.string.backup_include_keys_sub),
                Icons.Rounded.Key, includeKeys, { includeKeys = it; settings.backupIncludeKeys = it })
            Button(onClick = { export.launch("AIChat-${BuildConfig.BUILD_NUMBER}.ai") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Upload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_export_button))
            }
            OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_restore_button))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (restored) TextButton(onClick = {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }) { Text(stringResource(R.string.backup_restart)) }
        }
    }
}

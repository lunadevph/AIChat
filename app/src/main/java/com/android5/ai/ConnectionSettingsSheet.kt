@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android5.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import kotlinx.coroutines.*

@Composable
fun ApiKeyManagerSheet(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var savedOnly by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Providers.Info?>(null) }
    var adding by remember { mutableStateOf(false) }
    val auth = ChatGptAuth.get(LocalContext.current)
    val authRevision by auth.revision.collectAsState()
    val providers = remember(revision) { Providers.all(settingsManager.customProviders).filterNot { it.name == ChatGptOAuth.PROVIDER } }
    val saved = remember(revision) { providers.associate { it.name to settingsManager.hasStoredApiKey(it.name) } }
    val visible = providers.filter { (!savedOnly || saved[it.name] == true) && (query.isBlank() || it.name.contains(query.trim(), true)) }
    val active = remember(revision, authRevision) { settingsManager.selectedProvider }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 800.dp).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.connections_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.connections_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.active_provider_format, active), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { ChatGptConnectionCard(onConnected = { revision++ }) }
            item {
                Text(stringResource(R.string.connections_api_keys), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.connections_search)) }, shape = MaterialTheme.shapes.medium,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!savedOnly, { savedOnly = false }, label = { Text(stringResource(R.string.connections_all)) })
                    FilterChip(savedOnly, { savedOnly = true }, label = { Text(stringResource(R.string.connections_saved)) })
                }
            }
            if (visible.isEmpty()) item { Text(stringResource(R.string.connections_no_matches), Modifier.padding(16.dp)) }
            items(visible, key = { it.name }) { provider ->
                Surface(onClick = { editing = provider }, shape = MaterialTheme.shapes.large,
                    color = if (provider.name == active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProviderIcon(provider.name, Modifier.size(32.dp), null)
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(if (saved[provider.name] == true) R.string.connections_key_saved else R.string.connections_add_key),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (provider.name == active) Icons.Rounded.CheckCircle else if (saved[provider.name] == true) Icons.Rounded.Key else Icons.Rounded.Add,
                            null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.btn_add_provider))
            } }
        }
    }
    editing?.let { provider -> KeyEditorDialog(settingsManager, provider, onDismiss = { editing = null }, onChanged = { revision++ }) }
    if (adding) CustomProviderSheet(settingsManager, onDismiss = { adding = false }, onSaved = { name ->
        adding = false; revision++; savedOnly = false; query = ""; editing = Providers.info(name, settingsManager.customProviders)
    })
}

@Composable
private fun KeyEditorDialog(settings: SettingsManager, provider: Providers.Info, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var key by remember(provider.name) { mutableStateOf(settings.getApiKey(provider.name).orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var canSaveUntested by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteProvider by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(settings.hasStoredApiKey(provider.name)) }
    val custom = Providers.custom(settings.customProviders, provider.name) != null
    fun activate(): Boolean {
        if (!settings.hasApiKey(provider.name)) {
            failed = true; message = resources.getString(R.string.connections_saved_unreadable); return false
        }
        ChatGptAuth.get(context).cancelSignIn()
        settings.selectedProvider = provider.name
        settings.selectedModel = Providers.defaultModel(provider.name, settings.customProviders)
        onChanged()
        return true
    }
    fun store(activateAfter: Boolean) {
        if (!settings.setApiKey(provider.name, key.trim())) {
            failed = true; message = resources.getString(R.string.err_key_storage); return
        }
        saved = true; visible = false; failed = false; canSaveUntested = false
        if (activateAfter) activate() else onChanged()
        message = resources.getString(R.string.msg_key_saved)
    }
    fun fallbackIfRemoved() {
        if (settings.selectedProvider != provider.name) return
        val next = settings.getAllConfiguredProviders().firstOrNull { it != provider.name }
        if (next != null) {
            settings.selectedProvider = next
            settings.selectedModel = if (next == ChatGptOAuth.PROVIDER) ChatGptAuth.get(context).cachedModels().firstOrNull()?.id.orEmpty()
                else Providers.defaultModel(next, settings.customProviders)
        } else {
            settings.selectedProvider = "OpenRouter"
            settings.selectedModel = Providers.defaultModel("OpenRouter")
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) { visible = false; onDismiss() } },
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderIcon(provider.name, Modifier.size(28.dp), null); Text(provider.name)
        } },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Providers.baseUrl(provider.name, settings.customProviders), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.connections_key_privacy), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { key = it; message = null; canSaveUntested = false; failed = false }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.label_api_key)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(enabled = !busy, onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            stringResource(if (visible) R.string.desc_hide_api_key else R.string.desc_show_api_key))
                    } })
                if (provider.keyUrl.isNotBlank()) TextButton(enabled = !busy, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }
                        .onFailure { failed = true; message = resources.getString(R.string.connections_browser_error) }
                }) { Text(stringResource(R.string.connections_get_key)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Button(shapes = ExpressiveButtonShapes, enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; failed = false; message = null; visible = false
                    val candidate = key.trim()
                    scope.launch {
                        try {
                            when (val result = Providers.validateKey(provider.name, candidate, settings.customProviders)) {
                                Providers.KeyValidation.Valid -> store(true)
                                Providers.KeyValidation.InvalidKey -> { failed = true; message = resources.getString(R.string.connections_key_rejected) }
                                is Providers.KeyValidation.NetworkError -> {
                                    failed = true; canSaveUntested = true
                                    message = resources.getString(R.string.connections_check_unavailable, result.detail.orEmpty())
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true; message = resources.getString(R.string.connections_check_failed) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.connections_test_use)) }
                if (canSaveUntested) OutlinedButton(enabled = !busy, onClick = { store(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connections_save_untested))
                }
                if (saved) {
                    OutlinedButton(enabled = !busy, onClick = { if (activate()) onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connections_use_saved))
                    }
                    TextButton(enabled = !busy, onClick = { confirmRemove = true }) {
                        Text(stringResource(R.string.connections_remove_key), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (custom) TextButton(enabled = !busy, onClick = { confirmDeleteProvider = true }) {
                    Text(stringResource(R.string.connections_delete_provider), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { visible = false; onDismiss() }) { Text(stringResource(android.R.string.ok)) } }
    )
    if (confirmRemove || confirmDeleteProvider) AlertDialog(onDismissRequest = { confirmRemove = false; confirmDeleteProvider = false },
        title = { Text(stringResource(if (confirmDeleteProvider) R.string.connections_delete_provider else R.string.connections_remove_key)) },
        text = { Text(stringResource(if (confirmDeleteProvider) R.string.connections_delete_confirm else R.string.connections_remove_confirm, provider.name)) },
        confirmButton = { TextButton(onClick = {
            settings.setApiKey(provider.name, null)
            if (confirmDeleteProvider) settings.customProviders = settings.customProviders.filterNot { it.name == provider.name }
            fallbackIfRemoved(); onChanged(); key = ""; saved = false; visible = false
            val close = confirmDeleteProvider
            confirmRemove = false; confirmDeleteProvider = false
            if (close) onDismiss()
        }) { Text(stringResource(R.string.connections_confirm_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false; confirmDeleteProvider = false }) { Text(stringResource(android.R.string.cancel)) } })
}

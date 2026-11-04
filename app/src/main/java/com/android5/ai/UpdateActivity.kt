package com.android5.ai

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android5.ai.ui.theme.AIChatTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private sealed class UpdateState {
    data object Checking : UpdateState()
    data object Unpublished : UpdateState()
    data object Failed : UpdateState()
    data class Ready(val config: AppUpdateConfig) : UpdateState()
}
private sealed class DownloadState {
    data object Idle : DownloadState()
    data class Progress(val bytes: Long, val total: Long) : DownloadState()
    data class Ready(val file: File) : DownloadState()
    data object Failed : DownloadState()
}

class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsManager(this)
        setTheme(settings.activityThemeStyle())
        setContent {
            AIChatTheme(settings.themeMode) { UpdatesScreen(settings) { finish() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesScreen(settings: SettingsManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channel = settings.updateChannel
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var download by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var checkedAt by remember { mutableStateOf<String?>(null) }
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    var installerFailed by remember { mutableStateOf(false) }
    val downloading = download is DownloadState.Progress
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canInstall = UpdateInstaller.canInstall(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { downloadJob?.cancel() } }

    LaunchedEffect(channel, attempt) {
        generation++
        downloadJob?.cancel()
        download = DownloadState.Idle
        state = UpdateState.Checking
        installerFailed = false
        try {
            val api = Retrofit.Builder().baseUrl(UpdateChannels.BASE_URL)
                .client(OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(UpdateApi::class.java)
            val config = api.getUpdateConfig(UpdateChannels.feed(channel))
            require(UpdateChannels.accepts(config, channel, context.packageName))
            state = UpdateState.Ready(config)
            checkedAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            UpdateChecker.remember(context, config, channel)
            if (config.appLatestCodeVersion > BuildConfig.VERSION_CODE) {
                UpdateInstaller.verifiedCachedApk(context, config.appLatestVersion, config.apkSha256, channel)
                    ?.let { download = DownloadState.Ready(it) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: HttpException) {
            state = if (channel == UpdateChannels.DEBUG && e.code() == 404) UpdateState.Unpublished else UpdateState.Failed
        } catch (_: Exception) { state = UpdateState.Failed }
    }

    fun beginDownload(config: AppUpdateConfig) {
        val id = ++generation
        download = DownloadState.Progress(0, config.apkSizeBytes)
        downloadJob = scope.launch {
            try {
                val url = requireNotNull(config.downloadUrl).also { require(it.startsWith("https://")) }
                val file = UpdateInstaller.download(context, url,
                    UpdateInstaller.apkFile(context, config.appLatestVersion, channel), config.apkSha256) { bytes, total ->
                    if (generation == id) download = DownloadState.Progress(bytes, if (total > 0) total else config.apkSizeBytes)
                }
                if (generation == id) download = DownloadState.Ready(file)
            } catch (e: CancellationException) {
                if (generation == id) download = DownloadState.Idle
                throw e
            } catch (_: Exception) { if (generation == id) download = DownloadState.Failed }
        }
    }

    val ready = state as? UpdateState.Ready
    val config = ready?.config
    val newer = config != null && config.appLatestCodeVersion > BuildConfig.VERSION_CODE
    val required = newer && config != null && (config.updateRequired || config.minVersionCode > BuildConfig.VERSION_CODE)
    val channelLabel = stringResource(if (channel == UpdateChannels.DEBUG) R.string.update_channel_debug else R.string.update_channel_release)
    val statusTitle = when (state) {
        UpdateState.Checking -> R.string.updates_checking_title
        UpdateState.Failed -> R.string.updates_failed_title
        UpdateState.Unpublished -> R.string.updates_unpublished_title
        is UpdateState.Ready -> when {
            required -> R.string.updates_required_title
            newer -> R.string.updates_available_title
            else -> R.string.updates_current_title
        }
    }
    val statusBody = when (state) {
        UpdateState.Checking -> R.string.updates_checking_body
        UpdateState.Failed -> R.string.updates_failed_body
        UpdateState.Unpublished -> R.string.updates_unpublished_body
        is UpdateState.Ready -> when {
            required -> R.string.updates_required_body
            newer -> R.string.updates_available_body
            config != null && config.appLatestCodeVersion < BuildConfig.VERSION_CODE -> R.string.updates_ahead_body
            else -> R.string.updates_current_body
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_update_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.desc_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(when {
                                state == UpdateState.Failed -> Icons.Rounded.CloudOff
                                newer -> Icons.Rounded.SystemUpdate
                                else -> Icons.Rounded.Verified
                            }, null, Modifier.size(36.dp))
                            Text(stringResource(statusTitle), style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        Text(stringResource(statusBody), style = MaterialTheme.typography.bodyMedium)
                        if (state == UpdateState.Checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                        checkedAt?.takeIf { state is UpdateState.Ready }?.let {
                            Text(stringResource(R.string.updates_last_checked, it), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                UpdateSection(stringResource(R.string.update_channel_title)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        UpdateChannels.all.forEach { key ->
                            FilterChip(selected = channel == key, enabled = !downloading,
                                onClick = { if (channel != key) { settings.updateChannel = key; UpdateChecker.clear(context) } },
                                label = { Text(stringResource(if (key == UpdateChannels.DEBUG) R.string.update_channel_debug else R.string.update_channel_release)) })
                        }
                    }
                    Text(stringResource(if (channel == UpdateChannels.DEBUG) R.string.update_channel_debug_body else R.string.update_channel_release_body),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                UpdateSection(stringResource(R.string.updates_versions_title)) {
                    UpdateDetail(stringResource(R.string.update_label_installed),
                        stringResource(R.string.updates_version_build, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER))
                    UpdateDetail(stringResource(R.string.update_channel_title), channelLabel)
                    config?.let {
                        HorizontalDivider()
                        UpdateDetail(stringResource(R.string.update_label_latest), it.appLatestVersion.orEmpty())
                        it.buildNumber?.let { number -> UpdateDetail(stringResource(R.string.updates_build_label), number) }
                        if (it.apkSizeBytes > 0) UpdateDetail(stringResource(R.string.updates_size_label), Formatter.formatShortFileSize(context, it.apkSizeBytes))
                    }
                }

                if (newer && config != null) {
                    UpdateSection(stringResource(R.string.updates_install_title)) {
                        when (val current = download) {
                            is DownloadState.Progress -> {
                                Text(stringResource(R.string.update_status_downloading), style = MaterialTheme.typography.titleSmall)
                                if (current.total > 0) {
                                    val progress = (current.bytes.toFloat() / current.total).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    Text(stringResource(R.string.updates_download_bytes,
                                        Formatter.formatShortFileSize(context, current.bytes), Formatter.formatShortFileSize(context, current.total)))
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(Formatter.formatShortFileSize(context, current.bytes))
                                }
                                TextButton(onClick = { generation++; downloadJob?.cancel(); download = DownloadState.Idle }) {
                                    Text(stringResource(R.string.updates_cancel_download))
                                }
                            }
                            is DownloadState.Ready -> {
                                if (!config.apkSha256.isNullOrBlank()) Text(stringResource(R.string.updates_download_verified),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Button(onClick = {
                                    try {
                                        context.startActivity(if (canInstall) UpdateInstaller.installIntent(context, current.file)
                                            else UpdateInstaller.unknownSourcesIntent(context))
                                    } catch (_: Exception) { installerFailed = true }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.InstallMobile, null); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(if (canInstall) R.string.btn_install_update else R.string.updates_allow_install))
                                }
                                if (!canInstall) Text(stringResource(R.string.updates_install_permission), style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {
                                if (current is DownloadState.Failed) Text(stringResource(R.string.updates_download_error), color = MaterialTheme.colorScheme.error)
                                Button(onClick = { beginDownload(config) }, enabled = !config.downloadUrl.isNullOrBlank(), modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(if (current is DownloadState.Failed) R.string.updates_retry_download else R.string.btn_download_update))
                                }
                            }
                        }
                        if (installerFailed) Text(stringResource(R.string.updates_installer_error), color = MaterialTheme.colorScheme.error)
                    }
                }

                config?.let {
                    UpdateSection(stringResource(R.string.updates_notes_title)) {
                        val notes = it.changelog?.lineSequence()?.map { line -> line.trim().removePrefix("- ") }?.filter { line -> line.isNotBlank() }?.toList().orEmpty()
                        if (notes.isEmpty()) Text(stringResource(R.string.updates_no_notes), style = MaterialTheme.typography.bodyMedium)
                        notes.forEach { note ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { attempt++ }, enabled = state != UpdateState.Checking && !downloading,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.btn_check_again))
                }
            }
        }
    }
}

@Composable
private fun UpdateSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun UpdateDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

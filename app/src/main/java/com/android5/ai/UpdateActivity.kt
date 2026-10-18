package com.android5.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

private const val MIRROR_URL = "https://raw.githubusercontent.com/LunaDevPH/update-mirror/main/"

sealed class UpdateUi {
    object Loading : UpdateUi()
    data class Done(val config: AppUpdateConfig) : UpdateUi()
    data class Failed(val message: String) : UpdateUi()
}

/** Progress of the in-app update: download, then the hand-off to the installer. */
sealed class InstallUi {
    object Idle : InstallUi()
    data class Downloading(val bytes: Long, val total: Long) : InstallUi()
    data class Ready(val file: File) : InstallUi()
    data class Failed(val message: String) : InstallUi()
}

class UpdateActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                val context = LocalContext.current
                var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Loading) }
                var attempt by remember { mutableIntStateOf(0) }
                var install by remember { mutableStateOf<InstallUi>(InstallUi.Idle) }
                var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
                val scope = rememberCoroutineScope()

                // The install permission lives on a system screen, so re-read it
                // whenever we come back to the foreground.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            canInstall = UpdateInstaller.canInstall(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // An update downloaded earlier is still in the cache: offer it
                // rather than fetching it twice.
                LaunchedEffect(state) {
                    val done = state as? UpdateUi.Done ?: return@LaunchedEffect
                    if (done.config.appLatestCodeVersion <= BuildConfig.VERSION_CODE) return@LaunchedEffect
                    if (install != InstallUi.Idle) return@LaunchedEffect
                    UpdateInstaller.cachedApk(context, done.config.appLatestVersion)
                        ?.let { install = InstallUi.Ready(it) }
                }

                LaunchedEffect(attempt) {
                    state = UpdateUi.Loading
                    try {
                        val api = Retrofit.Builder()
                            .baseUrl(MIRROR_URL)
                            .client(
                                OkHttpClient.Builder()
                                    .connectTimeout(6, TimeUnit.SECONDS)
                                    .callTimeout(10, TimeUnit.SECONDS)
                                    .build()
                            )
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(UpdateApi::class.java)

                        val config = api.getUpdateConfig("mirror.json")
                        try {
                            settingsManager.googleIcon = config.googleIcon ?: settingsManager.googleIcon
                            settingsManager.openaiIcon = config.openaiIcon ?: settingsManager.openaiIcon
                            settingsManager.openrouterIcon = config.openrouterIcon ?: settingsManager.openrouterIcon
                            settingsManager.groqIcon = config.groqIcon ?: settingsManager.groqIcon
                            settingsManager.iconBaseUrl = MIRROR_URL
                        } catch (_: Exception) {}

                        state = UpdateUi.Done(config)
                    } catch (e: Exception) {
                        state = UpdateUi.Failed(e.localizedMessage.orEmpty())
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = { finish() }, shapes = ExpressiveIconButtonShapes) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.desc_back))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(16.dp))

                        // Hero icon with animated gradient ring
                        UpdateHeroIcon(state)

                        Spacer(Modifier.height(20.dp))

                        // App title
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))

                        // Current version badge
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Text(
                                stringResource(R.string.update_version_badge, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(Modifier.height(28.dp))

                        // Status card
                        AnimatedContent(
                            targetState = state,
                            transitionSpec = {
                                (fadeIn(tween(300)) + slideInVertically { it / 4 })
                                    .togetherWith(fadeOut(tween(150)))
                            },
                            label = "status"
                        ) { s ->
                            when (s) {
                                is UpdateUi.Loading -> UpdateLoadingCard()
                                is UpdateUi.Failed -> UpdateErrorCard(s.message)
                                is UpdateUi.Done -> {
                                    val isNew = s.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
                                    val required = isNew && s.config.updateRequired
                                    when {
                                        required -> UpdateRequiredCard(s.config)
                                        isNew -> UpdateAvailableCard(s.config)
                                        else -> UpdateUpToDateCard()
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // Version comparison
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            VersionInfoCard(
                                label = stringResource(R.string.update_label_installed),
                                version = BuildConfig.VERSION_NAME,
                                code = BuildConfig.VERSION_CODE.toString(),
                                icon = Icons.Rounded.PhoneAndroid,
                                modifier = Modifier.weight(1f)
                            )
                            VersionInfoCard(
                                label = stringResource(R.string.update_label_latest),
                                version = (state as? UpdateUi.Done)?.config?.appLatestVersion ?: "\u2014",
                                code = (state as? UpdateUi.Done)?.config?.appLatestCodeVersion?.toString() ?: "\u2014",
                                icon = Icons.Rounded.Cloud,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.height(24.dp))

                        // Action buttons
                        AnimatedContent(
                            targetState = state,
                            transitionSpec = {
                                (fadeIn(tween(300))).togetherWith(fadeOut(tween(150)))
                            },
                            label = "actions"
                        ) { s ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                when (s) {
                                    is UpdateUi.Loading -> {
                                        // Loading card has its own indicator
                                    }

                                    is UpdateUi.Failed -> {
                                        Button(
                                            onClick = { attempt++ },
                                            modifier = Modifier.fillMaxWidth().height(52.dp),
                                            shapes = ExpressiveButtonShapes
                                        ) {
                                            Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.btn_retry), fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    is UpdateUi.Done -> {
                                        val isNew = s.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
                                        if (isNew) {
                                            UpdateInstallActions(
                                                install = install,
                                                canInstall = canInstall,
                                                required = s.config.updateRequired,
                                                onDownload = {
                                                    val url = s.config.downloadUrl
                                                    if (url.isNullOrBlank()) {
                                                        install = InstallUi.Failed(
                                                            context.getString(R.string.update_error_unreachable)
                                                        )
                                                    } else {
                                                        install = InstallUi.Downloading(0L, -1L)
                                                        scope.launch {
                                                            try {
                                                                val file = UpdateInstaller.download(
                                                                    context = context,
                                                                    url = url,
                                                                    destination = UpdateInstaller.apkFile(
                                                                        context,
                                                                        s.config.appLatestVersion
                                                                    )
                                                                ) { written, total ->
                                                                    install = InstallUi.Downloading(written, total)
                                                                }
                                                                install = InstallUi.Ready(file)
                                                            } catch (e: Exception) {
                                                                install = InstallUi.Failed(
                                                                    e.message
                                                                        ?: e.javaClass.simpleName
                                                                )
                                                            }
                                                        }
                                                    }
                                                },
                                                onInstall = { file ->
                                                    try {
                                                        context.startActivity(
                                                            UpdateInstaller.installIntent(context, file)
                                                        )
                                                    } catch (e: Exception) {
                                                        install = InstallUi.Failed(
                                                            e.message ?: e.javaClass.simpleName
                                                        )
                                                    }
                                                },
                                                onAllowInstalls = {
                                                    try {
                                                        context.startActivity(
                                                            UpdateInstaller.unknownSourcesIntent(context)
                                                        )
                                                    } catch (_: Exception) {
                                                    }
                                                }
                                            )
                                        }
                                        TextButton(
                                            onClick = { attempt++ },
                                            shapes = ExpressiveButtonShapes
                                        ) {
                                            Icon(
                                                Icons.Rounded.Refresh,
                                                contentDescription = stringResource(R.string.desc_check_again),
                                                Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.btn_check_again))
                                        }

                                        // The in-app route can fail (no link, blocked
                                        // install); the release page always works.
                                        if (!s.config.downloadUrl.isNullOrBlank()) {
                                            TextButton(
                                                onClick = {
                                                    try {
                                                        startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse(s.config.downloadUrl)
                                                            )
                                                        )
                                                    } catch (_: Exception) {
                                                    }
                                                },
                                                shapes = ExpressiveButtonShapes
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.OpenInNew,
                                                    contentDescription = null,
                                                    Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.btn_open_browser))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/**
 * The download-and-install half of the update screen: one button until the user
 * acts, then live progress, then either the system installer or the single system
 * setting that still stands in the way.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateInstallActions(
    install: InstallUi,
    canInstall: Boolean,
    required: Boolean,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onAllowInstalls: () -> Unit
) {
    val context = LocalContext.current

    when (install) {
        is InstallUi.Idle -> {
            Button(
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shapes = ExpressiveButtonShapes,
                colors = if (required) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.desc_download_update),
                    Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (required) R.string.btn_download_required_update
                        else R.string.btn_download_update
                    ),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        is InstallUi.Downloading -> {
            val percent = if (install.total > 0) {
                ((install.bytes * 100) / install.total).toInt().coerceIn(0, 100)
            } else {
                -1
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (percent >= 0) {
                                stringResource(R.string.update_status_downloading_pct, percent)
                            } else {
                                stringResource(R.string.update_status_downloading)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = android.text.format.Formatter.formatFileSize(
                                context,
                                install.bytes
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (percent >= 0) {
                        LinearWavyProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is InstallUi.Ready -> {
            if (canInstall) {
                // Downloaded: hand straight over to the installer.
                LaunchedEffect(install.file.path) { onInstall(install.file) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.update_status_downloaded,
                            android.text.format.Formatter.formatFileSize(
                                context,
                                install.file.length()
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = { onInstall(install.file) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shapes = ExpressiveButtonShapes
                    ) {
                        Icon(
                            Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.btn_install_update),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.update_install_blocked_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.update_install_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onAllowInstalls,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shapes = ExpressiveButtonShapes
                        ) {
                            Text(stringResource(R.string.btn_open_install_settings))
                        }
                    }
                }
            }
        }

        is InstallUi.Failed -> {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.update_download_failed, install.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shapes = ExpressiveButtonShapes,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.btn_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateHeroIcon(state: UpdateUi) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation"
    )

    val isLoading = state is UpdateUi.Loading
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val icon = when (state) {
        is UpdateUi.Loading -> Icons.Rounded.Sync
        is UpdateUi.Failed -> Icons.Rounded.CloudOff
        is UpdateUi.Done -> {
            val isNew = state.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
            if (isNew) Icons.Rounded.SystemUpdate else Icons.Rounded.CheckCircle
        }
    }

    val containerColor = when (state) {
        is UpdateUi.Loading -> MaterialTheme.colorScheme.primaryContainer
        is UpdateUi.Failed -> MaterialTheme.colorScheme.errorContainer
        is UpdateUi.Done -> {
            val isNew = state.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
            if (isNew) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
        }
    }

    val iconTint = when (state) {
        is UpdateUi.Loading -> MaterialTheme.colorScheme.primary
        is UpdateUi.Failed -> MaterialTheme.colorScheme.error
        is UpdateUi.Done -> {
            val isNew = state.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
            if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        }
    }

    Box(contentAlignment = Alignment.Center) {
        // Outer gradient ring (only when loading)
        if (isLoading) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .rotate(rotation)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.0f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        ),
                        CircleShape
                    )
            )
            // Inner cutout to make ring
            Box(
                modifier = Modifier
                    .size(102.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        // Main icon container
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = containerColor.copy(alpha = if (isLoading) pulseAlpha else 0.8f),
            tonalElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = stringResource(R.string.desc_status_icon),
                    modifier = Modifier
                        .size(44.dp)
                        .then(
                            if (isLoading) Modifier.rotate(-rotation) else Modifier
                        ),
                    tint = iconTint
                )
            }
        }
    }
}

@Composable
private fun UpdateLoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.update_status_checking),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.update_status_contacting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.update_status_failed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                message.ifBlank { stringResource(R.string.update_error_unreachable) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun UpdateUpToDateCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.update_uptodate_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                stringResource(R.string.update_uptodate_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun UpdateAvailableCard(config: AppUpdateConfig) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.update_available_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                stringResource(R.string.update_available_sub, config.appLatestVersion ?: "?"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!config.changelog.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.update_whats_new),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        config.changelog.lines().filter { it.isNotBlank() }.forEach { line ->
                            val text = line.removePrefix("- ").removePrefix("* ").trim()
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    "\u2022",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRequiredCard(config: AppUpdateConfig) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Warning,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.update_required_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                stringResource(R.string.update_required_sub, config.appLatestVersion ?: "?"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            if (!config.changelog.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.update_whats_new),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(6.dp))
                        config.changelog.lines().filter { it.isNotBlank() }.forEach { line ->
                            val text = line.removePrefix("- ").removePrefix("* ").trim()
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    "\u2022",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VersionInfoCard(
    label: String,
    version: String,
    code: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 12.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                version,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                stringResource(R.string.update_label_code, code),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

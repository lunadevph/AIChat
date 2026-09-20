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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val MIRROR_URL = "https://raw.githubusercontent.com/LunaDevPH/update-mirror/main/"

sealed class UpdateUi {
    object Loading : UpdateUi()
    data class Done(val config: AppUpdateConfig) : UpdateUi()
    data class Failed(val message: String) : UpdateUi()
}

class UpdateActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                var state by remember { mutableStateOf<UpdateUi>(UpdateUi.Loading) }
                var attempt by remember { mutableIntStateOf(0) }

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
                        state = UpdateUi.Failed(e.localizedMessage ?: "Could not reach the update server.")
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = { finish() }, shapes = ExpressiveIconButtonShapes) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
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
                            "AI Chat",
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
                                "v${BuildConfig.VERSION_NAME}  \u00b7  Build ${BuildConfig.VERSION_CODE}",
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
                                label = "Installed",
                                version = BuildConfig.VERSION_NAME,
                                code = BuildConfig.VERSION_CODE.toString(),
                                icon = Icons.Rounded.PhoneAndroid,
                                modifier = Modifier.weight(1f)
                            )
                            VersionInfoCard(
                                label = "Latest",
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
                                            Text("Retry", fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    is UpdateUi.Done -> {
                                        val isNew = s.config.appLatestCodeVersion > BuildConfig.VERSION_CODE
                                        if (isNew) {
                                            Button(
                                                onClick = {
                                                    s.config.downloadUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                                        try {
                                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                        } catch (_: Exception) {}
                                                    }
                                                },
                                                enabled = s.config.updateLinkAvailable && !s.config.downloadUrl.isNullOrBlank(),
                                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                                shapes = ExpressiveButtonShapes,
                                                colors = if (s.config.updateRequired) {
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.error,
                                                        contentColor = MaterialTheme.colorScheme.onError
                                                    )
                                                } else {
                                                    ButtonDefaults.buttonColors()
                                                }
                                            ) {
                                                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    if (s.config.updateRequired) "Download Required Update" else "Download Update",
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        TextButton(
                                            onClick = { attempt++ },
                                            shapes = ExpressiveButtonShapes
                                        ) {
                                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Check Again")
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
                    contentDescription = null,
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
                "Checking for updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            )
            Text(
                "Contacting update server\u2026",
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
                "Connection Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                message,
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
                "You\u2019re up to date!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                "AI Chat is running the latest version.",
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
                "Update Available",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Version ${config.appLatestVersion ?: "?"} is ready to download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
                    "Update Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "Version ${config.appLatestVersion ?: "?"} is required to continue using the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
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
                null,
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
                "Code $code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

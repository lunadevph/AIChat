package com.android5.ai

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                var showAISettings by remember { mutableStateOf(false) }
                var showApiKeyManager by remember { mutableStateOf(false) }
                var showAboutApp by remember { mutableStateOf(false) }
                var showAppearance by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.Settings,
                                                null,
                                                Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.settings_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { goBack() }, shapes = ExpressiveIconButtonShapes) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.btn_cancel))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        SettingsSectionHeader(stringResource(R.string.settings_section_provider))
                        
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                val configuredCount = settingsManager.getAllConfiguredProviders().size
                                SettingsGroupItem(
                                    title = stringResource(R.string.settings_api_keys_title),
                                    subtitle = stringResource(R.string.settings_api_keys_subtitle),
                                    icon = Icons.Rounded.Key,
                                    badgeColor = MaterialTheme.colorScheme.primary,
                                    trailingBadge = "$configuredCount/${Providers.ALL.size}",
                                    onClick = { showApiKeyManager = true }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                                SettingsGroupItem(
                                    title = stringResource(R.string.settings_ai_title),
                                    subtitle = stringResource(R.string.settings_ai_subtitle),
                                    icon = Icons.Rounded.Psychology,
                                    badgeColor = MaterialTheme.colorScheme.secondary,
                                    onClick = { showAISettings = true }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                                SettingsGroupItem(
                                    title = stringResource(R.string.settings_appearance_title),
                                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                                    icon = Icons.Rounded.Palette,
                                    badgeColor = MaterialTheme.colorScheme.primary,
                                    onClick = { showAppearance = true }
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        SettingsSectionHeader(stringResource(R.string.settings_section_app))

                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                SettingsGroupItem(
                                    title = stringResource(R.string.settings_update_title),
                                    subtitle = stringResource(R.string.settings_update_subtitle),
                                    icon = Icons.Rounded.SystemUpdate,
                                    badgeColor = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        startActivity(Intent(this@SettingsActivity, UpdateActivity::class.java))
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                                SettingsGroupItem(
                                    title = stringResource(R.string.settings_about_title),
                                    subtitle = stringResource(R.string.settings_about_subtitle),
                                    icon = Icons.Rounded.Info,
                                    badgeColor = MaterialTheme.colorScheme.secondary,
                                    onClick = { showAboutApp = true }
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }

                    if (showApiKeyManager) {
                        ApiKeyManagerSheet(settingsManager) { showApiKeyManager = false }
                    }
                    if (showAISettings) {
                        AISettingsSheet(settingsManager) { showAISettings = false }
                    }
                    if (showAboutApp) {
                        AboutAppSheet { showAboutApp = false }
                    }
                    if (showAppearance) {
                        AppearanceSheet(settingsManager) { showAppearance = false }
                    }
                }
            }
        }
    }

    private fun goBack() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}

@Composable
fun SettingsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 12.dp, bottom = 10.dp)
            .semantics { heading() }
    )
}

@Composable
fun SettingsGroupItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeColor: Color,
    trailingBadge: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = badgeColor)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailingBadge != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = trailingBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApiKeyManagerSheet(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    var activeProvider by remember { mutableStateOf(settingsManager.selectedProvider) }
    var expandedProvider by remember { mutableStateOf<String?>(activeProvider) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    stringResource(R.string.sheet_api_keys_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sheet_api_keys_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(providerIconRes(activeProvider)),
                        contentDescription = activeProvider,
                        modifier = Modifier.size(24.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.active_provider_format, activeProvider),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.active_provider_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Providers.ALL.forEach { info ->
                var providerKey by remember(info.name) {
                    mutableStateOf(settingsManager.getApiKey(info.name) ?: "")
                }
                var keyVisible by remember { mutableStateOf(false) }
                var isValidating by remember { mutableStateOf(false) }
                var statusMessage by remember { mutableStateOf<String?>(null) }
                var isError by remember { mutableStateOf(false) }
                val isExpanded = expandedProvider == info.name
                val isCurrentActive = activeProvider == info.name
                val hasKey = settingsManager.hasApiKey(info.name)

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = if (isCurrentActive) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        if (isCurrentActive) 1.5.dp else 1.dp,
                        if (isCurrentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    tonalElevation = if (isCurrentActive) 2.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedProvider = if (isExpanded) null else info.name
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(providerIconRes(info.name)),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp).clip(CircleShape)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    info.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    info.tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))

                            // Status badge
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = when {
                                    isCurrentActive -> MaterialTheme.colorScheme.primaryContainer
                                    hasKey -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                border = if (!hasKey && !isCurrentActive) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                            ) {
                                Text(
                                    text = when {
                                        isCurrentActive -> stringResource(R.string.badge_active)
                                        hasKey -> stringResource(R.string.badge_configured)
                                        else -> stringResource(R.string.badge_not_configured)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isCurrentActive -> MaterialTheme.colorScheme.primary
                                        hasKey -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isExpanded) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(Modifier.height(14.dp))

                            OutlinedTextField(
                                value = providerKey,
                                onValueChange = {
                                    providerKey = it
                                    statusMessage = null
                                    isError = false
                                },
                                label = { Text(stringResource(R.string.label_api_key)) },
                                placeholder = { Text(info.keyPrefix + "…") },
                                singleLine = true,
                                isError = isError,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() }
                                ),
                                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                leadingIcon = {
                                    Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }, shapes = ExpressiveIconButtonShapes) {
                                        Icon(
                                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = null,
                                            Modifier.size(20.dp)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                GetKeyLink(info.name)
                                if (statusMessage != null) {
                                    Text(
                                        statusMessage!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        scope.launch {
                                            isValidating = true
                                            statusMessage = null
                                            when (val result = Providers.validateKey(info.name, providerKey.trim())) {
                                                is Providers.KeyValidation.Valid -> {
                                                    isValidating = false
                                                    settingsManager.setApiKey(info.name, providerKey.trim())
                                                    statusMessage = context.getString(R.string.msg_key_saved)
                                                    isError = false
                                                }
                                                is Providers.KeyValidation.InvalidKey -> {
                                                    isValidating = false
                                                    statusMessage = context.getString(R.string.error_invalid_key)
                                                    isError = true
                                                }
                                                is Providers.KeyValidation.NetworkError -> {
                                                    isValidating = false
                                                    statusMessage = context.getString(R.string.err_network, result.detail ?: "failed")
                                                    isError = true
                                                }
                                            }
                                        }
                                    },
                                    enabled = providerKey.isNotBlank() && !isValidating,
                                    shapes = ExpressiveButtonShapes,
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    if (isValidating) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.btn_test_save), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (!isCurrentActive) {
                                    FilledTonalButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            settingsManager.setApiKey(info.name, providerKey.trim())
                                            settingsManager.selectedProvider = info.name
                                            activeProvider = info.name
                                            statusMessage = context.getString(R.string.msg_provider_activated, info.name)
                                            isError = false
                                        },
                                        enabled = providerKey.isNotBlank() && !isValidating,
                                        shapes = ExpressiveButtonShapes,
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text(stringResource(R.string.btn_set_active), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (hasKey || providerKey.isNotBlank()) {
                                    OutlinedIconButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            settingsManager.setApiKey(info.name, null)
                                            providerKey = ""
                                            statusMessage = context.getString(R.string.msg_key_cleared)
                                            isError = false
                                        },
                                        shapes = ExpressiveIconButtonShapes,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.btn_clear_key), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        tonalElevation = 1.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    Modifier.size(22.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun ParameterSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Text(
                    format(value),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AISettingsSheet(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    var customInstructions by remember { mutableStateOf(settingsManager.customInstructions) }
    var aiMemory by remember { mutableStateOf(settingsManager.aiMemory) }
    var agentMemory by remember { mutableStateOf(settingsManager.agentMemory) }
    var showThinking by remember { mutableStateOf(settingsManager.showThinking) }
    var webSearch by remember { mutableStateOf(settingsManager.webSearchEnabled) }
    var streamingEnabled by remember { mutableStateOf(settingsManager.streamingEnabled) }
    var temperature by remember { mutableStateOf(settingsManager.temperature) }
    var topP by remember { mutableStateOf(settingsManager.topP) }
    var maxTokens by remember { mutableIntStateOf(settingsManager.maxTokens) }
    var frequencyPenalty by remember { mutableStateOf(settingsManager.frequencyPenalty) }
    var presencePenalty by remember { mutableStateOf(settingsManager.presencePenalty) }
    val clipboardManager = LocalClipboardManager.current
    var memoryToClear by remember { mutableStateOf<String?>(null) }

    val saveSettings = {
        settingsManager.customInstructions = customInstructions
        settingsManager.aiMemory = aiMemory
        settingsManager.agentMemory = agentMemory
        settingsManager.showThinking = showThinking
        settingsManager.webSearchEnabled = webSearch
        settingsManager.streamingEnabled = streamingEnabled
        settingsManager.temperature = temperature
        settingsManager.topP = topP
        settingsManager.maxTokens = maxTokens
        settingsManager.frequencyPenalty = frequencyPenalty
        settingsManager.presencePenalty = presencePenalty
    }

    if (memoryToClear != null) {
        AlertDialog(
            onDismissRequest = { memoryToClear = null },
            title = { Text(stringResource(R.string.dialog_clear_memory_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_clear_memory_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (memoryToClear == "ai") {
                            aiMemory = ""
                        } else if (memoryToClear == "agent") {
                            agentMemory = ""
                        }
                        memoryToClear = null
                    },
                    shapes = ExpressiveButtonShapes,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.btn_clear), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryToClear = null }, shapes = ExpressiveButtonShapes) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            saveSettings()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_ai_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SettingsSwitchRow(
                title = stringResource(R.string.setting_show_thinking),
                subtitle = stringResource(R.string.setting_show_thinking_sub),
                icon = Icons.Rounded.Psychology,
                checked = showThinking,
                onCheckedChange = { showThinking = it }
            )

            SettingsSwitchRow(
                title = stringResource(R.string.setting_web_search),
                subtitle = stringResource(R.string.setting_web_search_sub),
                icon = Icons.Rounded.Public,
                checked = webSearch,
                onCheckedChange = { webSearch = it }
            )

            OutlinedTextField(
                value = customInstructions,
                onValueChange = { customInstructions = it },
                label = { Text(stringResource(R.string.label_custom_instructions)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(stringResource(R.string.hint_custom_instructions)) }
            )

            // User Memory Section (Long-term instructions & user info)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.label_user_memory),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (aiMemory.isNotBlank()) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(aiMemory)) },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.btn_copy_memory),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { memoryToClear = "ai" },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.btn_clear_memory),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aiMemory,
                        onValueChange = { aiMemory = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text(stringResource(R.string.hint_user_memory)) }
                    )
                }
            }

            // AI Auto-Managed Memory Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.label_agent_memory),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (agentMemory.isNotBlank()) {
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(agentMemory)) },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.btn_copy_memory),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(
                                    onClick = { memoryToClear = "agent" },
                                    modifier = Modifier.size(48.dp),
                                    shapes = ExpressiveIconButtonShapes
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.btn_clear_memory),
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = agentMemory,
                        onValueChange = { agentMemory = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text(stringResource(R.string.hint_agent_memory)) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Streaming toggle
            SettingsSwitchRow(
                title = stringResource(R.string.setting_streaming),
                subtitle = stringResource(R.string.setting_streaming_sub),
                icon = Icons.Rounded.Speed,
                checked = streamingEnabled,
                onCheckedChange = { streamingEnabled = it }
            )

            // Model Parameters section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Tune,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.title_model_parameters),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.subtitle_model_parameters),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Temperature
                    ParameterSliderRow(
                        label = stringResource(R.string.param_temperature),
                        value = temperature,
                        valueRange = 0f..2f,
                        description = stringResource(R.string.param_temperature_desc),
                        format = { "%.2f".format(it) },
                        onValueChange = { temperature = it }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Top-P
                    ParameterSliderRow(
                        label = stringResource(R.string.param_top_p),
                        value = topP,
                        valueRange = 0f..1f,
                        description = stringResource(R.string.param_top_p_desc),
                        format = { "%.2f".format(it) },
                        onValueChange = { topP = it }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Max Tokens
                    ParameterSliderRow(
                        label = stringResource(R.string.param_max_tokens),
                        value = maxTokens.toFloat(),
                        valueRange = 256f..16384f,
                        description = stringResource(R.string.param_max_tokens_desc),
                        format = { it.toInt().toString() },
                        onValueChange = { maxTokens = it.toInt() }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Frequency Penalty
                    ParameterSliderRow(
                        label = stringResource(R.string.param_frequency_penalty),
                        value = frequencyPenalty,
                        valueRange = -2f..2f,
                        description = stringResource(R.string.param_frequency_penalty_desc),
                        format = { "%.2f".format(it) },
                        onValueChange = { frequencyPenalty = it }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Presence Penalty
                    ParameterSliderRow(
                        label = stringResource(R.string.param_presence_penalty),
                        value = presencePenalty,
                        valueRange = -2f..2f,
                        description = stringResource(R.string.param_presence_penalty_desc),
                        format = { "%.2f".format(it) },
                        onValueChange = { presencePenalty = it }
                    )

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            temperature = 0.7f
                            topP = 1.0f
                            maxTokens = 4096
                            frequencyPenalty = 0f
                            presencePenalty = 0f
                        },
                        shapes = ExpressiveButtonShapes,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_reset_defaults), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    settingsManager.customInstructions = customInstructions
                    settingsManager.aiMemory = aiMemory
                    settingsManager.agentMemory = agentMemory
                    settingsManager.showThinking = showThinking
                    settingsManager.webSearchEnabled = webSearch
                    settingsManager.streamingEnabled = streamingEnabled
                    settingsManager.temperature = temperature
                    settingsManager.topP = topP
                    settingsManager.maxTokens = maxTokens
                    settingsManager.frequencyPenalty = frequencyPenalty
                    settingsManager.presencePenalty = presencePenalty
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ExpressiveButtonShapes
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_save_ai), fontWeight = FontWeight.Bold)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutAppSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                tonalElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(stringResource(R.string.about_build_date, BuildConfig.BUILD_DATE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            
            Spacer(Modifier.height(8.dp))
            
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    val options = listOf(
        Triple("amoled", stringResource(R.string.theme_amoled), Color.Black),
        Triple("dynamic", stringResource(R.string.theme_dynamic), MaterialTheme.colorScheme.primary),
        Triple("light", stringResource(R.string.theme_light), Color.White)
    )
    val current = settingsManager.themeMode

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp)
        ) {
            Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_appearance_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            options.forEach { (key, label, swatchColor) ->
                val selected = current == key
                Surface(
                    onClick = {
                        settingsManager.themeMode = key
                        onDismiss()
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    tonalElevation = if (selected) 3.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(swatchColor, CircleShape)
                                .clip(CircleShape)
                                .then(
                                    if (key == "light" || key == "amoled") Modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                                    else Modifier
                                )
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}



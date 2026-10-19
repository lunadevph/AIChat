package com.android5.ai

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)
        setTheme(settingsManager.activityThemeStyle())

        if (settingsManager.isSetupComplete) {
            startMainActivity()
            return
        }

        setContent {
            AIChatTheme(themeMode = settingsManager.themeMode) {
                Scaffold { innerPadding ->
                    SetupScreen(
                        settingsManager = settingsManager,
                        modifier = Modifier.padding(innerPadding),
                        onComplete = { apiKey, provider, allKeys ->
                            allKeys.forEach { (p, k) ->
                                if (k.isNotBlank()) settingsManager.setApiKey(p, k)
                            }
                            settingsManager.setApiKey(provider, apiKey)
                            settingsManager.selectedProvider = provider
                            startMainActivity()
                        }
                    )
                }
            }
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupScreen(
    settingsManager: SettingsManager,
    modifier: Modifier = Modifier,
    onComplete: (String, String, Map<String, String>) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(settingsManager.selectedProvider) }
    var customProviders by remember { mutableStateOf(settingsManager.customProviders) }
    val providerKeys = remember {
        mutableStateMapOf<String, String>().apply {
            Providers.all(customProviders).forEach { p ->
                val saved = settingsManager.getApiKey(p.name)
                if (!saved.isNullOrBlank()) this[p.name] = saved
            }
        }
    }
    var apiKey by remember { mutableStateOf(providerKeys[selectedProvider] ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var networkTrouble by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val animatableOffset = remember { Animatable(0f) }
    var shakeTrigger by remember { mutableStateOf(0) }

    fun finish(key: String) {
        val trimmed = key.trim()
        if (trimmed.isNotBlank()) providerKeys[selectedProvider] = trimmed
        onComplete(trimmed, selectedProvider, providerKeys.toMap())
    }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            repeat(3) {
                animatableOffset.animateTo(15f, animationSpec = tween(50))
                animatableOffset.animateTo(-15f, animationSpec = tween(50))
            }
            animatableOffset.animateTo(0f, animationSpec = tween(50))
        }
    }

    // Hold on the confirmation for a beat so the user sees it land before the
    // chat screen replaces this one.
    LaunchedEffect(isConnected) {
        if (isConnected) {
            delay(700)
            finish(apiKey)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        AppLogo(
            size = 68.dp,
            contentDescription = stringResource(R.string.app_name)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            stringResource(R.string.setup_welcome),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        ) {
            Text(
                stringResource(R.string.setup_version_format, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        SetupSteps(current = if (isConnected) 3 else 2)

        Spacer(modifier = Modifier.height(22.dp))

        SetupSectionTitle(
            step = 1,
            title = stringResource(R.string.setup_step_provider),
            done = true
        )
        Spacer(modifier = Modifier.height(10.dp))

        ProviderPicker(
            selected = selectedProvider,
            onSelect = { newProvider ->
                if (apiKey.isNotBlank()) {
                    providerKeys[selectedProvider] = apiKey
                }
                selectedProvider = newProvider
                apiKey = providerKeys[newProvider] ?: settingsManager.getApiKey(newProvider) ?: ""
                errorText = null
                networkTrouble = false
            },
            compact = true,
            modifier = Modifier.fillMaxWidth(),
            providers = Providers.all(customProviders)
        )

        Spacer(modifier = Modifier.height(22.dp))

        SetupSectionTitle(
            step = 2,
            title = stringResource(R.string.setup_step_key),
            done = isConnected
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                providerKeys[selectedProvider] = it
                errorText = null
                networkTrouble = false
            },
            label = { Text(stringResource(R.string.label_api_key)) },
            placeholder = { Text(Providers.info(selectedProvider, customProviders).keyPrefix + "…") },
            isError = errorText != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            leadingIcon = {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                when {
                    isValidating || isConnected -> LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> IconButton(
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        shapes = ExpressiveIconButtonShapes
                    ) {
                        Icon(
                            if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (apiKeyVisible) stringResource(R.string.desc_hide_api_key) else stringResource(R.string.desc_show_api_key),
                            Modifier.size(22.dp)
                        )
                    }
                }
            },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(translationX = animatableOffset.value),
            shape = RoundedCornerShape(20.dp),
            supportingText = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (errorText != null) {
                        Text(errorText!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        GetKeyLink(selectedProvider)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.setup_key_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        val invalidKeyMsg = stringResource(R.string.error_invalid_key)
        Button(
            onClick = {
                focusManager.clearFocus()
                scope.launch {
                    isValidating = true
                    networkTrouble = false
                    when (val result = Providers.validateKey(selectedProvider, apiKey.trim(), customProviders)) {
                        is Providers.KeyValidation.Valid -> {
                            isValidating = false
                            providerKeys[selectedProvider] = apiKey.trim()
                            isConnected = true
                        }
                        is Providers.KeyValidation.InvalidKey -> {
                            isValidating = false
                            errorText = invalidKeyMsg
                            shakeTrigger++
                        }
                        is Providers.KeyValidation.NetworkError -> {
                            isValidating = false
                            errorText = context.getString(
                                R.string.err_network,
                                result.detail ?: context.getString(R.string.err_detail_connection_failed)
                            )
                            networkTrouble = true
                            shakeTrigger++
                        }
                    }
                }
            },
            enabled = apiKey.isNotBlank() && !isValidating && !isConnected,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shapes = ExpressiveButtonShapes
        ) {
            if (isValidating) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.setup_validating),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else if (isConnected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.setup_connected),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    stringResource(R.string.btn_get_started),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Offline (or blocked region): let them keep the key and start anyway —
        // the chat screen reports a connection problem far more usefully.
        if (networkTrouble) {
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = { finish(apiKey) },
                enabled = apiKey.isNotBlank(),
                shapes = ExpressiveButtonShapes
            ) {
                Text(stringResource(R.string.btn_continue_anyway))
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/** The three things setup asks for, with the one in progress lit up. */
@Composable
private fun SetupSteps(current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val steps = listOf(
            stringResource(R.string.setup_step_provider),
            stringResource(R.string.setup_step_key),
            stringResource(R.string.setup_step_chat)
        )

        steps.forEachIndexed { index, label ->
            val step = index + 1
            val active = step == current
            val done = step < current

            Surface(
                shape = RoundedCornerShape(50),
                color = when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    done -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                border = BorderStroke(
                    1.dp,
                    if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    }
                ),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (done) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            active -> MaterialTheme.colorScheme.onPrimaryContainer
                            done -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** A numbered heading for one setup step. */
@Composable
private fun SetupSectionTitle(step: Int, title: String, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    if (done) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                Text(
                    step.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}



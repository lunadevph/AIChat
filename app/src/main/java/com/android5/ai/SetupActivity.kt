package com.android5.ai

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.AIChatTheme
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes
import kotlinx.coroutines.launch

class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsManager = SettingsManager(this)

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
    val providerKeys = remember {
        mutableStateMapOf<String, String>().apply {
            Providers.ALL.forEach { p ->
                val saved = settingsManager.getApiKey(p.name)
                if (!saved.isNullOrBlank()) this[p.name] = saved
            }
        }
    }
    var apiKey by remember { mutableStateOf(providerKeys[selectedProvider] ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val animatableOffset = remember { Animatable(0f) }
    var shakeTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            repeat(3) {
                animatableOffset.animateTo(15f, animationSpec = tween(50))
                animatableOffset.animateTo(-15f, animationSpec = tween(50))
            }
            animatableOffset.animateTo(0f, animationSpec = tween(50))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            stringResource(R.string.setup_welcome),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        ) {
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            stringResource(R.string.label_provider),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))

        ProviderPicker(
            selected = selectedProvider,
            onSelect = { newProvider ->
                if (apiKey.isNotBlank()) {
                    providerKeys[selectedProvider] = apiKey
                }
                selectedProvider = newProvider
                apiKey = providerKeys[newProvider] ?: settingsManager.getApiKey(newProvider) ?: ""
                errorText = null
            },
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                providerKeys[selectedProvider] = it
                errorText = null
            },
            label = { Text(stringResource(R.string.label_api_key)) },
            placeholder = { Text(Providers.info(selectedProvider).keyPrefix + "…") },
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
                Icon(Icons.Rounded.Key, contentDescription = stringResource(R.string.label_api_key), tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                when {
                    isValidating -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    else -> IconButton(
                        onClick = { apiKeyVisible = !apiKeyVisible },
                        shapes = ExpressiveIconButtonShapes
                    ) {
                        Icon(
                            if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
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

        Spacer(modifier = Modifier.height(24.dp))

        val invalidKeyMsg = stringResource(R.string.error_invalid_key)
        val context = LocalContext.current
        Button(
            onClick = {
                focusManager.clearFocus()
                scope.launch {
                    isValidating = true
                    when (val result = Providers.validateKey(selectedProvider, apiKey.trim())) {
                        is Providers.KeyValidation.Valid -> {
                            isValidating = false
                            providerKeys[selectedProvider] = apiKey.trim()
                            onComplete(apiKey.trim(), selectedProvider, providerKeys.toMap())
                        }
                        is Providers.KeyValidation.InvalidKey -> {
                            isValidating = false
                            errorText = invalidKeyMsg
                            shakeTrigger++
                        }
                        is Providers.KeyValidation.NetworkError -> {
                            isValidating = false
                            errorText = context.getString(R.string.err_network, result.detail ?: "connection failed")
                            shakeTrigger++
                        }
                    }
                }
            },
            enabled = apiKey.isNotBlank() && !isValidating,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shapes = ExpressiveButtonShapes
        ) {
            Text(
                stringResource(R.string.btn_get_started),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}



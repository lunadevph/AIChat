package com.android5.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.ExpressiveButtonShapes
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes

/**
 * The form for a provider the user adds themselves.
 *
 * Everything past the base URL is optional: a custom provider is just another
 * OpenAI-compatible endpoint, so the app fetches its catalog from `/models` the
 * same way it does for the built-ins, and the model list here is only needed when
 * an endpoint does not serve one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CustomProviderSheet(
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var tagline by remember { mutableStateOf("") }
    var keyPrefix by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var defaultModel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    /** Stores the URL with exactly one trailing slash, as the API expects. */
    fun normalizedUrl(): String = baseUrl.trim().trimEnd('/').let {
        if (it.isEmpty()) "" else "$it/"
    }

    fun save() {
        val trimmedName = name.trim()
        val url = normalizedUrl()
        when {
            trimmedName.isBlank() ->
                error = context.getString(R.string.custom_provider_name_blank)
            Providers.ALL.any { it.name.equals(trimmedName, ignoreCase = true) } ->
                error = context.getString(R.string.custom_provider_name_builtin)
            settingsManager.customProviders.any { it.name.equals(trimmedName, ignoreCase = true) } ->
                error = context.getString(R.string.custom_provider_name_taken)
            !url.startsWith("http://") && !url.startsWith("https://") ->
                error = context.getString(R.string.custom_provider_url_invalid)
            else -> {
                settingsManager.customProviders = settingsManager.customProviders + Providers.Custom(
                    name = trimmedName,
                    baseUrl = url,
                    tagline = tagline.trim(),
                    keyPrefix = keyPrefix.trim(),
                    models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    defaultModel = defaultModel.trim().takeIf { it.isNotEmpty() }
                )
                onSaved(trimmedName)
                onDismiss()
            }
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.custom_provider_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, shapes = ExpressiveIconButtonShapes) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.desc_close))
                }
            }

            Text(
                stringResource(R.string.custom_provider_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.label_provider_name)) },
                placeholder = { Text(stringResource(R.string.custom_provider_name_hint)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; error = null },
                label = { Text(stringResource(R.string.label_provider_url)) },
                placeholder = { Text(stringResource(R.string.custom_provider_url_hint)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tagline,
                onValueChange = { tagline = it },
                label = { Text(stringResource(R.string.label_provider_tagline)) },
                placeholder = { Text(stringResource(R.string.custom_provider_tagline_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = keyPrefix,
                onValueChange = { keyPrefix = it },
                label = { Text(stringResource(R.string.label_provider_key_prefix)) },
                supportingText = { Text(stringResource(R.string.custom_provider_key_prefix_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = models,
                onValueChange = { models = it },
                label = { Text(stringResource(R.string.label_provider_models)) },
                supportingText = { Text(stringResource(R.string.custom_provider_models_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = defaultModel,
                onValueChange = { defaultModel = it },
                label = { Text(stringResource(R.string.label_provider_default_model)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    focusManager.clearFocus()
                    save()
                },
                shapes = ExpressiveButtonShapes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.btn_add_provider), fontWeight = FontWeight.Bold)
            }
        }
    }
}

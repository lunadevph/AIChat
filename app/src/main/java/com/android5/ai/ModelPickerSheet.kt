package com.android5.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android5.ai.ui.theme.ExpressiveIconButtonShapes

/**
 * The model picker.
 *
 * Its shape follows the update screen — hero mark, title, a pill stating what is
 * currently chosen — over a deliberately dense list: one line for the model and
 * one for its description, so more of the catalogue fits on screen at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelPickerSheet(
    settingsManager: SettingsManager,
    models: List<ModelInfo>,
    onSelect: (String) -> Unit,
    onProviderChange: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var visionOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var providerExpanded by remember { mutableStateOf(false) }

    val currentModelId = settingsManager.selectedModel
    val currentModel = models.find { it.id == currentModelId }
    val customProviders = settingsManager.customProviders

    val filteredModels = models.filter { model ->
        val matchesSearch =
            model.name?.contains(searchQuery, ignoreCase = true) == true ||
                model.displayName.contains(searchQuery, ignoreCase = true) ||
                model.id.contains(searchQuery, ignoreCase = true) ||
                model.description?.contains(searchQuery, ignoreCase = true) == true

        val matchesVision = !visionOnly || model.capabilities.contains("vision")

        matchesSearch && matchesVision
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                        .copy(alpha = 0.8f),
                    tonalElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(
                                providerIconRes(settingsManager.selectedProvider)
                            ),
                            contentDescription = null,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    stringResource(R.string.menu_change_model),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                // What is in use right now, in the same pill the update screen uses
                // for the running version.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = currentModel?.displayName
                            ?: stringResource(R.string.label_select_model),
                        modifier = Modifier.padding(
                            horizontal = 14.dp,
                            vertical = 5.dp
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Provider selector, so switching providers does not require a trip
                // to Settings. Changing it re-fetches the catalog.
                Text(
                    stringResource(R.string.label_provider),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(Providers.all(customProviders)) { info ->
                        FilterChip(
                            selected = info.name == settingsManager.selectedProvider,
                            onClick = {
                                if (info.name != settingsManager.selectedProvider) {
                                    settingsManager.selectedProvider = info.name
                                    onProviderChange(info.name)
                                }
                            },
                            label = { Text(info.name) },
                            leadingIcon = {
                                Image(
                                    painter = painterResource(providerIconRes(info.name)),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp).clip(CircleShape)
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.placeholder_search_models))
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                shapes = ExpressiveIconButtonShapes
                            ) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = stringResource(R.string.btn_clear_text),
                                    Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !visionOnly,
                        onClick = { visionOnly = false },
                        label = {
                            Text(
                                stringResource(R.string.label_all_models),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = if (!visionOnly) {
                            {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    FilterChip(
                        selected = visionOnly,
                        onClick = { visionOnly = true },
                        label = {
                            Text(
                                stringResource(R.string.label_vision),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = if (visionOnly) {
                            {
                                Icon(
                                    Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = pluralStringResource(
                            R.plurals.label_model_count,
                            filteredModels.size,
                            filteredModels.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filteredModels.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.empty_models_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        val selected = model.id == currentModelId

                        Surface(
                            onClick = { onSelect(model.id) },
                            shape = RoundedCornerShape(16.dp),
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                        .copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            border =
                                if (selected) {
                                    BorderStroke(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                            .copy(alpha = 0.35f)
                                    )
                                },
                            tonalElevation = if (selected) 2.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    this.role = Role.RadioButton
                                    this.selected = selected
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            model.displayName,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        if (model.capabilities.contains("vision")) {
                                            Spacer(Modifier.width(6.dp))

                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                                    .copy(alpha = 0.8f)
                                            ) {
                                                Text(
                                                    stringResource(R.string.label_vision),
                                                    modifier = Modifier.padding(
                                                        horizontal = 6.dp,
                                                        vertical = 1.dp
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }

                                    // One secondary line: the description when the
                                    // provider gives one, otherwise the raw id.
                                    val cleanDesc = model.description?.removePrefix("models/")
                                    val secondary = when {
                                        !cleanDesc.isNullOrBlank() -> cleanDesc
                                        !model.id.equals(model.displayName, ignoreCase = true) ->
                                            model.cleanId
                                        else -> null
                                    }

                                    if (secondary != null) {
                                        Text(
                                            secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                SelectionIndicator(selected, size = 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

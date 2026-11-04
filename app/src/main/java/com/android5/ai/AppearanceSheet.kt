package com.android5.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSheet(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    val theme = settingsManager.themeMode
    val style = settingsManager.bubbleStyle
    val options = listOf(
        Triple("dynamic", R.string.theme_dynamic, MaterialTheme.colorScheme.primary),
        Triple("light", R.string.theme_light, Color(0xFFE3E8F4)),
        Triple("amoled", R.string.theme_amoled, Color.Black),
        Triple("midnight", R.string.theme_midnight, Color(0xFFB9A5FF)),
        Triple("ocean", R.string.theme_ocean, Color(0xFF71CBF4)),
        Triple("forest", R.string.theme_forest, Color(0xFF8EC79B)),
        Triple("sunset", R.string.theme_sunset, Color(0xFFE59A8E)))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 760.dp),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.appearance_intro), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val user = bubbleVisuals(style, true)
                        Surface(Modifier.align(Alignment.End).widthIn(max = 230.dp), shape = user.shape,
                            color = user.color, contentColor = user.content, border = user.border) {
                            Text(stringResource(R.string.bubble_preview_user), Modifier.padding(14.dp))
                        }
                        val assistant = bubbleVisuals(style, false)
                        Surface(Modifier.align(Alignment.Start).widthIn(max = 250.dp), shape = assistant.shape,
                            color = assistant.color, contentColor = assistant.content, border = assistant.border) {
                            Text(stringResource(R.string.bubble_preview_assistant), Modifier.padding(14.dp))
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.bubble_style_title), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("outlined" to R.string.bubble_outlined, "filled" to R.string.bubble_filled,
                        "tonal" to R.string.bubble_tonal, "classic" to R.string.bubble_classic).forEach { (key, label) ->
                        FilterChip(selected = style == key, onClick = { settingsManager.bubbleStyle = key }, label = { Text(stringResource(label)) })
                    }
                }
            }
            item { Text(stringResource(R.string.appearance_colors), style = MaterialTheme.typography.titleMedium) }
            options.chunked(2).forEach { row ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (key, label, color) ->
                            val chosen = theme == key
                            Surface(onClick = { settingsManager.themeMode = key },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton; selected = chosen },
                                shape = RoundedCornerShape(18.dp),
                                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.heightIn(min = 72.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(22.dp).background(color, CircleShape))
                                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    if (chosen) SelectionIndicator(true, size = 18.dp)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

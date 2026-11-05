package com.android5.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun ToolPermissionsDialog(disabled: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_permissions_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(stringResource(R.string.tool_permissions_description), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.tool_permissions_count,
                        ToolPolicy.choices.count { ToolPolicy.isAllowed(it, true, disabled) }, ToolPolicy.choices.size),
                        modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                }
                items(ToolPolicy.choices, key = { it }) { name ->
                    val checked = ToolPolicy.isAllowed(name, true, disabled)
                    Surface(onClick = { onChange(ToolPolicy.setEnabled(disabled, name, !checked)) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(stringResource(toolTitleRes(name)), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = checked, onCheckedChange = {
                                onChange(ToolPolicy.setEnabled(disabled, name, it))
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { onChange(emptySet()) }) { Text(stringResource(R.string.tool_permissions_enable_all)) } }
    )
}

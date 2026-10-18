package com.android5.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The trailing check on a selectable row.
 *
 * The slot always occupies [size] whether or not the row is selected, so every row
 * leaves its label the same width and the selected row's text no longer wraps or
 * ellipsizes earlier than the rest. The icon is decorative on purpose — the row is
 * expected to expose its own `selected` semantics, which is what a screen reader
 * announces.
 */
@Composable
fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

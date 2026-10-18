@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android5.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's mark: the spark glyph on a soft, gradient-filled blob.
 *
 * One composable so the logo cannot drift between screens again — the drawer, the
 * top bar, the empty chat screen, setup and about all render this at different
 * sizes and nothing else. The colours come from the scheme's containers rather
 * than raw primary/tertiary so the glyph keeps its contrast against the fill in
 * both light and dark themes, and the mark repaints itself from the wallpaper
 * when dynamic colour is on.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null
) {
    // Nine-sided "cookie" from the Material shape library, matching the blob used
    // behind the empty state.
    val shape = MaterialShapes.Cookie9Sided.toShape()

    Box(
        modifier = modifier
            .size(size)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer
                    )
                ),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.55f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

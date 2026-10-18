package com.android5.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's mark: the spark glyph on a soft, rounded tile.
 *
 * One composable so the logo cannot drift between screens again — the drawer, the
 * top bar, the empty chat screen, setup and about all render this at different
 * sizes and nothing else. The tile is drawn from the scheme's containers rather
 * than raw primary/tertiary, so the glyph keeps its contrast against the fill in
 * both light and dark themes and repaints itself from the wallpaper when dynamic
 * colour is on. The corner and the glyph scale with [size], so a 30dp badge and
 * an 88dp empty-screen mark share one treatment.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = null
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.36f),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ),
        tonalElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.53f),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

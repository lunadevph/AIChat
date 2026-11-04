package com.android5.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

data class BubbleVisuals(val color: Color, val content: Color, val border: BorderStroke?, val shape: Shape)

@Composable
fun bubbleVisuals(style: String, user: Boolean, error: Boolean = false): BubbleVisuals {
    val colors = MaterialTheme.colorScheme
    val shape = if (style == "classic") RoundedCornerShape(18.dp, 18.dp,
        if (user) 4.dp else 18.dp, if (user) 18.dp else 4.dp)
        else RoundedCornerShape(if (user) 24.dp else 6.dp, if (user) 6.dp else 24.dp, 24.dp, 24.dp)
    if (error) return BubbleVisuals(colors.errorContainer, colors.onErrorContainer, BorderStroke(1.dp, colors.error), shape)
    return when (style) {
        "outlined" -> BubbleVisuals(colors.surface, colors.onSurface,
            BorderStroke(1.5.dp, if (user) colors.primary else colors.outline), shape)
        "filled" -> BubbleVisuals(if (user) colors.primary else colors.secondaryContainer,
            if (user) colors.onPrimary else colors.onSecondaryContainer, null, shape)
        "classic" -> BubbleVisuals(if (user) colors.primaryContainer else colors.surfaceContainerHighest,
            if (user) colors.onPrimaryContainer else colors.onSurface, null, shape)
        else -> BubbleVisuals(if (user) colors.primaryContainer else colors.surfaceContainerHigh,
            if (user) colors.onPrimaryContainer else colors.onSurface,
            BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)), shape)
    }
}

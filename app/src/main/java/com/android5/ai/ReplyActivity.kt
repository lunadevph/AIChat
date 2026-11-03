package com.android5.ai

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ReplyActivity(status: LoadingStatus, toolName: String?, retryIn: Int) {
    val label = when (status) {
        LoadingStatus.SEARCHING -> stringResource(R.string.status_searching)
        LoadingStatus.EXECUTING -> if (toolName != null) stringResource(R.string.activity_running_tool, toolName)
            else stringResource(R.string.status_executing)
        LoadingStatus.STREAMING -> stringResource(R.string.activity_responding)
        LoadingStatus.RATE_LIMITED -> stringResource(R.string.status_rate_limited_in, retryIn)
        else -> stringResource(R.string.activity_thinking)
    }
    val icon = when (status) {
        LoadingStatus.SEARCHING -> Icons.Rounded.Public
        LoadingStatus.EXECUTING -> Icons.Rounded.Build
        LoadingStatus.STREAMING -> Icons.Rounded.Edit
        LoadingStatus.RATE_LIMITED -> Icons.Rounded.Schedule
        else -> Icons.Rounded.MoreHoriz
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status == LoadingStatus.THINKING) ThinkingDots()
        else Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        if (status == LoadingStatus.RATE_LIMITED) Text(label, style = MaterialTheme.typography.labelLarge)
        else ShiningActivityText(label)
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking_dots")
    Row(Modifier.size(18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly) {
        repeat(3) { index ->
            val opacity by transition.animateFloat(0.25f, 1f,
                infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 140)), label = "thinking_dot_$index")
            Box(Modifier.size(3.dp).alpha(opacity).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** A moving accent across the text; Compose respects the system animation scale. */
@Composable
fun ShiningActivityText(text: String, modifier: Modifier = Modifier) {
    var width by remember { mutableFloatStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "activity_shine")
    val sweep by transition.animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "shine_position")
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val brush = Brush.linearGradient(listOf(base, MaterialTheme.colorScheme.primary, base),
        start = Offset(width * (sweep - 0.6f), 0f), end = Offset(width * (sweep + 0.6f), 0f))
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelLarge.copy(brush = brush),
        onTextLayout = { width = it.size.width.toFloat().coerceAtLeast(1f) })
}

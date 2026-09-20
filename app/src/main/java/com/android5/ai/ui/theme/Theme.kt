package com.android5.ai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
    largeIncreased = RoundedCornerShape(48.dp),
    extraLargeIncreased = RoundedCornerShape(56.dp),
    extraExtraLarge = RoundedCornerShape(64.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val ExpressiveButtonShapes = ButtonShapes(
    shape = RoundedCornerShape(20.dp),
    pressedShape = RoundedCornerShape(32.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val ExpressiveIconButtonShapes = IconButtonShapes(
    shape = CircleShape,
    pressedShape = RoundedCornerShape(20.dp)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AIChatTheme(
    themeMode: String = "dynamic",
    content: @Composable () -> Unit
) {
    val darkTheme = themeMode != "light"
    val useDynamic = themeMode == "dynamic"
    val isAmoled = themeMode == "amoled"

    val colorScheme = when {
        isAmoled -> darkColorScheme(
            primary = ExpressivePrimary,
            onPrimary = ExpressiveOnPrimary,
            primaryContainer = ExpressivePrimaryContainer,
            onPrimaryContainer = ExpressiveOnPrimaryContainer,
            secondary = ExpressiveSecondary,
            onSecondary = ExpressiveOnSecondary,
            secondaryContainer = ExpressiveSecondaryContainer,
            onSecondaryContainer = ExpressiveOnSecondaryContainer,
            tertiary = ExpressiveTertiary,
            onTertiary = ExpressiveOnTertiary,
            tertiaryContainer = ExpressiveTertiaryContainer,
            onTertiaryContainer = ExpressiveOnTertiaryContainer,
            background = AmoledBackground,
            onBackground = Color.White,
            surface = AmoledSurface,
            onSurface = Color.White,
            surfaceVariant = AmoledSurfaceVariant,
            onSurfaceVariant = Color(0xFFC4C7D4),
            surfaceContainerLowest = AmoledSurfaceContainerLowest,
            surfaceContainerLow = AmoledSurfaceContainerLow,
            surfaceContainer = AmoledSurfaceContainer,
            surfaceContainerHigh = AmoledSurfaceContainerHigh,
            surfaceContainerHighest = AmoledSurfaceContainerHighest,
            outline = AmoledOutline,
            outlineVariant = AmoledOutlineVariant
        )
        useDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = ExpressivePrimary,
            onPrimary = ExpressiveOnPrimary,
            primaryContainer = ExpressivePrimaryContainer,
            onPrimaryContainer = ExpressiveOnPrimaryContainer,
            secondary = ExpressiveSecondary,
            onSecondary = ExpressiveOnSecondary,
            secondaryContainer = ExpressiveSecondaryContainer,
            onSecondaryContainer = ExpressiveOnSecondaryContainer,
            tertiary = ExpressiveTertiary,
            onTertiary = ExpressiveOnTertiary,
            tertiaryContainer = ExpressiveTertiaryContainer,
            onTertiaryContainer = ExpressiveOnTertiaryContainer,
            surface = Color(0xFF111318),
            onSurface = Color(0xFFE2E2E9),
            surfaceVariant = Color(0xFF1C1E26),
            onSurfaceVariant = Color(0xFFC4C6D0),
            surfaceContainerLowest = Color(0xFF0C0E13),
            surfaceContainerLow = Color(0xFF171920),
            surfaceContainer = Color(0xFF1B1D24),
            surfaceContainerHigh = Color(0xFF252830),
            surfaceContainerHighest = Color(0xFF30333C)
        )
        else -> expressiveLightColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        typography = Typography,
        content = content
    )
}


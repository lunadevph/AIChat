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

/** Sharp corners, for the pre-Material appearances. */
private val SharpShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
    largeIncreased = RoundedCornerShape(12.dp),
    extraLargeIncreased = RoundedCornerShape(14.dp),
    extraExtraLarge = RoundedCornerShape(16.dp)
)

/** Tighter corners, for the compact appearance. */
private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(18.dp),
    extraLargeIncreased = RoundedCornerShape(20.dp),
    extraExtraLarge = RoundedCornerShape(24.dp)
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
    // "light" is always light, "amoled" is always dark; "dynamic" follows the
    // system light/dark setting (previously it was hard-coded to dark, so the
    // "Dynamic (System)" option never respected a light system theme).
    // The retro appearances — Old Android and Holo — are dark by nature.
    val darkTheme = when (themeMode) {
        "light" -> false
        "amoled", "old_android", "holo" -> true
        else -> isSystemInDarkTheme()
    }
    val useDynamic = themeMode == "dynamic" || themeMode == "compact"
    val isAmoled = themeMode == "amoled"
    val isOldAndroid = themeMode == "old_android"
    val isHolo = themeMode == "holo"
    val isCompact = themeMode == "compact"

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
        isOldAndroid -> darkColorScheme(
            primary = OldAndroidPrimary,
            onPrimary = OldAndroidOnPrimary,
            primaryContainer = OldAndroidPrimaryContainer,
            onPrimaryContainer = OldAndroidOnPrimaryContainer,
            secondary = OldAndroidSecondary,
            onSecondary = OldAndroidOnSecondary,
            secondaryContainer = OldAndroidSecondaryContainer,
            onSecondaryContainer = OldAndroidOnSecondaryContainer,
            tertiary = OldAndroidTertiary,
            onTertiary = OldAndroidOnTertiary,
            background = OldAndroidBackground,
            onBackground = Color.White,
            surface = OldAndroidSurface,
            onSurface = Color.White,
            surfaceVariant = OldAndroidSurfaceVariant,
            onSurfaceVariant = Color(0xFFC4C7D4),
            error = OldAndroidError,
            onError = OldAndroidOnError,
            outline = OldAndroidOutline,
            outlineVariant = OldAndroidOutlineVariant
        )
        isHolo -> darkColorScheme(
            primary = HoloPrimary,
            onPrimary = HoloOnPrimary,
            primaryContainer = HoloPrimaryContainer,
            onPrimaryContainer = HoloOnPrimaryContainer,
            secondary = HoloSecondary,
            onSecondary = HoloOnSecondary,
            secondaryContainer = HoloSecondaryContainer,
            onSecondaryContainer = HoloOnSecondaryContainer,
            tertiary = HoloTertiary,
            onTertiary = HoloOnTertiary,
            background = HoloBackground,
            onBackground = Color.White,
            surface = HoloSurface,
            onSurface = Color.White,
            surfaceVariant = HoloSurfaceVariant,
            onSurfaceVariant = Color(0xFFC4C7D4),
            error = HoloError,
            onError = HoloOnError,
            outline = HoloOutline,
            outlineVariant = HoloOutlineVariant
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
            // The status and navigation bar icons were only set for the status
            // bar, so on a light theme the navigation bar showed white glyphs on
            // a white bar. Both follow the theme now.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // The retro appearances are pre-Material, so they carry sharp corners; the
    // compact one tightens everything instead.
    val shapes = when {
        isCompact -> CompactShapes
        isOldAndroid || isHolo -> SharpShapes
        else -> ExpressiveShapes
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = if (isCompact) CompactTypography else Typography,
        content = content
    )
}


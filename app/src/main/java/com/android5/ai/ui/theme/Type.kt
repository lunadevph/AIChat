package com.android5.ai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.android5.ai.R

val GoogleSansRounded = FontFamily(
    Font(R.font.google_sans_rounded_thin, FontWeight.Thin),
    Font(R.font.google_sans_rounded_extralight, FontWeight.ExtraLight),
    Font(R.font.google_sans_rounded_light, FontWeight.Light),
    Font(R.font.google_sans_rounded_regular, FontWeight.Normal),
    Font(R.font.google_sans_rounded_medium, FontWeight.Medium),
    Font(R.font.google_sans_rounded_bold, FontWeight.Bold)
)

private val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/** A step heavier, which is the visible difference in the expressive type scale. */
private fun TextStyle.emphasized(): TextStyle = copy(fontWeight = FontWeight.Bold)

/**
 * The app's type on the Material 3 Expressive scale. The baseline styles above are
 * hand-tuned for the rounded typeface, so the "emphasized" variants they leave out
 * are derived from them instead of falling back to the stock Roboto tokens.
 */
val Typography = BaseTypography.copy(
    displayLargeEmphasized = BaseTypography.displayLarge.emphasized(),
    displayMediumEmphasized = BaseTypography.displayMedium.emphasized(),
    displaySmallEmphasized = BaseTypography.displaySmall.emphasized(),
    headlineLargeEmphasized = BaseTypography.headlineLarge.emphasized(),
    headlineMediumEmphasized = BaseTypography.headlineMedium.emphasized(),
    headlineSmallEmphasized = BaseTypography.headlineSmall.emphasized(),
    titleLargeEmphasized = BaseTypography.titleLarge.emphasized(),
    titleMediumEmphasized = BaseTypography.titleMedium.emphasized(),
    titleSmallEmphasized = BaseTypography.titleSmall.emphasized(),
    bodyLargeEmphasized = BaseTypography.bodyLarge.emphasized(),
    bodyMediumEmphasized = BaseTypography.bodyMedium.emphasized(),
    bodySmallEmphasized = BaseTypography.bodySmall.emphasized(),
    labelLargeEmphasized = BaseTypography.labelLarge.emphasized(),
    labelMediumEmphasized = BaseTypography.labelMedium.emphasized(),
    labelSmallEmphasized = BaseTypography.labelSmall.emphasized()
)

/**
 * A denser type scale for the compact appearance: the same styles, set smaller,
 * so more fits on a screen without changing the hierarchy.
 */
val CompactTypography = Typography.copy(
    headlineLarge = Typography.headlineLarge.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = Typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = Typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = Typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = Typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = Typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodyLarge = Typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = Typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodySmall = Typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelLarge = Typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = Typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelSmall = Typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
    headlineLargeEmphasized = Typography.headlineLargeEmphasized.copy(fontSize = 26.sp, lineHeight = 34.sp),
    headlineMediumEmphasized = Typography.headlineMediumEmphasized.copy(fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmallEmphasized = Typography.headlineSmallEmphasized.copy(fontSize = 20.sp, lineHeight = 28.sp),
    titleLargeEmphasized = Typography.titleLargeEmphasized.copy(fontSize = 19.sp, lineHeight = 26.sp),
    titleMediumEmphasized = Typography.titleMediumEmphasized.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmallEmphasized = Typography.titleSmallEmphasized.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodyLargeEmphasized = Typography.bodyLargeEmphasized.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMediumEmphasized = Typography.bodyMediumEmphasized.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodySmallEmphasized = Typography.bodySmallEmphasized.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelLargeEmphasized = Typography.labelLargeEmphasized.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelMediumEmphasized = Typography.labelMediumEmphasized.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelSmallEmphasized = Typography.labelSmallEmphasized.copy(fontSize = 10.sp, lineHeight = 14.sp)
)

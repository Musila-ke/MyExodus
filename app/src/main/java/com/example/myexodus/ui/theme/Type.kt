package com.example.myexodus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.myexodus.R

// Desert Dawn type pairing (2026-08-31): Work Sans — clean grotesque for UI and data
// legibility; Fraunces — warm high-character serif reserved for the hero streak numeral
// and emphasis. Both are variable TTFs (API 26+ native support).
val SansFamily = FontFamily(
    Font(R.font.work_sans, FontWeight.Normal),
    Font(R.font.work_sans, FontWeight.Medium),
    Font(R.font.work_sans, FontWeight.SemiBold),
    Font(R.font.work_sans, FontWeight.Bold),
)

val DisplayFamily = FontFamily(
    Font(R.font.fraunces, FontWeight.SemiBold),
    Font(R.font.fraunces, FontWeight.Bold),
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The same scale stepped up for larger windows — every role at once, so every screen and dialog
 * inherits it without per-screen sizes. This is a step, not a proportion: a 360dp phone gets the
 * sizes above verbatim, 600dp+ one step, 840dp+ two. Lengths stay `sp`, so the user's own
 * accessibility font scale still multiplies on top — converting to dp would freeze text at the
 * current density and silently disable that setting.
 */
internal fun scaledTypography(base: Typography, factor: Float): Typography = Typography(
    displayLarge = base.displayLarge.scaled(factor),
    displayMedium = base.displayMedium.scaled(factor),
    displaySmall = base.displaySmall.scaled(factor),
    headlineLarge = base.headlineLarge.scaled(factor),
    headlineMedium = base.headlineMedium.scaled(factor),
    headlineSmall = base.headlineSmall.scaled(factor),
    titleLarge = base.titleLarge.scaled(factor),
    titleMedium = base.titleMedium.scaled(factor),
    titleSmall = base.titleSmall.scaled(factor),
    bodyLarge = base.bodyLarge.scaled(factor),
    bodyMedium = base.bodyMedium.scaled(factor),
    bodySmall = base.bodySmall.scaled(factor),
    labelLarge = base.labelLarge.scaled(factor),
    labelMedium = base.labelMedium.scaled(factor),
    labelSmall = base.labelSmall.scaled(factor),
)

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaled(factor),
    lineHeight = lineHeight.scaled(factor),
    letterSpacing = letterSpacing.scaled(factor),
)

/** Unspecified and non-sp units pass through: an em-based style must not become sp. */
private fun TextUnit.scaled(factor: Float): TextUnit = if (isSp) (value * factor).sp else this

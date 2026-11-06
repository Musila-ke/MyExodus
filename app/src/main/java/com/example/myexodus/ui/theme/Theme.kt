package com.example.myexodus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.myexodus.settings.ThemeMode
import com.example.myexodus.ui.rememberWindowWidth
import com.example.myexodus.ui.typeScaleFactor

// Internal, not private: the home-screen widget renders from these same two schemes
// (widget/DesertDawnGlanceTheme.kt) so Desert Dawn keeps exactly one definition.
internal val LightColorScheme = lightColorScheme(
    primary = TealLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = Color(0xFF8A8075),
    onSecondary = Color.White,
    // Warm taupe container (M3 baseline purple was leaking into the nav pill, tonal buttons,
    // the partner neutral card, and progress tracks while this slot was undefined).
    secondaryContainer = Color(0xFFE4DBD0),
    onSecondaryContainer = Color(0xFF3E382F),
    tertiary = GoldLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9E4CB),
    onTertiaryContainer = Color(0xFF4A2D08),
    background = SandLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = MutedLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = ContainerLowestLight,
    surfaceContainerLow = ContainerLowLight,
    surfaceContainer = ContainerLight,
    surfaceContainerHigh = ContainerHighLight,
    surfaceContainerHighest = ContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = OnInverseSurfaceLight,
    inversePrimary = TealDark,
    scrim = Color.Black,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFF6D5CF),
    onErrorContainer = Color(0xFF5C1F16),
)

internal val DarkColorScheme = darkColorScheme(
    primary = TealDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = MutedDark,
    onSecondary = Color(0xFF201C18),
    secondaryContainer = Color(0xFF3A342C),
    onSecondaryContainer = Color(0xFFE8DFD6),
    tertiary = GoldDark,
    onTertiary = Color(0xFF3A2308),
    tertiaryContainer = Color(0xFF55360F),
    onTertiaryContainer = Color(0xFFF7D9B5),
    background = SandDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = MutedDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = ContainerLowestDark,
    surfaceContainerLow = ContainerLowDark,
    surfaceContainer = ContainerDark,
    surfaceContainerHigh = ContainerHighDark,
    surfaceContainerHighest = ContainerHighestDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = OnInverseSurfaceDark,
    inversePrimary = TealLight,
    scrim = Color.Black,
    error = ErrorDark,
    onError = Color(0xFF2E100B),
    errorContainer = Color(0xFF4A1E18),
    onErrorContainer = Color(0xFFF5C9C0),
)

/**
 * Which scheme is live, for the few surfaces whose *role* has no M3 slot that maps to both
 * schemes at once — the floating nav bar is white in light (container-lowest) but must be an
 * elevated ink surface in dark (container-high); neither role alone means "the bar".
 */
internal val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** The one place ThemeMode becomes a boolean, shared by every activity's theme root. */
@Composable
fun rememberDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Type steps up once past 600dp and twice past 840dp; the ramp itself lives in
 *  Adaptive.kt (`WindowWidth.typeScaleFactor`) so the View-world block surfaces ramp with
 *  the same numbers. Layout adapts separately (AppShell's ContentMaxWidth). */
@Composable
fun MyExodusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val widthClass = rememberWindowWidth()
    val typography = remember(widthClass) {
        scaledTypography(Typography, widthClass.typeScaleFactor)
    }
    // Brand palette is fixed (Desert Dawn) — dynamic color is intentionally off so the
    // chosen identity survives on Android 12+.
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = typography,
            content = content
        )
    }
}

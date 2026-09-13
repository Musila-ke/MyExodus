package com.example.myexodus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = TealLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2ECE8),
    onPrimaryContainer = Color(0xFF0B3A35),
    secondary = Color(0xFF8A8075),
    onSecondary = Color.White,
    tertiary = GoldLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9E4CB),
    onTertiaryContainer = Color(0xFF4A2D08),
    background = SandLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = Color(0xFFEFEAE2),
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

private val DarkColorScheme = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF0C211F),
    primaryContainer = Color(0xFF123C37),
    onPrimaryContainer = Color(0xFFC4E8E3),
    secondary = MutedDark,
    onSecondary = Color(0xFF201C18),
    tertiary = GoldDark,
    onTertiary = Color(0xFF3A2308),
    tertiaryContainer = Color(0xFF55360F),
    onTertiaryContainer = Color(0xFFF7D9B5),
    background = SandDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = Color(0xFF2A2622),
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

@Composable
fun MyExodusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Brand palette is fixed (Desert Dawn) — dynamic color is intentionally off so the
    // chosen identity survives on Android 12+.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}

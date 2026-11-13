package com.example.myexodus.ui.theme

import androidx.compose.ui.graphics.Color

// Desert Dawn palette (user-chosen 2026-08-29): warm sand neutrals, deep teal primary,
// gold-amber reserved for streaks/milestones. Light + dark variants.
val SandLight = Color(0xFFFAF8F5)
val SurfaceLight = Color(0xFFFFFFFF)
// Named because the block overlay (a plain View, outside Composition) paints the on-teal text
// and the neutral container from the same constants the scheme uses — one palette, no drift.
val OnPrimaryLight = Color.White
// Named for the same reason as OnPrimary: the block surfaces' anchor disc paints from these
// constants in the View world (BlockOverlayView) and the scheme in Compose.
val PrimaryContainerLight = Color(0xFFD2ECE8)
val OnPrimaryContainerLight = Color(0xFF0B3A35)
val SurfaceVariantLight = Color(0xFFEFEAE2)
val TextLight = Color(0xFF1D1B19)
val MutedLight = Color(0xFF6E675E)
val TealLight = Color(0xFF0F766E)
val GoldLight = Color(0xFFB45309)
val ErrorLight = Color(0xFFB3402F)

val SandDark = Color(0xFF141311)
val SurfaceDark = Color(0xFF1D1B19)
val OnPrimaryDark = Color(0xFF0C211F)
val PrimaryContainerDark = Color(0xFF123C37)
val OnPrimaryContainerDark = Color(0xFFC4E8E3)
val SurfaceVariantDark = Color(0xFF2A2622)
val TextDark = Color(0xFFF2EEE9)
val MutedDark = Color(0xFFA39B90)
val TealDark = Color(0xFF2FA598)
val GoldDark = Color(0xFFD99A3D)
val ErrorDark = Color(0xFFD97A66)

// Warm sand container ramp — the M3 surfaceContainer/surfaceDim/surfaceBright roles.
// Material's defaults for these are cold lavender-grays; leaving them undefined made the
// nav bar, dialogs and elevated cards render off-brand (2026-09-12 fix).
val ContainerLowestLight = Color(0xFFFFFFFF)
val ContainerLowLight = Color(0xFFF6F2EC)
val ContainerLight = Color(0xFFF1ECE4)
val ContainerHighLight = Color(0xFFEBE6DD)
val ContainerHighestLight = Color(0xFFE5DFD5)
val SurfaceDimLight = Color(0xFFE3DED6)
val SurfaceBrightLight = Color(0xFFFAF8F5)
val OutlineLight = Color(0xFF867E73)
val OutlineVariantLight = Color(0xFFD8D1C6)
val InverseSurfaceLight = Color(0xFF32302C)
val OnInverseSurfaceLight = Color(0xFFF4F0EA)

val ContainerLowestDark = Color(0xFF0E0D0C)
val ContainerLowDark = Color(0xFF191715)
val ContainerDark = Color(0xFF211E1C)
val ContainerHighDark = Color(0xFF2A2724)
val ContainerHighestDark = Color(0xFF34302C)
val SurfaceDimDark = Color(0xFF141311)
val SurfaceBrightDark = Color(0xFF3B3733)
val OutlineDark = Color(0xFF8F877C)
val OutlineVariantDark = Color(0xFF46403A)
val InverseSurfaceDark = Color(0xFFF2EEE9)
val OnInverseSurfaceDark = Color(0xFF32302C)

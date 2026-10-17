package com.example.myexodus.widget

import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.example.myexodus.ui.theme.ContainerLowDark
import com.example.myexodus.ui.theme.ContainerLowLight
import com.example.myexodus.ui.theme.DarkColorScheme
import com.example.myexodus.ui.theme.LightColorScheme
import com.example.myexodus.ui.theme.OutlineVariantDark
import com.example.myexodus.ui.theme.OutlineVariantLight

/**
 * Desert Dawn as a Glance theme. Built from the same two Material 3 schemes the app renders with,
 * so the widget can never drift from the app's palette and discreet/detailed copy is not the only
 * thing the two share.
 *
 * Only `background` is overridden: the widget canvas is the warm container-low rung, not the app
 * window's flat Sand (widget redesign 2026-10-17, ADR #173). On the launcher the card needs to
 * read as a raised surface over arbitrary wallpaper; the near-black SandDark drew as a muddy slab
 * and the white SandLight as a glaring sheet.
 */
internal val DesertDawnGlanceColors = ColorProviders(
    light = LightColorScheme.copy(background = ContainerLowLight),
    dark = DarkColorScheme.copy(background = ContainerLowDark),
)

/**
 * Glance 1.1.1's theme contract predates M3's `outlineVariant` role, so the hairline and the
 * progress track take the palette's real token as a day/night pair instead of overloading the
 * much stronger `outline`.
 */
internal val HairlineColor: ColorProvider =
    DayNightColorProvider(day = OutlineVariantLight, night = OutlineVariantDark)

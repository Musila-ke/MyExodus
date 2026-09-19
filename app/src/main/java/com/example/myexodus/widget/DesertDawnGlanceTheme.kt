package com.example.myexodus.widget

import androidx.glance.material3.ColorProviders
import com.example.myexodus.ui.theme.DarkColorScheme
import com.example.myexodus.ui.theme.LightColorScheme

/**
 * Desert Dawn as a Glance theme. Built from the same two Material 3 schemes the app renders with,
 * so the widget can never drift from the app's palette and discreet/detailed copy is not the only
 * thing the two share. Glance's own `widgetBackground` token is derived from these by the library.
 */
internal val DesertDawnGlanceColors = ColorProviders(
    light = LightColorScheme,
    dark = DarkColorScheme,
)

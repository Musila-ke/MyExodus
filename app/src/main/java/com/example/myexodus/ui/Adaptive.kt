package com.example.myexodus.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Material window-width classes for the phone→tablet range. Screens use this to pick paddings
 * and the shell to pick bottom bar vs rail. Window size from [LocalWindowInfo] (lint-recommended:
 * configuration dp values are inset-ambiguous and rounded). ponytail: swap to material3-adaptive's
 * `currentWindowAdaptiveInfo()` when foldables/posture matter enough to justify the extra
 * dependency + lockfile churn.
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

const val EXPANDED_RAIL_BREAKPOINT_DP = 840
const val MEDIUM_BREAKPOINT_DP = 600

@Composable
fun rememberWindowWidth(): WindowWidth {
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    return when {
        widthDp < MEDIUM_BREAKPOINT_DP -> WindowWidth.COMPACT
        widthDp < EXPANDED_RAIL_BREAKPOINT_DP -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}

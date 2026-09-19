package com.example.myexodus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Screenshot/screen-sharing protection (plan v3 W6, INV-11) for surfaces that show recovery
 * content. Window flags are per-window and the whole app shares one Activity, so "this surface
 * is secure" has to be a count of composed secure surfaces, not a screen-local flag: the
 * recovery tab, the session history, and the coaching editor each [SecureSurface] themselves
 * and MainActivity mirrors the count onto the window.
 */
object SecureSurfaces {

    private val count = MutableStateFlow(0)

    /** How many secure surfaces are currently composed; MainActivity renders >0 as FLAG_SECURE. */
    val composed: StateFlow<Int> = count.asStateFlow()

    internal fun enter() = count.update { it + 1 }

    internal fun exit() = count.update { (it - 1).coerceAtLeast(0) }
}

/** Marks the calling composition as a privacy surface; released when it leaves composition. */
@Composable
fun SecureSurface() {
    DisposableEffect(Unit) {
        SecureSurfaces.enter()
        onDispose { SecureSurfaces.exit() }
    }
}

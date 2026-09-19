package com.example.myexodus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FLAG_SECURE is a per-window flag on the one Activity, so the count is what keeps it honest:
 * it must drop back to zero when the last gated surface leaves composition (otherwise the
 * whole app stays screenshot-blocked forever) and must never go negative on a double dispose
 * (which would hide a live surface's protection behind a stale count).
 */
class SecureSurfacesTest {

    @Test
    fun countTracksComposedSurfaces_andReturnsToZero() {
        SecureSurfaces.enter()
        assertEquals("one surface must raise the count", 1, SecureSurfaces.composed.value)

        SecureSurfaces.enter()
        assertEquals("two surfaces must both be counted", 2, SecureSurfaces.composed.value)

        SecureSurfaces.exit()
        SecureSurfaces.exit()
        assertEquals("leaving composition must release the count", 0, SecureSurfaces.composed.value)
    }

    @Test
    fun exitBelowZero_isClamped() {
        SecureSurfaces.exit()

        assertEquals("a stray dispose must not go negative", 0, SecureSurfaces.composed.value)

        SecureSurfaces.enter()
        assertEquals("a real surface after a stray dispose must still count", 1, SecureSurfaces.composed.value)

        SecureSurfaces.exit()
    }
}

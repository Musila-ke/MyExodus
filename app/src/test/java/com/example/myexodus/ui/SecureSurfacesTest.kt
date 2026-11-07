package com.example.myexodus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * The counter above cannot catch a surface that never calls it. That is exactly how two
     * recovery surfaces shipped unprotected until 2026-09-22: the coaching editor and the risk
     * windows open as panes of the **Settings** tab, where nothing recovery-side is composed
     * above them, so `FLAG_SECURE` was never set and both were fully capturable — while a
     * comment asserted all three surfaces declared their own. A stale comment is not a guard;
     * this is. Scope is `*Screen.kt` hosts (the convention the package follows); non-Screen
     * hosts such as `TechniqueRunner` and `UrgeLogSheet` are outside this scan.
     */
    @Test
    fun `every recovery screen declares its own secure surface`() {
        val dir = listOf(
            File("src/main/java/com/example/myexodus/ui/recovery"),
            File("app/src/main/java/com/example/myexodus/ui/recovery"),
        ).firstOrNull { it.isDirectory }
            ?: error("recovery ui sources not found from ${File(".").absolutePath}")

        val hosts = dir.listFiles { f: File -> f.isFile && f.name.endsWith("Screen.kt") }.orEmpty()
        assertTrue("expected recovery screen hosts in ${dir.absolutePath}", hosts.isNotEmpty())

        val missing = hosts
            .filterNot { it.readText().contains("SecureSurface()") }
            .map { it.name }

        assertTrue(
            "Recovery surfaces must declare their own SecureSurface (W6/INV-11) — a parent's " +
                "composition is not a guarantee: $missing",
            missing.isEmpty(),
        )
    }
}

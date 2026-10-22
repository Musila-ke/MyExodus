package com.example.myexodus.accountability

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR #209 regression guard. On 2026-10-21 the manual §10 harness called
 * `FirebaseAuth.getInstance().signOut()` on the DEFAULT app on every launch. The default
 * anonymous uid IS the user's account (§6), so each launch rotated the production identity
 * and orphaned the live pairing — the X6532's Partner hub then read "Can't reach your
 * partner" while the pair document stayed active.
 *
 * A source guard rather than an execution test: the invariant is *which* auth instance the
 * harness holds, and the activity itself cannot run in a JVM test (biometric prompt +
 * AndroidKeyStore). Execution of the isolated path is the device leg.
 */
class PairingHarnessIsolationTest {

    @Test
    fun `the pairing harness never touches the default firebase identity`() {
        val source = listOf(
            File("src/debug/java/com/example/myexodus/accountability/PairingTestActivity.kt"),
            File("app/src/debug/java/com/example/myexodus/accountability/PairingTestActivity.kt"),
        ).firstOrNull { it.isFile }
            ?: error("pairing harness source not found from ${File(".").absolutePath}")

        val text = source.readText()

        assertFalse(
            "PairingTestActivity must never obtain the default FirebaseAuth: its anonymous uid " +
                "is the user's production account, and signing it out orphans every live pairing",
            text.contains("FirebaseAuth.getInstance()"),
        )
        assertTrue(
            "the harness must sign in on its own named FirebaseApp",
            text.contains("FirebaseAuth.getInstance(harnessApp)"),
        )
        assertTrue(
            "the harness must not build a pairing flow over the production graphs",
            !text.contains("entryPoint.pairingFlow()") && !text.contains("entryPoint.deletion()"),
        )
    }
}

package com.example.myexodus.data.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DeviceSafety's emulator assumption skips only the *test body* — JUnit still runs `@After`, so a
 * tearDown calling `RecoveryManager.resetAllData()` directly wiped the owner's live store on the
 * X6532 on 2026-09-29 (12 production wipes logged, every test body skipped). Enforced
 * mechanically: a suite that declares itself emulator-only may destroy state only through
 * `resetAllDataEmulatorOnly()`, which no-ops on a handset wherever it is called from.
 */
class DestructiveFixtureGuardTest {

    @Test
    fun `emulator-guarded suites never call resetAllData directly`() {
        val androidTestDir = listOf(
            File("src/androidTest/java"),
            File("app/src/androidTest/java"),
        ).firstOrNull { it.isDirectory }
            ?: error("androidTest sources not found from ${File(".").absolutePath}")

        val offenders = androidTestDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in ALLOWED }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { RAW_RESET.containsMatchIn(it.value) }
                    .map { "${file.name}:${it.index + 1}" }
            }
            .toList()

        assertTrue(
            "These call sites delete the production store, keys and activity log on whatever " +
                "device they run on, and neither the emulator assumption nor a @Before abort " +
                "can stop a tearDown — route them through resetAllDataEmulatorOnly(): " +
                offenders,
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** The zero-arg call only; `resetAllDataEmulatorOnly()` and paren-less prose are clean. */
        val RAW_RESET = Regex("""\.resetAllData\s*\(\s*\)""")

        /**
         * DeviceSafety.kt holds the guard's own body. EnhancedModeDeviceSuite is a physical-device
         * suite that must seed from a clean store — its wipes are intended on the phone, not an
         * oversight (ADR #122 honest limit).
         */
        val ALLOWED = setOf("DeviceSafety.kt", "EnhancedModeDeviceSuite.kt")
    }
}

package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §8 S1.3 mode resolution: debug override → build value → fail safe to `OFF`. */
class EngineModeTest {

    @Test
    fun parseOrNull_acceptsAnyCase() {
        assertEquals(EngineMode.ENFORCE, EngineMode.parseOrNull("enforce"))
        assertEquals(EngineMode.ACCOUNT, EngineMode.parseOrNull("Account"))
        assertEquals(EngineMode.OFF, EngineMode.parseOrNull("OFF"))
    }

    @Test
    fun parseOrNull_returnsNullForUnknownOrMissing() {
        assertNull(EngineMode.parseOrNull("monitor"))
        assertNull(EngineMode.parseOrNull(""))
        assertNull(EngineMode.parseOrNull(null))
    }

    @Test
    fun effectiveMode_debugOverrideWins() {
        assertEquals(
            EngineMode.OFF,
            EngineMode.effectiveMode(buildConfigValue = "ENFORCE", debugBuild = true, override = "OFF"),
        )
    }

    @Test
    fun effectiveMode_debugWithoutOverrideUsesBuildValue() {
        assertEquals(
            EngineMode.ENFORCE,
            EngineMode.effectiveMode(buildConfigValue = "ENFORCE", debugBuild = true, override = null),
        )
    }

    @Test
    fun effectiveMode_releaseIgnoresOverride() {
        assertEquals(
            EngineMode.OFF,
            EngineMode.effectiveMode(buildConfigValue = "OFF", debugBuild = false, override = "ENFORCE"),
        )
    }

    @Test
    fun effectiveMode_unrecognizedValuesNeverBecomeEnforce() {
        assertEquals(
            EngineMode.OFF,
            EngineMode.effectiveMode(buildConfigValue = "ACCOUNTING", debugBuild = false, override = null),
        )
        assertEquals(
            EngineMode.OFF,
            EngineMode.effectiveMode(buildConfigValue = null, debugBuild = true, override = "yes"),
        )
    }
}

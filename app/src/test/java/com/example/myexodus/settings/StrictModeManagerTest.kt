package com.example.myexodus.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrictModeManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var fakeNow = 1_000_000L

    private fun manager() = StrictModeManager(context, nowMillis = { fakeNow })

    @Test
    fun disabledByDefault() {
        assertFalse(manager().strictModeEnabled)
        assertTrue(manager().canDisable())
    }

    @Test
    fun enablingStarts24hCooldown() {
        val m = manager()
        m.strictModeEnabled = true
        assertTrue(m.cooldownActive)
        assertEquals(24L * 60 * 60 * 1000, m.cooldownRemainingMillis)
        assertFalse(m.canDisable())
    }

    @Test
    fun beforeExpiry_locked_evenAfterTogglingOthers() {
        val m = manager()
        m.strictModeEnabled = true
        fakeNow += 12L * 60 * 60 * 1000
        assertEquals(12L * 60 * 60 * 1000, m.cooldownRemainingMillis)
        assertFalse(m.canDisable())
    }

    @Test
    fun afterExpiry_unlocked() {
        val m = manager()
        m.strictModeEnabled = true
        fakeNow += 24L * 60 * 60 * 1000 + 1
        assertFalse(m.cooldownActive)
        assertEquals(0L, m.cooldownRemainingMillis)
        assertTrue(m.canDisable())
    }

    @Test
    fun cooldownSurvivesProcessDeath() {
        val m = manager()
        m.strictModeEnabled = true
        fakeNow += 6L * 60 * 60 * 1000
        // New instance (as after a process restart) reads the same persisted state.
        val restarted = StrictModeManager(context, nowMillis = { fakeNow })
        assertTrue(restarted.strictModeEnabled)
        assertEquals(18L * 60 * 60 * 1000, restarted.cooldownRemainingMillis)
    }

    @Test
    fun turningStrictOffClearsCooldown_afterExpiry() {
        val m = manager()
        m.strictModeEnabled = true
        fakeNow += 24L * 60 * 60 * 1000 + 1
        m.strictModeEnabled = false
        assertEquals(0L, m.cooldownRemainingMillis)
        assertTrue(m.canDisable())
    }

    @Test
    fun reenabling_resetsCooldown() {
        val m = manager()
        m.strictModeEnabled = true
        fakeNow += 24L * 60 * 60 * 1000 + 1
        m.strictModeEnabled = false
        fakeNow += 3L * 60 * 60 * 1000
        m.strictModeEnabled = true
        assertEquals(24L * 60 * 60 * 1000, m.cooldownRemainingMillis)
        assertFalse(m.canDisable())
    }
}

package com.example.myexodus.blocking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §8.2 re-enable contract (X6532 root-cause 2026-10-14): reenable() must clear the flag AND
 * advance the watermark so a pre-tap exit event can never be re-detected — the fix for the
 * "popup keeps coming back" loop, where a lost async watermark write let a days-old
 * system_server force-stop re-fire on every later start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectionStatusStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = ProtectionStatusStore(context)

    @Test
    fun reenable_clearsFlagAndAdvancesWatermark() {
        val s = store()
        s.requiresExplicitReEnable = true
        s.lastKnownGoodExitCheckEpochMillis = 5_000L
        val before = System.currentTimeMillis()

        s.reenable()

        assertFalse(s.requiresExplicitReEnable)
        assertTrue(
            "watermark must move to (at least) now — the tap settles every earlier exit",
            s.lastKnownGoodExitCheckEpochMillis >= before,
        )
    }

    @Test
    fun reenable_neverMovesWatermarkBackward() {
        // Backward clock correction: a watermark already ahead of wall-clock must stay put,
        // or records stamped before the jump start counting as "after the watermark" again.
        val s = store()
        val future = System.currentTimeMillis() + 3_600_000L
        s.lastKnownGoodExitCheckEpochMillis = future

        s.reenable()

        assertEquals(future, s.lastKnownGoodExitCheckEpochMillis)
    }

    @Test
    fun reenable_keepsDegradation() {
        // Audit finding #14: the monitor's change-gated publish owns the degradation value and
        // the posted alert; clearing it here would freeze the stale notification on screen.
        val s = store()
        s.requiresExplicitReEnable = true
        s.degradation = ProtectionDegradation.APP_STOPPED

        s.reenable()

        assertEquals(ProtectionDegradation.APP_STOPPED, s.degradation)
    }

    @Test
    fun flagAndWatermark_persistAcrossInstances() {
        // What "persist" means for the re-fire fix: the values must be readable by the next
        // process (commit()-backed — apply()'s flush could be lost to an XOS SIGKILL).
        store().apply {
            requiresExplicitReEnable = true
            lastKnownGoodExitCheckEpochMillis = 123_456L
        }

        val fresh = store()
        assertTrue(fresh.requiresExplicitReEnable)
        assertEquals(123_456L, fresh.lastKnownGoodExitCheckEpochMillis)
    }

    @Test
    fun degradationClearsToNull() {
        val s = store()
        s.degradation = ProtectionDegradation.APP_STOPPED
        s.degradation = null
        assertNull(s.degradation)
    }

    @Test
    fun defaultState_isHealthyAndUnwatermarked() {
        val s = store()
        assertFalse(s.requiresExplicitReEnable)
        assertEquals(0L, s.lastKnownGoodExitCheckEpochMillis)
        assertNull(s.degradation)
    }
}

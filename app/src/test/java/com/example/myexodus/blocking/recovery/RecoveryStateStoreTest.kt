package com.example.myexodus.blocking.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room-backed recovery session + reboot simulation (blocking-core plan §8.3, §12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryStateStoreTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var store: RecoveryStateStore
    private var clock = FakeClock(WALL, ELAPSED)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        store = RecoveryStateStore(factory)
        clock = FakeClock(WALL, ELAPSED)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun startRecovery_persistsDualClockDeadlinesAndRule() = runTest {
        val state = store.startRecovery("com.instagram.android", clock)
        assertEquals(WALL + 60_000L, state.deadlineWallClockUtcMillis)
        assertEquals(ELAPSED + 60_000L, state.deadlineElapsedRealtimeThisBoot)
        assertEquals(ELAPSED, state.bootSessionId)
        assertEquals("com.instagram.android", state.ruleId)
        assertEquals(state, factory.open()!!.recoveryStateDao().current())
    }

    @Test
    fun startRecovery_usesPolicyFrictionSeconds() = runTest {
        factory.open()!!.recoveryPolicyDao().update(120L, 900L)
        val state = store.startRecovery("com.instagram.android", clock)
        assertEquals(WALL + 120_000L, state.deadlineWallClockUtcMillis)
        assertEquals(ELAPSED + 120_000L, state.deadlineElapsedRealtimeThisBoot)
    }

    @Test
    fun simulatedReboot_switchesToWallClockRemaining() = runTest {
        store.startRecovery("com.instagram.android", clock)
        val persisted = factory.open()!!.recoveryStateDao().current()!!
        assertNotNull(persisted)

        // Simulated reboot: elapsed-realtime reset to ~0, far below the stored boot id.
        val freshElapsed = 3_000L
        val sameBoot = RecoveryTimer.sameBootSession(persisted.bootSessionId, freshElapsed)
        assertFalse(sameBoot)

        // 30s of wall time later, the wall-clock deadline is the authority: 60s - 30s = 30s.
        val remaining = RecoveryTimer.remainingSeconds(
            persisted.deadlineWallClockUtcMillis,
            persisted.deadlineElapsedRealtimeThisBoot,
            sameBoot,
            nowWallClockUtcMillis = WALL + 30_000L,
            nowElapsedRealtimeMillis = freshElapsed,
        )
        assertEquals(30L, remaining)
    }

    @Test
    fun recordOverride_thenCooldownGatesEligibility() = runTest {
        // No recovery session yet: override eligible (nothing to gate).
        assertTrue(store.overrideEligible(clock))

        store.startRecovery("com.instagram.android", clock)
        store.recordOverride(clock)

        clock.wall = WALL + 599_000L
        assertFalse("Override must not be eligible inside the 600s cooldown", store.overrideEligible(clock))
        clock.wall = WALL + 600_000L
        assertTrue("Override eligible again at cooldown expiry", store.overrideEligible(clock))
    }

    private class FakeClock(var wall: Long, var elapsed: Long) : RecoveryClock {
        override fun nowWallClockUtcMillis(): Long = wall
        override fun nowElapsedRealtimeMillis(): Long = elapsed
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    companion object {
        private const val WALL = 1_800_000_000_000L
        private const val ELAPSED = 100_000L
    }
}

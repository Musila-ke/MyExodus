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
        assertEquals(state, factory.open()!!.recoveryStateDao().forRule("com.instagram.android"))
    }

    @Test
    fun sessions_areIndependentPerRule() = runTest {
        val instagram = store.startRecovery("com.instagram.android", clock)
        clock.wall = WALL + 60_000L
        clock.elapsed = ELAPSED + 60_000L
        val tiktok = store.startRecovery("com.tiktok.android", clock)

        // The second start must not have replaced or restarted the first rule's session.
        assertEquals(
            listOf(instagram, tiktok),
            store.sessions().sortedBy { it.ruleId },
        )
        assertEquals(WALL + 60_000L, instagram.deadlineWallClockUtcMillis)
        assertEquals(WALL + 120_000L, tiktok.deadlineWallClockUtcMillis)
    }

    @Test
    fun clear_removesOnlyThatRulesSession() = runTest {
        store.startRecovery("com.instagram.android", clock)
        store.startRecovery("com.tiktok.android", clock)

        store.clear("com.instagram.android")

        assertEquals(
            listOf("com.tiktok.android"),
            store.sessions().map { it.ruleId },
        )
    }

    @Test
    fun startRecovery_usesPolicyFrictionSeconds() = runTest {
        factory.open()!!.recoveryPolicyDao().update(120L, 900L)
        val state = store.startRecovery("com.instagram.android", clock)
        assertEquals(WALL + 120_000L, state.deadlineWallClockUtcMillis)
        assertEquals(ELAPSED + 120_000L, state.deadlineElapsedRealtimeThisBoot)
    }

    @Test
    fun frictionSeconds_defaultsToSixty() = runTest {
        assertEquals(60L, store.frictionSeconds())
    }

    @Test
    fun setFrictionSeconds_drivesTheNextSession() = runTest {
        store.setFrictionSeconds(8 * 3600L)
        assertEquals(8 * 3600L, store.frictionSeconds())
        val state = store.startRecovery("com.instagram.android", clock)
        assertEquals(WALL + 8 * 3600_000L, state.deadlineWallClockUtcMillis)
    }

    @Test
    fun setFrictionSeconds_clampsToTheOwnerRange() = runTest {
        store.setFrictionSeconds(0L)
        assertEquals(60L, store.frictionSeconds())
        store.setFrictionSeconds(48 * 3600L)
        assertEquals(24 * 3600L, store.frictionSeconds())
    }

    @Test
    fun setFrictionSeconds_keepsTheOverrideCooldown() = runTest {
        factory.open()!!.recoveryPolicyDao().update(120L, 900L)
        store.setFrictionSeconds(300L)
        val policy = factory.open()!!.recoveryPolicyDao().current()!!
        assertEquals(300L, policy.defaultFrictionSeconds)
        assertEquals(900L, policy.overrideCooldownSeconds)
    }

    @Test
    fun simulatedReboot_switchesToWallClockRemaining() = runTest {
        store.startRecovery("com.instagram.android", clock)
        val persisted = factory.open()!!.recoveryStateDao().forRule("com.instagram.android")!!
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

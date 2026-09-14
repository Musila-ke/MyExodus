package com.example.myexodus.ui.recovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.BlockDecisionEngine
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.blocking.recovery.RecoveryStateStore
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RestrictionRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unlocking must consume the rule's recovery session. A spent session row left in the DB
 *  makes the next blocker launch for the same rule read remaining == 0 and auto-unlock the
 *  re-armed rule — a zero-friction bypass caught on-device 2026-09-13 (blocker flashed and
 *  vanished, rule expired again). This test fails if [unlockRule] stops clearing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryUnlockTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var store: RecoveryStateStore
    private lateinit var repository: RulesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        store = RecoveryStateStore(factory)
        repository = RulesRepository(factory, RulesSnapshotPublisher(context, factory))
    }

    @After
    fun tearDown() = recovery.resetAllData()

    @Test
    fun unlockRule_expiresTheRuleAndConsumesTheSession() = runTest {
        val profile = repository.ensureActiveProfile()
        val rule = RestrictionRule(
            ruleId = "r-unlock",
            profileId = profile.userId,
            blockedPackage = "com.ichi2.anki",
            targetType = "APP",
            isAlwaysOn = true,
            dailyLimitMs = 0L,
            timeRangeStart = null,
            timeRangeEnd = null,
            activeDaysOfWeek = "1111111",
            timezone = "UTC",
            blockExpiresAt = 0L,
            severityLevel = "HIGH",
        )
        repository.insertRule(rule)
        store.startRecovery(rule.ruleId)

        unlockRule(repository, store, rule.ruleId)

        val stored = repository.getActiveRules().single { it.ruleId == rule.ruleId }
        assertFalse(
            "Rule 3 expiry must lift the block",
            BlockDecisionEngine.blocksNow(
                stored.isAlwaysOn, stored.dailyLimitMs, stored.timeRangeStart, stored.timeRangeEnd,
                stored.activeDaysOfWeek, stored.timezone, stored.blockExpiresAt, System.currentTimeMillis(),
            ),
        )
        assertNull(
            "Session must be consumed — a spent row auto-unlocks the next blocker launch",
            store.current(),
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

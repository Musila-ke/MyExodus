package com.example.myexodus.accountability.monitoring

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7 Step 7 receive half: a verified monitoring contract body becomes the recipient-local
 * snapshot — wholesale state, replay-guarded, and never routed to notifications (the caller
 * owns that branch). Non-monitoring contract kinds are not this class's business.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MonitoringSnapshotReceiverTest {

    private lateinit var context: Context
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private var nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val health = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), health,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, health)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    private fun receiver() = MonitoringSnapshotReceiver(storeFactory = factory, now = { nowMs })

    private fun event(
        sequence: Long = 4,
        latest: Long = 9,
        state: MonitoringState = MonitoringState.DEGRADED,
        bucket: OutboxAgeBucket = OutboxAgeBucket.ONE_TO_SIX_HOURS,
    ) = AccountabilityEventWire.monitoring(
        eventId = AccountabilityEventWire.newEventId(),
        sequence = sequence,
        category = AccountabilityEventCategory.DEVICE_STATUS_CHANGED,
        occurredAtMillis = nowMs,
        state = state,
        outboxAge = bucket,
        latestSequence = latest,
    )

    @Test
    fun monitoringEvent_becomesTheRecipientSnapshot() = runTest {
        assertTrue(receiver().onEvent("pair-1", "gen-a", event()))

        val row = factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!
        assertEquals(MonitoringState.DEGRADED.wire, row.monitoringState)
        assertEquals(OutboxAgeBucket.ONE_TO_SIX_HOURS.wire, row.outboxAgeBucket)
        assertEquals("gen-a", row.snapshotInstallGeneration)
        assertEquals(9L, row.latestRelationshipSeq)
        assertEquals(4L, row.snapshotEventSequence)
        assertEquals(AccountabilityEventWire.minuteOf(nowMs), row.occurredAtMinute)
        assertEquals(nowMs, row.receivedAtMs)
    }

    @Test
    fun nonMonitoringKinds_areIgnored() = runTest {
        val accountability = AccountabilityEventWire.accountability(
            eventId = AccountabilityEventWire.newEventId(),
            sequence = 1,
            category = AccountabilityEventCategory.BLOCKED_APP_ATTEMPT,
            occurredAtMillis = nowMs,
        )

        assertFalse(receiver().onEvent("pair-1", "gen-a", accountability))
        assertNull(factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1"))
    }

    @Test
    fun replayedOlderSnapshot_neverRollsTheCardBackwards() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, state = MonitoringState.ACTIVE))

        assertFalse(receiver.onEvent("pair-1", "gen-a", event(sequence = 3, state = MonitoringState.UNAVAILABLE)))
        assertEquals(
            MonitoringState.ACTIVE.wire,
            factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!.monitoringState,
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

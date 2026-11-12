package com.example.myexodus.accountability.monitoring

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.MonitoringCapability
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
import org.junit.Assert.assertNull
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
        capabilities: Set<MonitoringCapability>? = null,
    ) = AccountabilityEventWire.monitoring(
        eventId = AccountabilityEventWire.newEventId(),
        sequence = sequence,
        category = AccountabilityEventCategory.DEVICE_STATUS_CHANGED,
        occurredAtMillis = nowMs,
        state = state,
        outboxAge = bucket,
        latestSequence = latest,
        capabilities = capabilities,
    )

    @Test
    fun monitoringEvent_becomesTheRecipientSnapshot() = runTest {
        assertEquals(
            emptySet<MonitoringCapability>(),
            receiver().onEvent("pair-1", "gen-a", event()),
        )

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

        assertEquals(emptySet<MonitoringCapability>(), receiver().onEvent("pair-1", "gen-a", accountability))
        assertNull(factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1"))
    }

    @Test
    fun replayedOlderSnapshot_neverRollsTheCardBackwards() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, state = MonitoringState.ACTIVE))

        assertEquals(
            emptySet<MonitoringCapability>(),
            receiver.onEvent("pair-1", "gen-a", event(sequence = 3, state = MonitoringState.UNAVAILABLE)),
        )
        assertEquals(
            MonitoringState.ACTIVE.wire,
            factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!.monitoringState,
        )
    }

    /** §13.3 staged snapshots carry placeholder sequences in the body; the caller's
     *  override is the row's real order in the shared sequence space. */
    @Test
    fun stagedSnapshot_usesTheCallerSequenceOverride() = runTest {
        val receiver = receiver()
        val body = event(sequence = 1, latest = 1, state = MonitoringState.ACTIVE)

        receiver.onEvent("pair-1", "install-a", body, sequenceOverride = 1_500L)
        var row = factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!
        assertEquals(1_500L, row.snapshotEventSequence)
        assertEquals(1_500L, row.latestRelationshipSeq)

        assertEquals(
            "an older staged snapshot never rolls the card backwards",
            emptySet<MonitoringCapability>(),
            receiver.onEvent(
                "pair-1", "install-a",
                event(sequence = 1, latest = 1, state = MonitoringState.UNAVAILABLE),
                sequenceOverride = 1_400L,
            ),
        )
        row = factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!
        assertEquals(MonitoringState.ACTIVE.wire, row.monitoringState)
    }

    /** Owner directive 2026-11-10: the transition the alert rides on is `before - after`. */
    @Test
    fun lostGrants_areReportedAndTheNewSetIsStored() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, capabilities = ALL_GRANTS))

        val lost = receiver.onEvent(
            "pair-1",
            "gen-a",
            event(sequence = 5, capabilities = setOf(MonitoringCapability.ACCESSIBILITY)),
        )

        assertEquals(setOf(MonitoringCapability.USAGE_ACCESS, MonitoringCapability.OVERLAY,
            MonitoringCapability.WEBSITE_FILTER), lost)
        assertEquals(
            MonitoringCapability.encodeSet(setOf(MonitoringCapability.ACCESSIBILITY)),
            factory.open()!!.partnerMonitoringSnapshotDao().byPair("pair-1")!!.capabilities,
        )
    }

    @Test
    fun grantedBack_thenLostAgain_reportsTheSecondLoss() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, capabilities = ALL_GRANTS))
        receiver.onEvent(
            "pair-1", "gen-a", event(sequence = 5, capabilities = ALL_GRANTS), // no change
        )
        receiver.onEvent("pair-1", "gen-a", event(sequence = 6, capabilities = emptySet()))

        assertEquals(
            emptySet<MonitoringCapability>(),
            receiver.onEvent("pair-1", "gen-a", event(sequence = 7, capabilities = ALL_GRANTS)),
        )
        assertEquals(
            ALL_GRANTS,
            receiver.onEvent("pair-1", "gen-a", event(sequence = 8, capabilities = emptySet())),
        )
    }

    /** A re-pair's first snapshot arrives under a new install generation — it must not read
     *  as a loss against the previous install's grants. */
    @Test
    fun newInstallGeneration_neverReportsALoss() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, capabilities = ALL_GRANTS))

        val lost = receiver.onEvent(
            "pair-1", "gen-b", event(sequence = 1, capabilities = emptySet()),
        )

        assertEquals(emptySet<MonitoringCapability>(), lost)
    }

    /** A pre-v3 sender states no grants: null on either side is never a loss. */
    @Test
    fun grantlessSender_neverReportsALoss() = runTest {
        val receiver = receiver()
        receiver.onEvent("pair-1", "gen-a", event(sequence = 4, capabilities = null))

        val lost = receiver.onEvent(
            "pair-1", "gen-a", event(sequence = 5, capabilities = emptySet()),
        )

        assertEquals(emptySet<MonitoringCapability>(), lost)
    }

    private companion object {
        val ALL_GRANTS = MonitoringCapability.entries.toSet()
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

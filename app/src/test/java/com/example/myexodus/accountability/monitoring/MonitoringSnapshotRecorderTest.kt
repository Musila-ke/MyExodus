package com.example.myexodus.accountability.monitoring

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.AccountabilityEventCategory
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.serialization.OutboxAgeBucket
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.Outbox
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.PairProfileSharing
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7 Step 7 sender half (plan §4.7 / §8.9 / §8.13): the hourly-or-on-change journal, the
 * consent gate, the truthful state mapping, and the outbox-age bucket. The recorder is the
 * only writer of the Step 4 journal's monitoring rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MonitoringSnapshotRecorderTest {

    private lateinit var context: Context
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AccountabilityDatabase

    private var nowMs = START
    private var liveness: MonitoringLiveness = MonitoringLiveness.Confirmed(ProtectionAuthority.FULL)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val health = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), health,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, health)
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, "recorder-${System.nanoTime()}.db")
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        recovery.resetAllData()
    }

    private fun recorder() = MonitoringSnapshotRecorder(
        storeFactory = factory,
        outbox = db,
        liveness = { liveness },
        now = { nowMs },
    )

    private suspend fun consent(shareAvailability: Boolean = true) {
        factory.open()!!.pairSharingDao().upsertProfile(
            PairProfileSharing(pairId = "pair-1", shareAvailability = shareAvailability),
        )
    }

    private suspend fun journalRows() =
        factory.open()!!.partnerEventJournalDao().dueForDelivery(0, nowMs, 10)

    private suspend fun lastState() = factory.open()!!.partnerLivenessDao().checkin()?.lastState

    @Test
    fun firstRecord_journalsTheObservedState() = runTest {
        consent()

        assertTrue(recorder().record())

        val row = journalRows().single()
        assertEquals(AccountabilityEventCategory.DEVICE_STATUS_CHANGED.wire, row.category)
        assertEquals(MonitoringState.ACTIVE.wire, row.monitoringState)
        assertEquals(OutboxAgeBucket.UNDER_1H.wire, row.outboxAgeBucket)
        assertEquals(MonitoringState.ACTIVE.wire, lastState())
        assertEquals(nowMs, factory.open()!!.partnerLivenessDao().checkin()!!.lastRecordedAtMs)
    }

    @Test
    fun withoutConsent_theDebounceAdvancesButNothingIsJournaled() = runTest {
        val recorder = recorder()

        assertFalse(recorder.record())
        assertTrue(journalRows().isEmpty())
        assertEquals("the state was still observed", MonitoringState.ACTIVE.wire, lastState())

        consent()
        nowMs += 60_000
        assertFalse("an enable must not replay a transition seen while off", recorder.record())
        assertTrue(journalRows().isEmpty())
    }

    @Test
    fun stateChange_journalsImmediately_andRecoveryIsAPermissionChange() = runTest {
        consent()
        val recorder = recorder()
        recorder.record()

        liveness = MonitoringLiveness.ServiceAbsent
        nowMs += 60_000
        assertTrue("a change does not wait for the hour", recorder.record())
        val lost = journalRows().first { it.monitoringState == MonitoringState.UNAVAILABLE.wire }
        assertEquals(
            "coverage lost is a component-availability event",
            AccountabilityEventCategory.MONITORING_COMPONENT_UNAVAILABLE.wire,
            lost.category,
        )

        liveness = MonitoringLiveness.Confirmed(ProtectionAuthority.FULL)
        nowMs += 60_000
        assertTrue(recorder.record())
        val restored = journalRows().first {
            it.monitoringState == MonitoringState.ACTIVE.wire &&
                it.category == AccountabilityEventCategory.MONITORING_PERMISSION_CHANGED.wire
        }
        assertEquals(MonitoringState.ACTIVE.wire, restored.monitoringState)
    }

    @Test
    fun steadyState_journalsOncePerHour() = runTest {
        consent()
        val recorder = recorder()
        recorder.record()

        nowMs += MonitoringSnapshotRecorder.SNAPSHOT_INTERVAL_MILLIS - 1
        assertFalse(recorder.record())
        assertEquals(1, journalRows().size)

        nowMs += 1
        assertTrue(recorder.record())
        assertEquals(2, journalRows().size)
    }

    @Test
    fun force_journalsInsideTheInterval() = runTest {
        consent()
        val recorder = recorder()
        recorder.record()

        nowMs += 1_000
        assertTrue(recorder.record(force = true))
        assertEquals(2, journalRows().size)
    }

    /** §8.13 line 470: an alive-but-unverifiable service is UNKNOWN — never a claimed ACTIVE. */
    @Test
    fun unverifiableLiveness_isJournaledAsUnknown() = runTest {
        consent()
        liveness = MonitoringLiveness.Unverifiable

        recorder().record()

        assertEquals(MonitoringState.UNKNOWN.wire, journalRows().single().monitoringState)
    }

    @Test
    fun degradedAuthorities_mapToDegradedAndUnavailable() = runTest {
        consent()
        liveness = MonitoringLiveness.Confirmed(ProtectionAuthority.DEGRADED_POLLING_ONLY)
        recorder().record()
        assertEquals(MonitoringState.DEGRADED.wire, journalRows().single().monitoringState)

        liveness = MonitoringLiveness.Confirmed(ProtectionAuthority.DEGRADED_NO_DETECTION)
        nowMs += 1_000
        recorder().record()
        assertEquals(
            MonitoringState.UNAVAILABLE.wire,
            journalRows().first { it.monitoringState == MonitoringState.UNAVAILABLE.wire }.monitoringState,
        )
    }

    @Test
    fun outboxAgeBucket_usesTheOldestPendingRow() = runTest {
        consent()
        db.outboxDao().insert(
            Outbox(
                messageId = "m-old",
                eventId = 1,
                envelopeBytes = ByteArray(4),
                status = Outbox.STATUS_PENDING,
                createdAtEpochMillis = nowMs - 7L * 60 * 60 * 1000,
            ),
        )
        db.outboxDao().insert(
            Outbox(
                messageId = "m-new",
                eventId = 2,
                envelopeBytes = ByteArray(4),
                status = Outbox.STATUS_PENDING,
                createdAtEpochMillis = nowMs - 60_000,
            ),
        )

        recorder().record()

        assertEquals(
            "the oldest pending row is the bucket's basis",
            OutboxAgeBucket.SIX_TO_TWENTY_FOUR_HOURS.wire,
            journalRows().single().outboxAgeBucket,
        )
    }

    @Test
    fun outboxAgeBucket_nothingPending_isUnderAnHour() = runTest {
        consent()
        db.outboxDao().insert(
            Outbox(
                messageId = "m-sent",
                eventId = 1,
                envelopeBytes = ByteArray(4),
                status = Outbox.STATUS_SENT,
                createdAtEpochMillis = nowMs - 7L * 60 * 60 * 1000,
            ),
        )

        recorder().record()

        assertEquals(OutboxAgeBucket.UNDER_1H.wire, journalRows().single().outboxAgeBucket)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val START = 1_800_000_000_000L
    }
}

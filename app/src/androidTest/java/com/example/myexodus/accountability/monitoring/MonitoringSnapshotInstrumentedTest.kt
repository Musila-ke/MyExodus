package com.example.myexodus.accountability.monitoring

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.accountability.sync.AppCheckTestSupport
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.google.firebase.FirebaseApp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §7 Step 7 sender half on a device: for a consenting relationship the production
 * [MonitoringSnapshotRecorder] journals a contract monitoring body and the very next production
 * sync pass builds it into that pair's chain — the row the Step 4 outbox drains as the sealed
 * envelope. The consent row belongs to an existing test pair and is restored in `finally`;
 * no other pair's settings are read or written.
 *
 * Run (device paired by the scenario harness):
 *   adb shell am instrument -w -e class \
 *     com.example.myexodus.accountability.monitoring.MonitoringSnapshotInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class MonitoringSnapshotInstrumentedTest {

    @Test
    fun consentingPair_journalsAndShipsAMonitoringSnapshot(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())
        val store = ep.appRulesStoreFactory().open() ?: error("rules store unavailable")
        // Harness runs leave stale enrollments behind (rotated identity, deleted pairing).
        // The consent leg must ride a pair the server still answers for, or the pass silently
        // skips it and the snapshot would never be built — probe exactly as the pass does.
        val resolver = ep.pairUidResolver()
        val enrollment = ep.enrollmentDao().all()
            .firstOrNull { resolver.resolve(it.pairId) != null }
            ?: error("no resolvable enrollment: run the pairing harness first")
        log("RESULT monitor=PAIR ${enrollment.pairId}")
        val sharing = store.pairSharingDao()
        val previous = sharing.profileFor(enrollment.pairId)
        try {
            val enabledAt = System.currentTimeMillis()
            sharing.upsertProfile(
                (previous ?: PairProfileSharing(pairId = enrollment.pairId))
                    .copy(shareAvailability = true, availabilityEnabledSinceMs = enabledAt),
            )

            assertTrue(
                "a consenting pair must journal a snapshot",
                ep.monitoringSnapshotRecorder().record(force = true),
            )
            val row = store.partnerEventJournalDao()
                .dueForDelivery(enabledAt, System.currentTimeMillis(), 100)
                .lastOrNull { it.monitoringState != null }
                ?: error("no contract monitoring journal row")
            val state = MonitoringState.fromWire(row.monitoringState!!)
                ?: error("journaled state is not a contract value: ${row.monitoringState}")
            log("RESULT monitor=JOURNALED event=${row.eventId} state=${state.wire} outbox=${row.outboxAgeBucket}")

            val report = ep.partnerSyncCoordinator().syncOnce()
            assertTrue("an enrolled pair must run the pass", report.enrolled)
            assertTrue(
                "the pass must link the journaled snapshot into the pair's chain",
                ep.chainStateStore().hasEvent(
                    enrollment.pairId, enrollment.mySenderKeyId, row.eventId,
                ),
            )
            log("RESULT monitor=LINKED pair=${enrollment.pairId} linked=${report.eventsLinked}")
            log("RESULT monitor=PASS")
        } finally {
            if (previous != null) sharing.upsertProfile(previous)
            else sharing.deleteProfileFor(listOf(enrollment.pairId))
            log("RESULT monitor=RESTORED availability=${previous?.shareAvailability ?: false}")
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusMonitorTest"
    }
}

package com.example.myexodus.accountability.sync

import android.app.ActivityManager
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.monitoring.PartnerHeartbeatClient
import com.example.myexodus.accountability.monitoring.StandbyBucket
import com.example.myexodus.accountability.serialization.MonitoringState
import com.example.myexodus.data.db.IS_EMULATOR
import com.example.myexodus.data.db.entity.DeviceLiveness
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §7 Step 7 device leg: the production [PartnerHeartbeatClient] instance calling the deployed,
 * App Check-enforced `submitHeartbeat` as this device's real anonymous identity.
 *
 * What only a device can prove is what this asserts: the production install registration
 * satisfies the server's `INSTALL_NOT_REGISTERED` gate; Play Integrity passes under
 * enforcement; the persisted counter survives the call; the server's 30 s floor refuses a
 * forced beat without losing liveness; a burned counter is never reused; and the whole path
 * posts no notification. Replay/namespace semantics beyond that are covered by the JVM suite
 * (client) and the Firestore-emulator suite (server).
 *
 * Run (attesting X6532, no extra args):
 *   adb shell am instrument -w -r -e class \
 *     com.example.myexodus.accountability.sync.PartnerHeartbeatInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 *
 * For the App Standby leg the host first runs
 *   adb shell am set-standby-bucket com.example.myexodus rare
 * then restores `active` afterwards; the test asserts the persisted bucket equals whatever the
 * platform reports at check-in time, so the marked run is the proof. On the X6532 the bucket
 * is pinned EXEMPTED by the deviceidle whitelist and any instrumentation foregrounds the app,
 * so the observable buckets there are EXEMPTED/ACTIVE; RARE/RESTRICTED mapping, the jitter
 * floor and the once-per-transition notice are JVM-proven.
 *
 * Two further legs, each run as its own class argument:
 *  - `workManagerFallback_beatsWithoutTheForegroundService`: host force-stops the app first
 *    (also proving the stopped window's absence of beats); instrumentation starts the process,
 *    `MyExodusApp` skips its FGS re-arm under instrumentation, the production work is
 *    cancelled, and the test waits out the client's real 15-minute floor on the wall clock
 *    before force-dueing the production worker. ~17 minutes.
 *  - `noNetwork_beatFailsBurnsItsCounter_andTheNextOnlineBeatRecovers`: run WITHOUT `-w`
 *    (the radio is the adb transport). The test logs `RESULT offline=ARMED`, then polls for
 *    the host's airplane mode; the host arms a detached restore
 *      adb shell 'setsid sh -c "cmd connectivity airplane-mode enable; sleep 45; \
 *        cmd connectivity airplane-mode disable" >/dev/null 2>&1 &'
 *    and reconnects wireless adb. Results are read back from logcat.
 */
@RunWith(AndroidJUnit4::class)
class PartnerHeartbeatInstrumentedTest {

    @Test
    fun deployedCallableAcceptsTheDeviceBeat_andTheServerFloorHolds(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())

        assertTrue(
            "the production installation registration must succeed — otherwise the server " +
                "correctly refuses the beat (notification permission / GMS / network state)",
            ep.pushInstallationRegistrar().register(),
        )
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no anonymous identity after registration")
        val fid = PushInstallationRegistrar.installationIdOf()
            ?: error("no Firebase installation id on this device")
        log("identity uid=$uid fid=$fid")

        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = activeIds(context, notifications)
        val heartbeat = ep.partnerHeartbeatClient()
        val store = ep.appRulesStoreFactory().open() ?: error("rules store unavailable")

        // 1. An accepted beat through the deployed callable.
        val first = heartbeat.checkIn(force = true)
        if (first == PartnerHeartbeatClient.Outcome.RESYNCED) {
            // A prior run (or the server) is ahead of a freshly restored local row; the next
            // honest beat after the floor resynchronizes forward.
            log("first beat RESYNCED — waiting out the server floor")
            delay(SERVER_FLOOR_WAIT_MS)
            assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, heartbeat.checkIn(force = true))
        } else {
            assertEquals("first beat must be liveness: $first", PartnerHeartbeatClient.Outcome.ACCEPTED, first)
        }
        val accepted = store.partnerLivenessDao().liveness(fid) ?: error("no liveness row")
        assertEquals(uid, accepted.uid)
        assertEquals(fid, accepted.installId)
        assertEquals("the server accepted this exact counter", accepted.counter, accepted.lastAcceptedCounter)
        assertTrue("accepted time recorded", accepted.lastAcceptedAtMs > 0)
        log("ACCEPTED counter=${accepted.counter} at=${accepted.lastAcceptedAtMs}")

        // 2. A forced second beat inside the server's 30 s floor: refused, never liveness, and
        // the burned counter is kept so the next send cannot replay it.
        assertEquals(
            PartnerHeartbeatClient.Outcome.RESYNCED,
            heartbeat.checkIn(force = true),
        )
        val refused = store.partnerLivenessDao().liveness(fid)!!
        assertEquals("the burned number is never reused", accepted.counter + 1, refused.counter)
        assertEquals(accepted.lastAcceptedCounter, refused.lastAcceptedCounter)
        assertEquals(
            "a refused beat never erases the prior acceptance",
            accepted.lastAcceptedAtMs,
            heartbeat.lastAcceptedAt(),
        )
        log("RESULT serverFloor=PASS burned=${refused.counter}")

        // 3. Past the floor the next beat lands and is the new liveness truth.
        delay(SERVER_FLOOR_WAIT_MS)
        assertEquals(PartnerHeartbeatClient.Outcome.ACCEPTED, heartbeat.checkIn(force = true))
        val next = store.partnerLivenessDao().liveness(fid)!!
        assertEquals(refused.counter + 1, next.counter)
        assertEquals(next.counter, next.lastAcceptedCounter)
        assertTrue(next.lastAcceptedAtMs > accepted.lastAcceptedAtMs)
        log("ACCEPTED counter=${next.counter} at=${next.lastAcceptedAtMs}")

        // 4. App Standby: a forced check-in always re-reads the bucket, so the persisted value
        // must equal what the platform reports right now (the host may have set `rare`).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val platform = StandbyBucket.fromPlatform(
                context.getSystemService(UsageStatsManager::class.java).appStandbyBucket,
            )
            val checkin = store.partnerLivenessDao().checkin()
            assertNotNull("standby diagnostic row written at check-in", checkin)
            assertEquals("persisted bucket reflects the platform", platform.wire, checkin!!.lastStandbyBucket)
            assertTrue(checkin.standbyCheckedAtMs > 0)
            log("RESULT standby=${checkin.lastStandbyBucket}")
        }

        // 5. The heartbeat path is silent by design: no notification was posted for any of it.
        assertEquals(
            "the heartbeat path must never post a notification",
            before,
            activeIds(context, notifications),
        )
        log("RESULT heartbeat=PASS uid=$uid fid=$fid")
        log("RESULT mono elapsed=${SystemClock.elapsedRealtime()}")

        // 6. §7 Step 7 snapshot half, against the durable journal the partner envelopes are
        // built from: a consenting pair's FGS start journals its observed state. When no pair
        // consents to availability sharing the recorder is silent by design — log that truth
        // rather than asserting a snapshot the consent gate forbids.
        val consenting = store.pairSharingDao().profileAll().any { it.shareAvailability }
        if (consenting) {
            val nowMs = System.currentTimeMillis()
            val snapshots = store.partnerEventJournalDao()
                .dueForDelivery(nowMs - SNAPSHOT_LOOKBACK_MS, nowMs, 100)
                .filter { it.monitoringState != null }
            assertTrue("a consenting pair must have a journaled monitoring snapshot", snapshots.isNotEmpty())
            val latest = snapshots.last()
            assertTrue(
                "journaled snapshot is a contract monitoring state: ${latest.monitoringState}",
                MonitoringState.fromWire(latest.monitoringState!!) != null,
            )
            log(
                "RESULT snapshot=PASS state=${latest.monitoringState} " +
                    "outbox=${latest.outboxAgeBucket} at=${latest.createdAtMs}",
            )
        } else {
            log("RESULT snapshot=NO_CONSENT availabilitySharing off for every pair (by design)")
        }
    }

    /**
     * §7 Step 7 WorkManager fallback on the real scheduler: no FGS exists in this process
     * (host force-stopped first; `MyExodusApp` skips the re-arm under instrumentation), the
     * production periodic work is cancelled so only the test's due-now spec can run, and the
     * client's real 15-minute floor + jitter is waited out on the wall clock — no faked
     * timestamps. The pass is proven by its own effect: the next counter is sent, the server
     * accepts it, and the process's first check-in performs the startup App Standby read.
     */
    @Test
    fun workManagerFallback_beatsWithoutTheForegroundService(): Unit = runBlocking {
        Assume.assumeFalse(
            "host force-stop + 15-minute floor choreography is a X6532 run, not a suite leg",
            IS_EMULATOR,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())

        assertFalse("no FGS may run during this leg", foregroundServiceRunning(context))
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no anonymous identity")
        val fid = PushInstallationRegistrar.installationIdOf()
            ?: error("no Firebase installation id on this device")
        val store = ep.appRulesStoreFactory().open() ?: error("rules store unavailable")
        val dao = store.partnerLivenessDao()
        val before = dao.liveness(fid) ?: error("no liveness row: run the main leg first")
        assertEquals(uid, before.uid)

        val workManager = WorkManager.getInstance(context)
        try {
            workManager.cancelUniqueWork(PARTNER_SYNC_WORK_NAME)
            awaitCancelled(workManager)
            val dueMs = before.lastSentAtMs + PartnerHeartbeatClient.MIN_INTERVAL_MILLIS +
                PartnerHeartbeatClient.JITTER_MAX_MILLIS + 2_000L
            log("RESULT wm=WAITING counter=${before.counter} waitMs=${(dueMs - System.currentTimeMillis()).coerceAtLeast(0)}")
            while (System.currentTimeMillis() < dueMs) {
                delay(30_000)
                log("RESULT wm=tick remainingMs=${(dueMs - System.currentTimeMillis()).coerceAtLeast(0)}")
            }
            assertFalse("FGS appeared during the wait", foregroundServiceRunning(context))

            val passStart = System.currentTimeMillis()
            enqueueDueNow(workManager)
            // Real dispatch: WorkManager → scheduler → @HiltWorker doWork() → syncOnce → checkIn.
            val deadline = passStart + 180_000L
            var row: DeviceLiveness? = null
            while (System.currentTimeMillis() < deadline) {
                // The client persists its counter BEFORE the call, so a bare counter bump only
                // proves the send started. Acceptance is the pass's real effect: wait for the
                // reply to land, or a cancellation race would assert on an in-flight beat.
                val current = dao.liveness(fid)
                if (current != null && current.counter > before.counter &&
                    current.lastAcceptedCounter == current.counter
                ) {
                    row = current
                    break
                }
                delay(2_000)
            }
            val after = row ?: error("WorkManager pass never reached the heartbeat in 180s")
            assertEquals("the WM beat sends the next counter", before.counter + 1, after.counter)
            assertEquals("the server accepted the WM beat", after.counter, after.lastAcceptedCounter)
            assertTrue(
                "the accepted time advanced from ${before.lastAcceptedAtMs}",
                after.lastAcceptedAtMs > before.lastAcceptedAtMs,
            )
            val checkin = dao.checkin() ?: error("no standby diagnostic row")
            assertTrue(
                "the WM process's first check-in is a startup standby read " +
                    "(${checkin.standbyCheckedAtMs} vs pass $passStart)",
                checkin.standbyCheckedAtMs >= passStart,
            )
            val platform = StandbyBucket.fromPlatform(
                context.getSystemService(UsageStatsManager::class.java).appStandbyBucket,
            )
            assertEquals("persisted bucket reflects the platform", platform.wire, checkin.lastStandbyBucket)
            log(
                "RESULT wm=PASS counter=${after.counter} standby=${checkin.lastStandbyBucket} " +
                    "standbyCheckedAt=${checkin.standbyCheckedAtMs}",
            )
        } finally {
            workManager.cancelUniqueWork(PARTNER_SYNC_WORK_NAME)
            awaitCancelled(workManager)
            enqueuePartnerSync(context)
            log("RESULT wm=RESTORED productionEnqueue=true")
        }
    }

    /**
     * §7 Step 7 no-network leg on the real radio. Host choreography is in the class doc: the
     * test waits for the host's airplane mode, then proves the offline attempt fails without
     * unproving the prior acceptance and burns its counter, and that the first online beat
     * after recovery (server floor permitting) is accepted — the burned number is never reused.
     */
    @Test
    fun noNetwork_beatFailsBurnsItsCounter_andTheNextOnlineBeatRecovers(): Unit = runBlocking {
        Assume.assumeFalse(
            "the host drives airplane mode and the leg needs the main leg's liveness row first",
            IS_EMULATOR,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())
        val fid = PushInstallationRegistrar.installationIdOf()
            ?: error("no Firebase installation id on this device")
        val store = ep.appRulesStoreFactory().open() ?: error("rules store unavailable")
        val dao = store.partnerLivenessDao()
        val client = ep.partnerHeartbeatClient()
        val before = dao.liveness(fid) ?: error("no liveness row: run the main leg first")

        log("RESULT offline=ARMED counter=${before.counter} accepted=${before.lastAcceptedCounter}")
        check(waitForUsableNetwork(context, connected = false, timeoutMs = 300_000)) {
            "host never took the device offline"
        }
        // Offline the callable fails: the number is burned, nothing is accepted, and the
        // previous acceptance time survives (§8.10 — a refused beat never unproves one).
        assertEquals(PartnerHeartbeatClient.Outcome.FAILED, client.checkIn(force = true))
        val burned = dao.liveness(fid)!!
        assertEquals("the offline attempt burns the next counter", before.counter + 1, burned.counter)
        assertEquals("offline never accepts", before.lastAcceptedCounter, burned.lastAcceptedCounter)
        assertEquals("the prior acceptance stands", before.lastAcceptedAtMs, burned.lastAcceptedAtMs)
        log("RESULT offline=FAILED burned=${burned.counter} accepted=${burned.lastAcceptedCounter}")

        check(waitForUsableNetwork(context, connected = true, timeoutMs = 300_000)) {
            "host never restored the radio"
        }
        // Wi-Fi reassociates before the platform validates the network; a beat inside that
        // window would burn another number, so give validation a moment.
        delay(3_000)
        var online = client.checkIn(force = true)
        if (online == PartnerHeartbeatClient.Outcome.RESYNCED) {
            log("first online beat RESYNCED — waiting out the server floor")
            delay(SERVER_FLOOR_WAIT_MS)
            online = client.checkIn(force = true)
        }
        assertEquals("the first online beat must be accepted: $online", PartnerHeartbeatClient.Outcome.ACCEPTED, online)
        val recovered = dao.liveness(fid)!!
        assertEquals("the burned number is never reused", burned.counter + 1, recovered.counter)
        assertEquals(recovered.counter, recovered.lastAcceptedCounter)
        assertTrue(
            "the recovery beat is the new acceptance",
            recovered.lastAcceptedAtMs > before.lastAcceptedAtMs,
        )
        log("RESULT offline=PASS counter=${recovered.counter} at=${recovered.lastAcceptedAtMs}")
    }

    private fun enqueueDueNow(workManager: WorkManager) {
        // WorkManager 2.11: a past-time override is honoured only until the spec has run once
        // (the run clamps it to lastEnqueue + interval), so the cancelled work is re-enqueued
        // fresh — the same recipe the partner-sync execution leg uses.
        val dueNow = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES)
            .setNextScheduleTimeOverride(System.currentTimeMillis() - 1_000L)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PARTNER_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, dueNow,
        )
    }

    private suspend fun awaitCancelled(workManager: WorkManager) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val remaining = workManager.getWorkInfosForUniqueWork(PARTNER_SYNC_WORK_NAME).get()
                .any { it.state != WorkInfo.State.CANCELLED }
            if (!remaining) break
            delay(200)
        }
    }

    /** Own-package service query (the deprecated API still reports the caller's own). */
    private fun foregroundServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == context.packageName &&
                it.service.className.endsWith("EnforcementForegroundService")
        }
    }

    /** Validated internet, not just a link: Wi-Fi reassociation is not yet usability. */
    private suspend fun waitForUsableNetwork(
        context: Context,
        connected: Boolean,
        timeoutMs: Long,
    ): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            val usable = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable == connected) return true
            delay(1_000)
        }
        return false
    }

    private fun activeIds(context: Context, manager: NotificationManager): Set<String> =
        manager.activeNotifications
            .filter { it.packageName == context.packageName }
            .map { "${it.id}/${it.tag}" }
            .toSet()

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"

        /** The server's floor plus a second of dispatch slop. */
        const val SERVER_FLOOR_WAIT_MS = 31_000L

        /** The snapshot check reads the journal a normal FGS start wrote in this session. */
        const val SNAPSHOT_LOOKBACK_MS = 2L * 60 * 60 * 1000
    }
}

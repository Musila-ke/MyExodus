package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan §8 Step 7 (client) / AD-18a — the execution leg on the physical device. The unit tests
 * pin the registrar's orchestration with fakes; this proves the real on-device pieces: anonymous
 * auth resolves a uid, FCM issues a token, the production registrar (same Hilt singleton the
 * worker injects) attempts the `push_installations` write, and the production WM worker runs
 * end-to-end on this device.
 *
 * Rule-deploy dependency: v2 security rules are written but NOT deployed (user-gated), so under
 * the live v1 rules the write is expected to be PERMISSION_DENIED — the test distinguishes that
 * specific signature (registrar still returns cleanly, AD-05 sync fallback applies) from any
 * other failure, and once the rules deploy the same test flips to asserting the written document.
 */
@RunWith(AndroidJUnit4::class)
class PushInstallationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val registrar: PushInstallationRegistrar by lazy {
        EntryPointAccessors.fromApplication(context, PartnerTransportTestEntryPoint::class.java)
            .pushInstallationRegistrar()
    }

    @Test
    fun registrar_resolvesRealAuthAndFcmToken_thenWritesOrIsRuleDenied() = runBlocking {
        AppCheckInstaller.install(context)
        // After the production installer: on a non-attesting device (emulator) the debug
        // provider must win, or every Firestore leg below 403s under App Check enforcement.
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
            ?: withTimeout(30_000) { auth.signInAnonymously().await() }.user?.uid
        assertNotNull("anonymous auth must resolve a uid", uid)

        val token = withTimeout(30_000) { FirebaseMessaging.getInstance().token.await() }
        assertTrue("FCM must issue a token on this device", !token.isNullOrBlank())

        val installationId = withTimeout(30_000) { PushInstallationRegistrar.installationIdOf() }
        assertNotNull("device must resolve a Firebase Installation ID", installationId)

        val registered = withTimeout(120_000) { registrar.register() }
        val doc = FirestoreSchema.pushInstallationDocument(
            FirebaseFirestore.getInstance(), uid!!, installationId!!,
        )
        if (registered) {
            val snapshot = doc.get().await()
            assertTrue("registered install must exist under (uid, fid)", snapshot.exists())
            assertEquals(
                setOf("provider", "token", "platform", "appVersion", "pushEligible", "updatedAt", "createdAt"),
                snapshot.data?.keys,
            )
        } else {
            // Not deployed yet count: the write itself must be the rules rejection — not a
            // different breakage wearing the same boolean.
            val failure = runCatching {
                doc.set(
                    FirestoreSchema.pushInstallationData("fcm", token!!, "android", "instrumented", true),
                ).await()
            }.exceptionOrNull()
            assertTrue(
                "register() failed for a non-rules reason: $failure",
                failure is FirebaseFirestoreException &&
                    failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED,
            )
        }
    }

    @Test
    fun partnerSyncWorker_executesOnDevice_underRealWorkManager() = runBlocking {
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance())
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid
            ?: withTimeout(30_000) { auth.signInAnonymously().await() }.user?.uid
        val installationId = withTimeout(30_000) { PushInstallationRegistrar.installationIdOf() }
        val doc = FirestoreSchema.pushInstallationDocument(
            FirebaseFirestore.getInstance(), uid!!, installationId!!,
        )
        val before = doc.get().await().getTimestamp("updatedAt")

        // XOS never fires WorkManager jobs on schedule (ADR #27), so make the spec due now.
        // Two WM 2.11 facts shape the recipe (verified against work-runtime 2.11.2 sources):
        // a past-time override is honoured only while the work has never run (periodCount ==
        // 0) — calculateNextRunTime otherwise clamps it to lastEnqueue + interval — so the
        // stale spec must be cancelled first (UPDATE deletes a CANCELLED spec and re-enqueues
        // it fresh); and a periodic work never reaches WorkInfo.State.SUCCEEDED (its docs:
        // "will simply go back to ENQUEUED"). The execution proof is therefore the pass's
        // Firestore effect — the registrar inside runPartnerSyncPass refreshing updatedAt.
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PARTNER_SYNC_WORK_NAME)
        awaitCancelled(workManager)
        val dueNow = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES)
            .setNextScheduleTimeOverride(System.currentTimeMillis() - 1_000L)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PARTNER_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, dueNow,
        )

        // Real dispatch: WorkManager → scheduler → @HiltWorker doWork() → registrar + syncOnce.
        val deadline = System.currentTimeMillis() + 180_000L
        var advanced = false
        while (System.currentTimeMillis() < deadline) {
            val now = doc.get().await().getTimestamp("updatedAt")
            if (now != null && (before == null || now.compareTo(before) > 0)) {
                advanced = true
                break
            }
            delay(2_000)
        }
        assertTrue("PartnerSyncWorker pass never ran: updatedAt did not advance in 180s", advanced)

        // Restore the production pin: drop the test spec, then re-enqueue the KEEP cadence.
        workManager.cancelUniqueWork(PARTNER_SYNC_WORK_NAME)
        awaitCancelled(workManager)
        enqueuePartnerSync(context)
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
}

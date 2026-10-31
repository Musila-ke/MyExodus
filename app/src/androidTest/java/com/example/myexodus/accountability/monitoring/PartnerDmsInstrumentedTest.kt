package com.example.myexodus.accountability.monitoring

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.sync.AppCheckTestSupport
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.assumePartnerHarness
import com.example.myexodus.accountability.sync.harnessLog
import com.example.myexodus.accountability.sync.pairDeviceWith
import com.example.myexodus.data.db.entity.PairProfileSharing
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PA Step 8 (§8.10-§8.13) device leg: the DI-wired [PartnerDmsClient] against the deployed,
 * App Check-enforced `setAvailabilityConsent` / `readPartnerStatus` callables, driven from the
 * production sync pass — the local sharing toggle's value must reach the AUTHORITATIVE pairing
 * document the dispatch recheck reads, and the status read must parse the deployed shape
 * without inventing a state.
 *
 * What only a device can prove: Play Integrity passes under enforcement for both callables;
 * an active pairing accepts this member's consent write; the virtual partner's read — a
 * different authenticated identity — sees exactly what the client wrote (fail closed before,
 * true after, false after revocation); a pass with no local sharing row writes nothing; and
 * the `availability_alerts_v1` channel exists at HIGH importance from app startup. The
 * cache/ack semantics (one callable per confirmed value, TTL boundary, failed-read
 * behaviour) and every server branch are covered by [PartnerDmsClientTest], the Firestore
 * emulator suite and the 100k soak.
 *
 * Preconditions mirror PartnerApprovalInstrumentedTest: signed-in anonymous identity, warm
 * signing window (tap "Warm signing window" in PairingTestActivity), fresh virtual partner.
 *   adb install -r app-debug.apk + app-debug-androidTest.apk
 *   adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity
 *   adb shell am instrument -w -r -e class \
 *     com.example.myexodus.accountability.monitoring.PartnerDmsInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PartnerDmsInstrumentedTest {

    @Test
    fun localSharingIntentConvergesToTheServer_andTheStatusReadParses(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. The channel the server's six-hour notice targets must exist from startup, at the
        // importance the push expects. Create-only in the app; a device-side downgrade is the
        // user's override, so this asserts the created value on this device.
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(PartnerNotifications.CHANNEL_AVAILABILITY_ALERTS)
        assertNotNull("availability channel must exist from app startup", channel)
        assertEquals(
            "the availability channel is created HIGH (§8.12 user-visible push)",
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )
        harnessLog("RESULT channel=HIGH id=${channel.id}")

        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        assumePartnerHarness(ep)
        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val identity = PartnerTestIdentity(
            context = context,
            cekDerivation = ep.cekDerivation(),
            eventCipher = ep.eventCipher(),
            epochStore = ep.identityEpochStore(),
        )
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        harnessLog("A=$uidA B=$uidB pair=$pairId")
        pairDeviceWith(ep, identity, uidA, uidB, "Musila", "Mama")

        val store = ep.appRulesStoreFactory().open() ?: error("rules store unavailable")
        val dao = store.pairSharingDao()
        val dms = ep.partnerDmsClient()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val trayBefore = activeIds(context, notifications)

        // 2. Fail-closed baseline: this pair has no local sharing row, so the pass must write
        // nothing, and the virtual partner's read of A reports "not shared".
        assertNull("a fresh pair has no local sharing row", dao.profileFor(pairId))
        ep.partnerSyncCoordinator().syncOnce(null)
        val absent = statusAsPartner(identity, pairId)
        assertEquals("an unstated consent is not shared", false, absent["consent"])
        assertEquals("an unstated consent yields no availability data", "NO_DATA", absent["connection"])
        harnessLog("RESULT noLocalRow=PASS (pass wrote nothing; partner sees consent=false)")

        // 3. The user's explicit toggle (the exact row SharingScreen writes) converges through
        // the production pass to the authoritative pairing document.
        dao.upsertProfile(PairProfileSharing(pairId = pairId, shareAvailability = true))
        // The report's uidsResolved is aggregate across EVERY enrollment this device holds —
        // repeated harness runs leave older pairs whose server relationships are gone, so it
        // is not this pair's verdict. The pair's verdict is the server read below.
        val onPass = ep.partnerSyncCoordinator().syncOnce(null)
        harnessLog("pass: $onPass")
        val shared = statusAsPartner(identity, pairId)
        assertEquals("the consent must reach the authoritative pairing document", true, shared["consent"])
        assertKnownShape(shared)
        harnessLog("RESULT consentOn=PASS consent=${shared["consent"]} connection=${shared["connection"]}")

        // 4. A's client read path against the deployed callable: it asks about B, who never
        // consented, so the parsed status must be the explicit "not shared / NO_DATA" answer —
        // a real callable round trip, not null and not an invented state.
        val aboutB = dms.status(pairId, force = true)
        assertNotNull("the deployed readPartnerStatus answer must parse", aboutB)
        assertEquals(false, aboutB!!.consent)
        assertEquals(PartnerConnection.NO_DATA, aboutB.connection)
        assertEquals(PartnerConnectionBucket.NO_DATA, aboutB.bucket)
        harnessLog("RESULT statusAboutUnconsentedPartner=PASS connection=${aboutB.connection.wire}")

        // 5. The consented read shape: B sets its own scope through the deployed callable (a
        // second authenticated identity, App Check enforced), and A's client must now parse
        // consent=true. B has no liveness state, so the derived reading is NO_DATA — the
        // "consented, no data yet" state the display contract defines.
        setConsentAsPartner(identity, pairId, true)
        val consented = dms.status(pairId, force = true)
        assertNotNull("a consented partner status must parse", consented)
        assertEquals(true, consented!!.consent)
        assertEquals(PartnerConnection.NO_DATA, consented.connection)
        assertEquals(PartnerConnectionBucket.NO_DATA, consented.bucket)
        harnessLog("RESULT statusAboutConsentedPartner=PASS consent=true connection=NO_DATA")

        // 6. Revocation converges too: the toggle going off is the user's intent, and the same
        // pass must retire the server value the dispatch recheck reads.
        dao.upsertProfile(PairProfileSharing(pairId = pairId, shareAvailability = false))
        ep.partnerSyncCoordinator().syncOnce(null)
        val revoked = statusAsPartner(identity, pairId)
        assertEquals("revocation must reach the authoritative pairing document", false, revoked["consent"])
        harnessLog("RESULT consentOff=PASS consent=${revoked["consent"]}")

        // 7. A failed callable never throws into the pass or invents a reading: a pair the
        // caller is not a member of is refused, reported as "not acknowledged", and the read
        // claims nothing.
        assertFalse("a refused consent write is not acknowledged", dms.syncConsent("pair-does-not-exist", true))
        assertNull("a refused status read claims nothing", dms.status("pair-does-not-exist", force = true))
        harnessLog("RESULT refusedWrites=PASS (syncConsent=false, status=null)")

        // 8. None of the DMS path posts a notification: the six-hour notice is the server
        // worker's job, not the client's.
        assertEquals(
            "the DMS client path must never post a notification",
            trayBefore,
            activeIds(context, notifications),
        )

        harnessLog("RESULT dms=PASS pair=$pairId")
    }

    private suspend fun statusAsPartner(identity: PartnerTestIdentity, pairId: String): Map<*, *> =
        identity.functions.getHttpsCallable("readPartnerStatus")
            .call(mapOf("pairId" to pairId))
            .await()
            .data as? Map<*, *>
            ?: error("readPartnerStatus returned no map for $pairId")

    private suspend fun setConsentAsPartner(
        identity: PartnerTestIdentity,
        pairId: String,
        enabled: Boolean,
    ) {
        val answer = identity.functions.getHttpsCallable("setAvailabilityConsent")
            .call(mapOf("pairId" to pairId, "enabled" to enabled))
            .await()
            .data as? Map<*, *>
        assertEquals("the partner's own consent write must land", pairId, answer?.get("pairId"))
    }

    private fun assertKnownShape(status: Map<*, *>) {
        assertTrue(
            "a known connection state: ${status["connection"]}",
            status["connection"] in setOf("CHECKING_IN", "LATE", "NOT_CHECKING_IN", "NO_DATA"),
        )
        assertTrue(
            "a known bucket: ${status["bucket"]}",
            status["bucket"] in setOf("WITHIN_1H", "HOURS_1_TO_6", "OVER_6H", "NO_DATA"),
        )
        assertFalse("no precise timestamp is ever returned", status.containsKey("last_received_at_ms"))
    }

    private fun activeIds(context: Context, manager: NotificationManager): Set<String> =
        manager.activeNotifications
            .filter { it.packageName == context.packageName }
            .map { "${it.id}/${it.tag}" }
            .toSet()

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
    }
}

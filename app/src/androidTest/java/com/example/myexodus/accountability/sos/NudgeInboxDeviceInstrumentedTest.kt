package com.example.myexodus.accountability.sos

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.PartnerTestKeys
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.sync.AppCheckTestSupport
import com.example.myexodus.accountability.sync.PartnerNotifications
import com.example.myexodus.accountability.sync.PartnerNudgePush
import com.example.myexodus.accountability.sync.assertWarmSigningWindow
import com.example.myexodus.accountability.sync.harnessLog
import com.example.myexodus.accountability.sync.pairDeviceWith
import com.example.myexodus.notifications.NotificationAccess
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SOS plan §12.3, recipient-inbox bullets — the data half (physical-device leg, X6532 rig):
 *
 *  - Card appears from Firestore without relying on FCM payload content.
 *  - `I'm here` acknowledges all fetched pending nudges.
 *  - `X` acknowledges all fetched pending nudges.
 *  - Notification cancellation occurs after successful acknowledgment.
 *
 * The virtual partner (the §12 harness identity) seals a nudge and sends it through the real
 * `sendNudge` callable; the app's DI-wired [NudgeInboxRepository] — the exact instance Home's
 * card renders from — must fetch it via its Firestore listener alone (no FCM is involved
 * anywhere in this leg: the card's content never comes from push), and one acknowledgment must
 * write `ackedAt` on the live backend, clear the card through the listener, and retire the tray
 * entry. Killing/foregrounding is not needed because the fixture is the listener, not a push.
 *
 * The UI half of these bullets — both actions in [NudgeHomeSurfaceInstrumentedTest] invoking
 * this same acknowledgment path, and the batch-over-all-fetched semantics — plus FR-4's
 * 10-minute server cooldown that makes two fresh pending nudges unseedable inside one run
 * (NudgeInboxRepositoryTest pins the multi-id batch at the exact seam).
 *
 * Preconditions (X6532 rig): app launched + signed in; warm signing window ("Warm signing
 * window" in PairingTestActivity); POST_NOTIFICATIONS on; `/config/features.partnerSosEnabled`
 * = true for the run window (plan §13.2 item 8, "internal tests with controlled flag
 * enablement") — the test fails with that instruction if the callable refuses. Run with
 * `adb shell am instrument -w -r -e class com.example.myexodus.accountability.sos.NudgeInboxDeviceInstrumentedTest
 * com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner` — never via Gradle
 * connected tests against the physical device.
 */
@RunWith(AndroidJUnit4::class)
class NudgeInboxDeviceInstrumentedTest {

    @Test
    fun cardFromFirestore_ackAll_clearsIt_andRetiresTheTray(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)
        assertWarmSigningWindow(ep.signer())

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
        harnessLog("nudge leg: A=$uidA B=$uidB pair=$pairId")
        pairDeviceWith(ep, identity, uidA, uidB, "Musila", "Nudge Partner")

        // B seals the constant envelope under the pair CEK (mirrored derivation) and sends it
        // through the production callable — the only writer of a nudge document.
        val enrollmentB = identity.enrollmentDao.getByPairId(pairId) ?: error("B enrollment missing")
        val bCek = ep.cekDerivation().deriveCek(
            pairId,
            PartnerTestKeys.agreementPrivateKey(),
            EnrollmentTranscript.parsePublicKey(enrollmentB.partnerAgreementPubKey),
        )
        val nudgeId = UUID.randomUUID().toString()
        val envelope = NudgeEnvelope.seal(ep.eventCipher(), bCek, pairId, nudgeId, uidB)
        val sentAtMs = System.currentTimeMillis()
        val sent = try {
            FirebaseFunctions.getInstance(identity.app, "us-central1")
                .getHttpsCallable("sendNudge")
                .call(mapOf("pairId" to pairId, "nudgeId" to nudgeId, "envelope" to envelope))
                .await()
                .data
        } catch (e: FirebaseFunctionsException) {
            fail(
                "sendNudge refused (${e.code} ${e.message}) — enable " +
                    "/config/features.partnerSosEnabled for this run (plan §13.2 item 8)",
            )
            return@runBlocking
        }
        assertEquals("the callable must accept the nudge", "accepted", (sent as? Map<*, *>)?.get("status"))
        harnessLog("B sent nudge=$nudgeId envelope=${envelope.length}B")

        // The recipient inbox — the production DI instance Home's card renders from. This one
        // listener fetch is the whole bullet: no push, no FCM payload, the card is Firestore.
        val repository = ep.nudgeInboxRepository()
        val card = withTimeout(30_000) { repository.inbox().first { it != null } }!!
        assertEquals(pairId, card.pairId)
        assertEquals("the fetched pending set is exactly the sent nudge", listOf(nudgeId), card.pendingNudgeIds)
        assertTrue(
            "the card hangs off the sender's wall-clock stamp",
            card.createdAtMs in (sentAtMs - 60_000)..(System.currentTimeMillis() + 60_000),
        )

        // The tray entry a push would have posted for this nudge (the service's own call), so
        // the acknowledgment's cancellation has something real to retire.
        assertTrue(
            "POST_NOTIFICATIONS must be on for the X6532 tray leg",
            NotificationAccess.canPost(context),
        )
        PartnerNotifications.ensurePartnerNudgesChannel(context)
        PartnerNotifications.notifyPartnerNudge(context, nudgeId)
        val manager = context.getSystemService(NotificationManager::class.java)
        fun nudgeEntries() = manager.activeNotifications.filter {
            it.tag == PartnerNudgePush.TAG || it.id == PartnerNotifications.NOTIF_PARTNER_NUDGE
        }
        assertEquals(1, nudgeEntries().size)

        // One acknowledgment ("I'm here" and X are the same call): backend write → card
        // clears through the listener → tray entry retired (§6.9 steps 1-4).
        assertTrue("the ack batch must commit", repository.acknowledge(card))

        val backend = FirebaseFirestore.getInstance()
            .document("pairings/$pairId/nudges/$nudgeId")
            .get()
            .await()
        assertNotNull("ackedAt must land on the live backend document", backend.getTimestamp("ackedAt"))

        withTimeout(30_000) { repository.inbox().first { it == null } }
        assertEquals(
            "the acknowledged nudge must leave the tray",
            0,
            nudgeEntries().size,
        )
        harnessLog("RESULT nudgeInbox=PASS pair=$pairId nudge=$nudgeId ackAll=1 trayCancelled=true")
    }
}

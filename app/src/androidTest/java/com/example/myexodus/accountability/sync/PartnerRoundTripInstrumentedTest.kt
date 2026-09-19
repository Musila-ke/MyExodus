package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.state.ChainHead
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.EntryPointAccessors
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12 device matrix: the full v2 round trip on the X6532 — pair (real registerPairing, real
 * attestation both ways) → send/receive through the live rules + relay → gap repair
 * (temporary gap: SyncRequest → replay → healed chain).
 *
 * The partner is virtual: a second anonymous identity ([PartnerTestIdentity]) with a second
 * ATTESTED keypair ([com.example.myexodus.accountability.PartnerTestKeys]) on this same
 * device. Every wire leg and every protocol decision is production code; the fiction is only
 * "two uids, one phone". Cross-DEVICE distinctness is not exercised (a second physical
 * attestor does not exist; emulators cannot attest — 2026-09-16, vault).
 *
 * Preconditions: the app's anonymous user is signed in, and the biometric signing window is
 * warm — run PairingTestActivity's "Warm signing window" tap first, then:
 *   adb install -r app-debug.apk + app-debug-androidTest.apk
 *   adb shell am instrument -w -r -e class \
 *     com.example.myexodus.accountability.sync.PartnerRoundTripInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Every run pairs a FRESH virtual partner (a new anonymous uid): the pairing leg runs end to
 * end each time and the chain scenarios always start at sequence 0. The partner's attested
 * keys persist across runs; only its uid is new.
 */
@RunWith(AndroidJUnit4::class)
class PartnerRoundTripInstrumentedTest {

    @Test
    fun pairThenSendReceiveThenGapRepair(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)

        // Stage 0 — one warm biometric window covers every signature below (A's SyncRequest,
        // B's transcript + proof).
        assertWarmSigningWindow(ep.signer())

        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val identity = PartnerTestIdentity(
            context = context,
            cekDerivation = ep.cekDerivation(),
            eventCipher = ep.eventCipher(),
            epochStore = ep.identityEpochStore(),
        )
        // Both apps normally run PlayIntegrity (installed at startup / harness construction);
        // on a non-attesting device both need the registered debug token instead.
        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        log("A=$uidA B=$uidB pair=$pairId")

        // Stage 1 — pairing through the live registerPairing. No server pre-read: the rules
        // deliberately deny reads of a pair document the caller can't already see (probing
        // guard, firestore.rules), and with a fresh uid pair nothing exists yet anyway.
        pairDevice(ep, identity, uidA, uidB)
        val aRecord = ep.enrollmentDao().getByPairId(pairId)
            ?: error("A enrollment record missing after pairing")
        val bRecord = identity.enrollmentDao.getByPairId(pairId)
            ?: error("B enrollment record missing after pairing")
        assertEquals("A must store B's attested signing key", aRecord.partnerSigningKeyId, bRecord.mySenderKeyId)
        assertEquals("B must store A's attested signing key", bRecord.partnerSigningKeyId, aRecord.mySenderKeyId)

        val store = ep.chainStateStore()

        // Stage 2 — B creates four linked events; three are delivered, base+3 is held back —
        // the deliberate gap. (Delivery is hand-selected here precisely because a gap is the
        // scenario; the replay leg below runs the production drainer.)
        // A fresh partner uid means a fresh pairId: B's chain starts at sequence 0.
        val base = store.localHead(pairId, bRecord.mySenderKeyId)?.lastSequence ?: 0L
        val creator = identity.eventCreator()
        for (i in 1L..4L) {
            val result = creator.create(pairId, EventPayload(text = "roundtrip-${base + i}"), identity.signer.beginSign())
            assertTrue("B event create failed: $result", result is EventCreator.CreateResult.Created)
        }
        assertEquals("B's own chain head", base + 4, identity.store.localHead(pairId, bRecord.mySenderKeyId)!!.lastSequence)

        for (seq in listOf(base + 1, base + 2, base + 4)) {
            val event = identity.store.storedEvent(pairId, bRecord.mySenderKeyId, seq)
                ?: error("B event $seq not stored")
            identity.writeToMailbox(
                recipientUid = uidA,
                messageId = UUID.randomUUID().toString(),
                data = FirestoreSchema.mailboxMessageData(
                    senderId = bRecord.mySenderKeyId,
                    senderUid = uidB,
                    pairId = pairId,
                    payloadBase64 = Base64.getEncoder().encodeToString(event.envelope),
                    keyEpoch = event.epoch,
                ),
            )
        }
        // Manual delivery above IS B's send — mark the rows sent so B's production drainer
        // (the replay leg's transport) ships only the replays.
        identity.db.outboxDao().pendingFor(pairId, 100)
            .forEach { identity.db.outboxDao().markSent(it.messageId) }

        // Stage 3 — A receives base+1, base+2, base+4 → commits two, gaps on four.
        log("sync1: ${ep.partnerSyncCoordinator().syncOnce(null)}")
        val gapHead = store.localHead(pairId, bRecord.mySenderKeyId)!!
        assertEquals("A should have committed ${base + 2}", base + 2, gapHead.lastSequence)
        assertEquals("gap must open REPAIR_PENDING", ChainHead.REPAIR_PENDING, gapHead.repairState)
        // gapSequences lists the BUFFERED orphan events (PENDING_GAP rows), not the missing
        // numbers: the held-back base+3 leaves base+4 waiting as the gap row.
        assertEquals("buffered gap event", listOf(base + 4), store.gapSequences(pairId, bRecord.mySenderKeyId))

        // Stage 4 — A's repair pass ships a SyncRequest to B's mailbox (an FCM-wake pass may
        // have shipped it already — both are the production path; assert on the doc, not the caller).
        log("sync2: ${ep.partnerSyncCoordinator().syncOnce(null)}")
        val requestDoc = identity.mailboxMessages(uidB).firstOrNull {
            it.getString("pairId") == pairId && it.getString("senderUid") == uidA
        }
        assertNotNull("SyncRequest never reached B's mailbox", requestDoc)

        // Stage 5 — B hash-matches the anchor (A's last known link of B's chain) and replays
        // base+3 and base+4 as exact stored bytes with fresh message ids, then drains.
        val anchor = store.localHead(pairId, bRecord.mySenderKeyId)!!
        val replayed = identity.repairCoordinator().respondToRequest(pairId, anchor.lastSequence, anchor.lastEventHash)
        assertEquals("B must replay base+3 and base+4", 2, replayed)
        log("B drain: ${identity.outboxDrainer().drain(pairId, uidB, uidA)}")

        // Stage 6 — A heals: base+3 commits, the buffered base+4 re-validates, state clears.
        log("sync3: ${ep.partnerSyncCoordinator().syncOnce(null)}")
        val healed = store.localHead(pairId, bRecord.mySenderKeyId)!!
        assertEquals("A should have healed to ${base + 4}", base + 4, healed.lastSequence)
        assertTrue("gap must be cleared", store.gapSequences(pairId, bRecord.mySenderKeyId).isEmpty())
        assertEquals("healing must return the head to NONE", ChainHead.REPAIR_NONE, healed.repairState)
        assertFalse("a repair must never fork the chain", healed.forked)

        // Stage 7 — A's receive pipeline acked what it consumed (AD-18b leg).
        val acked = FirebaseFirestore.getInstance().collection("mailboxes").document(uidA)
            .collection("messages").get().await().documents.any { doc ->
                !doc.reference.collection("acks").get().await().isEmpty
            }
        assertTrue("A's receive pipeline left no acks (AD-18b)", acked)

        log("RESULT roundtrip=PASS pair=$pairId base=$base")
    }

    private suspend fun pairDevice(
        ep: RoundTripTestEntryPoint,
        identity: PartnerTestIdentity,
        uidA: String,
        uidB: String,
    ) {
        log("pairing: A initiates, B redeems, A confirms")
        val initiation = ep.pairingFlow().initiate(uidA)
        coroutineScope {
            val partnerOutcome = async(Dispatchers.IO) {
                identity.pairingFlow().complete(initiation.qrBytes, uidB, identity.signer.beginSign())
            }
            val await = ep.pairingFlow().awaitPartner(initiation.token, PairingToken.TTL_MILLIS)
            assertNotNull("partner material not verified (initiator rejected — see MyExodusPairing log)", await)
            log("SAS (both roles must agree): ${await!!.sas}")
            val outcomeA = ep.pairingFlow().submitInitiatorSignature(await, initiation.token, uidA, ep.signer().beginSign())
            val outcomeB = withTimeout(PARTNER_OUTCOME_TIMEOUT_MILLIS) { partnerOutcome.await() }
            log("initiator=$outcomeA partner=$outcomeB")
            assertTrue("initiator pairing failed: $outcomeA", outcomeA is PairingFlow.Outcome.Paired)
            assertTrue("partner pairing failed: $outcomeB", outcomeB is PairingFlow.Outcome.Paired)
            assertEquals(
                "SAS mismatch between roles",
                (outcomeA as PairingFlow.Outcome.Paired).sas,
                (outcomeB as PairingFlow.Outcome.Paired).sas,
            )
        }
    }

    private fun assertWarmSigningWindow(signer: AccountabilitySigner) {
        val signature = signer.beginSign()
        signature.update("warm-window-probe".toByteArray())
        try {
            signer.finishSign(signature)
        } catch (e: Exception) {
            fail(
                "signing window is cold — tap \"Warm signing window\" in PairingTestActivity " +
                    "(adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity) " +
                    "and re-run. Cause: ${e.message}",
            )
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
        const val PARTNER_OUTCOME_TIMEOUT_MILLIS = 60_000L
    }
}

package com.example.myexodus.accountability.sync

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.ui.partner.PartnerDashboardUi
import com.example.myexodus.ui.partner.canRespondToApprovals
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * §7 item 13 / §8 Step 12 acceptance on the wire (ADR #48; leg delivered as the ADR #46 harness
 * was designed to be extended): the approval flow end to end — B (virtual partner) requests
 * through the production requester half, A's production DI pipeline records the RESPONDER row
 * with its server-anchored deadline, A decides through the production responder half, and B's
 * production receive pipeline applies the decision.
 *
 * What only the device can prove and the JVM suite cannot: the wire legs (rules + relay) carry
 * both approval event types, the responder deadline really is anchored on the mailbox document's
 * Firestore server timestamp, the canonical request id derives from the chain position the
 * sender cannot spoof, and the whole exchange runs under real attestation and a real warm
 * biometric window.
 *
 * Preconditions: the app's anonymous user is signed in, and the biometric signing window is
 * warm — tap "Warm signing window" in PairingTestActivity first (one tap covers A's and B's
 * keys; every signature below fits the 900 s window):
 *   adb install -r app-debug.apk + app-debug-androidTest.apk
 *   adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity
 *   adb shell am instrument -w -r -e class \
 *     com.example.myexodus.accountability.sync.PartnerApprovalInstrumentedTest \
 *     com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 *
 * A fresh virtual partner per run (new anonymous uid → new pairId); A's newest enrollment is
 * the pair under test (`EnrollmentDao.all()` is `created_at DESC`, which is what
 * `PartnerSyncCoordinator` syncs).
 */
@RunWith(AndroidJUnit4::class)
class PartnerApprovalInstrumentedTest {

    @Test
    fun requestTravelsResponderDecidesResponseApplies(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ep = EntryPointAccessors.fromApplication(context, RoundTripTestEntryPoint::class.java)

        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val identity = PartnerTestIdentity(
            context = context,
            cekDerivation = ep.cekDerivation(),
            eventCipher = ep.eventCipher(),
            epochStore = ep.identityEpochStore(),
        )
        assertWarmSigningWindow(ep.signer(), "A")
        assertWarmSigningWindow(identity.signer, "B")

        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        log("A=$uidA B=$uidB pair=$pairId")
        pairDevice(ep, identity, uidA, uidB)

        val aRecord = ep.enrollmentDao().getByPairId(pairId) ?: error("A enrollment missing")
        val bRecord = identity.enrollmentDao.getByPairId(pairId) ?: error("B enrollment missing")

        // Stage 1 — B requests through the production requester half: the request is a chain
        // event, the row is keyed by the canonical id derived from B's own chain position.
        val request = identity.approvalFlow()
            .requestApproval(pairId, REQUEST_MESSAGE, identity.signer.beginSign())
        assertTrue("request not chained: $request", request is ApprovalFlow.RequestResult.Submitted)
        val sequence = identity.store.localHead(pairId, bRecord.mySenderKeyId)!!.lastSequence
        val requestId = "${bRecord.mySenderKeyId}:$sequence"
        assertEquals(
            "canonical request id must derive from the chain position",
            requestId,
            (request as ApprovalFlow.RequestResult.Submitted).requestEventId,
        )
        log("B drained: ${identity.outboxDrainer().drain(pairId, uidB, uidA)}")

        // Stage 2 — A pulls over the live rules + relay; the DI-wired handler records the
        // RESPONDER row, expiry anchored on the mailbox document's SERVER timestamp (AD-16).
        val pull = ep.partnerSyncCoordinator().syncOnce(null)
        log("A pull: $pull")
        assertEquals("A must append B's approval request", 1, pull.pulledCount)
        val responderRow = ep.approvalFlow().pendingFor(pairId, ApprovalRequest.ROLE_RESPONDER)
            .singleOrNull() ?: error("A has no pending RESPONDER row after the pull")
        assertEquals(requestId, responderRow.requestEventId)
        assertEquals(REQUEST_MESSAGE, responderRow.message)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, responderRow.status)
        assertEquals(
            "responder deadline must be creation + 72h",
            ApprovalRequest.TTL_MILLIS,
            responderRow.expiresAtEpochMillis - responderRow.createdAtEpochMillis,
        )
        val serverClockDelta = responderRow.createdAtEpochMillis - System.currentTimeMillis()
        assertTrue(
            "responder row must be anchored on the mailbox server timestamp (delta=${serverClockDelta}ms)",
            abs(serverClockDelta) < SERVER_CLOCK_SANITY_MILLIS,
        )

        // Stage 3 — the exact read model the Partner tab renders: the pending row reaches the
        // §6 line 240 card position and the badge gate reads verified (buttons enabled).
        val ui = ep.partnerDashboardSource().load()
        assertTrue("dashboard must be Paired for a live enrollment", ui is PartnerDashboardUi.Paired)
        val approvalUi = (ui as PartnerDashboardUi.Paired).approvals.single { it.requestEventId == requestId }
        assertEquals(ApprovalRequest.ROLE_RESPONDER, approvalUi.role)
        assertEquals(ApprovalRequest.STATUS_REQUESTED, approvalUi.status)
        assertTrue(
            "the §6 hash-chain badge must read verified for a pending request (got ${ui.chainStatus})",
            canRespondToApprovals(ui.chainStatus),
        )

        // AD-16's responder guard compares the device clock against the server-anchored
        // creation. A device clock running behind the server's is refused until it catches up —
        // measure the skew and wait it out, never paper over a larger refusal than that.
        if (serverClockDelta > 0) {
            log("device clock is ${serverClockDelta}ms behind the server — waiting out AD-16's rollback guard")
            delay(serverClockDelta + 1_000)
        }

        // Stage 4 — A decides through the production responder half (what the badge-gated
        // buttons call after BiometricPrompt): CAS-decide, then the response event is chained.
        val decision = ep.approvalFlow().respond(requestId, approve = true, ep.signer().beginSign())
        assertTrue("A's decision was refused: $decision", decision is ApprovalFlow.RespondResult.Decided)
        val drain = ep.partnerSyncCoordinator().syncOnce(null)
        log("A decision drain: $drain")
        assertTrue("A must deliver the response event", drain.drained)

        // Stage 5 — B's production receive pipeline pulls the response and applies it: the
        // REQUESTER row moves, no earlier than the responder's decision.
        val bPull = identity.receiver().pull(pairId, uidB)
        log("B pull: $bPull")
        assertEquals("B must append A's approval response", 1, bPull.appended)
        assertEquals(
            "B's view of A's chain must hold the response event",
            1L,
            identity.store.localHead(pairId, aRecord.mySenderKeyId)!!.lastSequence,
        )
        val requesterRow = identity.approvalFlow().recentFor(pairId).single { it.requestEventId == requestId }
        assertEquals(ApprovalRequest.STATUS_APPROVED, requesterRow.status)
        assertEquals(ApprovalRequest.ROLE_REQUESTER, requesterRow.role)

        // Stage 6 — the single-use boundary (what the Strict Mode gate crosses): APPROVED →
        // CONSUMED exactly once, and the row leaves the pending list.
        assertTrue("first consume must succeed", identity.approvalFlow().consume(requestId))
        assertFalse("second consume must be a no-op", identity.approvalFlow().consume(requestId))
        assertTrue(
            "a consumed request must leave the pending list",
            identity.approvalFlow().pendingFor(pairId, ApprovalRequest.ROLE_REQUESTER).isEmpty(),
        )

        // Stage 7 — AD-18b: B's receive pipeline acked what it consumed.
        val acked = identity.mailboxMessages(uidB).any { doc ->
            !doc.reference.collection("acks").get().await().isEmpty
        }
        assertTrue("B's receive pipeline left no acks (AD-18b)", acked)

        log("RESULT approval=PASS request=$requestId serverClockDelta=${serverClockDelta}ms")
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

    private fun assertWarmSigningWindow(signer: AccountabilitySigner, who: String) {
        val signature = signer.beginSign()
        signature.update("warm-window-probe".toByteArray())
        try {
            signer.finishSign(signature)
        } catch (e: Exception) {
            fail(
                "$who's signing window is cold — tap \"Warm signing window\" in PairingTestActivity " +
                    "(adb shell am start -n com.example.myexodus/.accountability.PairingTestActivity) " +
                    "and re-run. Cause: ${e.message}",
            )
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
        const val REQUEST_MESSAGE = "Let me disable Strict Mode for a trip"
        const val PARTNER_OUTCOME_TIMEOUT_MILLIS = 60_000L

        /** Server-anchored creation should sit within this of the device clock; a larger gap
         *  means the anchor is not the mailbox timestamp (or the device clock is far off). */
        const val SERVER_CLOCK_SANITY_MILLIS = 5 * 60 * 1000L
    }
}

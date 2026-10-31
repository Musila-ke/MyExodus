package com.example.myexodus.accountability.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myexodus.R
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.approval.ApprovalFlow
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.enrollment.PairingFlow
import com.example.myexodus.accountability.enrollment.PairingToken
import com.example.myexodus.accountability.state.ApprovalRequest
import com.example.myexodus.accountability.sync.PartnerNotifications
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
 * biometric window. The pairing also walks the name exchange PairingCeremonyScreen performs
 * (owner ask 2026-09-29: pair users the way a real user pairs), and the resulting responder
 * notification is read back out of the system tray — named for the partner, keyed per pair,
 * generic on the lockscreen (FR-13).
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
        assumePartnerHarness(ep)

        val uidA = FirebaseAuth.getInstance().currentUser?.uid
            ?: error("no signed-in identity — launch the app once (anonymous auth) and re-run")
        val identity = PartnerTestIdentity(
            context = context,
            cekDerivation = ep.cekDerivation(),
            eventCipher = ep.eventCipher(),
            epochStore = ep.identityEpochStore(),
        )
        // A real responder granted this through the system dialog when they first used the app;
        // the test grants it over shell so the tray assertion below never depends on whichever
        // manual grant the device happens to carry.
        grantPostNotifications(context)
        assertTrue(
            "POST_NOTIFICATIONS must be granted for the tray assertion",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )

        AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity.app)
        val uidB = identity.signIn()
        val pairId = EnrollmentTranscript.durablePairIdOf(uidA, uidB)
        log("A=$uidA B=$uidB pair=$pairId")
        pairDevice(ep, identity, uidA, uidB)

        val aRecord = ep.enrollmentDao().getByPairId(pairId) ?: error("A enrollment missing")
        val bRecord = identity.enrollmentDao.getByPairId(pairId) ?: error("B enrollment missing")
        // The name exchange production's ceremony screens perform: each side typed a label and
        // the pairing carried it across — asserted on the persisted rows, not just the args.
        assertEquals("A must see B's chosen label", PARTNER_LABEL_B, aRecord.partnerLabel)
        assertEquals("B must see A's chosen label", MY_LABEL_A, bRecord.partnerLabel)
        log("labels: A sees '${aRecord.partnerLabel}', B sees '${bRecord.partnerLabel}'")

        // Stage 1 — B requests through the production requester half: the request is a chain
        // event, the row is keyed by the canonical id derived from B's own chain position.
        val request = identity.approvalFlow()
            .requestApproval(pairId, REQUEST_MESSAGE, identity.signer.beginSign())
        assertTrue("request not chained: $request", request is ApprovalFlow.RequestResult.Submitted)
        val sequence = identity.store.localHead(pairId, bRecord.mySenderKeyId)!!.lastSequence
        val requestId = ApprovalRequest.eventIdOf(pairId, bRecord.mySenderKeyId, sequence)
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
        // pull.pulledCount sums appends across EVERY enrollment on this device (repeated harness
        // runs leave old pairs behind) and only counts what actually appended — so the strict
        // "exactly 1" this line used to assert tested something the app never promised. The
        // requirement is that B's request arrived, which the responder-row asserts right below
        // prove directly (row present, canonical id, server-anchored deadline).
        assertTrue("A must append B's approval request (pulled=${pull.pulledCount})", pull.pulledCount >= 1)
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

        // Stage 2b — the tray copy a real responder reads: named for the partner, keyed to
        // this pair (§19.5), and generic on the lockscreen (FR-13 redaction).
        val posted = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.id == PartnerNotifications.idFor(PartnerNotifications.NOTIF_APPROVAL_REQUEST, pairId) }
        assertNotNull("A must post an approval-request notification for pair=$pairId", posted)
        assertEquals(
            "the tray copy must name the partner",
            context.getString(R.string.approval_request_title_named, PARTNER_LABEL_B),
            posted!!.notification.extras.getString(Notification.EXTRA_TITLE),
        )
        val publicVersion = posted.notification.publicVersion
        assertNotNull("lockscreen public version must exist (FR-13)", publicVersion)
        assertEquals(
            "the lockscreen public copy must stay generic",
            context.getString(R.string.approval_request_title),
            publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
        )
        log("tray: '${posted.notification.extras.getString(Notification.EXTRA_TITLE)}' (public: generic)")

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
        val aHeadAfterDecision = ep.chainStateStore().localHead(pairId, aRecord.mySenderKeyId)!!.lastSequence

        // Stage 5 — B's production receive pipeline pulls the response and applies it: the
        // REQUESTER row moves, no earlier than the responder's decision.
        val bPull = identity.receiver().pull(pairId, uidB)
        log("B pull: $bPull")
        // A's own activity fans out to every enrollment (ADR #115) — this fresh test pair
        // inherits A's pending journal rows, so A's chain slice here is activity + response,
        // not exactly one event. What must hold: the pull appended A's events, and B's view
        // of A's chain reached the head A had when it chained the response.
        assertTrue("B must append A's approval response (appended=${bPull.appended})", bPull.appended >= 1)
        val bHeadOfA = identity.store.localHead(pairId, aRecord.mySenderKeyId)!!.lastSequence
        assertTrue(
            "B's view of A's chain must hold the response event (B=$bHeadOfA, A=$aHeadAfterDecision)",
            bHeadOfA >= aHeadAfterDecision,
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
        log("pairing: A initiates, B redeems, A confirms — with the name exchange a real user walks")
        val initiation = ep.pairingFlow().initiate(uidA, MY_LABEL_A)
        coroutineScope {
            val partnerOutcome = async(Dispatchers.IO) {
                identity.pairingFlow().complete(
                    initiation.qrBytes,
                    uidB,
                    identity.signer.beginSign(),
                    myLabel = PARTNER_LABEL_B,
                )
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

    /** POST_NOTIFICATIONS over shell — GrantPermissionRule lives in androidx.test:rules, which
     *  this module does not carry, and a real user grants the same permission in the system
     *  dialog. Output is read to EOF so the command has really completed before the assertion. */
    private fun grantPostNotifications(context: Context) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(
            ui.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"),
        ).use { it.readBytes() }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MyExodusPartnerTest"
        const val REQUEST_MESSAGE = "Let me disable Strict Mode for a trip"
        const val PARTNER_OUTCOME_TIMEOUT_MILLIS = 60_000L

        /** The name exchange a real user walks (owner ask 2026-09-29): the harness passes the
         *  same labels PairingCeremonyScreen collects, so the device ends up holding
         *  real-shaped enrollment data and every notification names its partner. */
        const val MY_LABEL_A = "Musila"
        const val PARTNER_LABEL_B = "Mama"

        /** Server-anchored creation should sit within this of the device clock; a larger gap
         *  means the anchor is not the mailbox timestamp (or the device clock is far off). */
        const val SERVER_CLOCK_SANITY_MILLIS = 5 * 60 * 1000L
    }
}

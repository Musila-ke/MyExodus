package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.PartnerTestKeys
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.ui.partner.PartnerDashboardUi
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activity-layer device leg (plan §7 Step 14 / task #77) — the two halves the JVM suite cannot
 * reach, both on the real keystore:
 *
 *  1. **Receive → render** (B → A): the virtual partner sends all five §2.1 activity types through
 *     the production creator + drainer; A pulls through the production mailbox pipeline
 *     (verify → CEK → decrypt → chain append → `PartnerActivityReceiver` projection) and the
 *     EXACT read models the UI renders are asserted — `PartnerDashboardSource` (the Partner tab's
 *     summary card) and the Home card's `observePinnedMessages` Flow query.
 *  2. **Emit → deliver** (A → B, the #111 rails): A emits through the production journal
 *     ([PartnerActivityJournal.emitForProfile] — the same call the Req 15 composer and the domain
 *     transactions make), `syncOnce` reconciles it into a signed envelope with A's REAL device
 *     keystore (the reason this leg needs the warm window), links the pair, drains to B's mailbox,
 *     and B's production receive pipeline ingests + commits it.
 *
 * Honest seam: the virtual partner carries no AppRulesStore, so B's sender half enters at
 * `EventCreator.create` with the payload shape `PartnerSyncCoordinator.toActivityPayload()`
 * builds (same version pair + `stateAfter`) — the journal layer above it is JVM-proven
 * (`PartnerActivityJournalTest`). Leaves A paired with the fresh virtual partner (roster keeps
 * prior harness pairs).
 *
 * Preconditions: app user signed in + warm signing window (PairingTestActivity's "Warm signing
 * window" tap; one 900 s window covers every signature here).
 */
@RunWith(AndroidJUnit4::class)
class PartnerActivityLayerInstrumentedTest {

    @Test
    fun activityLayer_endToEnd_projectsIntoTheRenderModels_andOwnEmissionDelivers(): Unit =
        runBlocking {
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
            harnessLog("activity leg: A=$uidA B=$uidB pair=$pairId")

            // Stage 1 — the labeled pairing run (ADR #106 ceremony, both roles attesting).
            pairDeviceWith(ep, identity, uidA, uidB, "Musila", "Activity Partner")

            // Stage 2 — B emits all five activity types, cumulative snapshots, chain order.
            val t0 = System.currentTimeMillis()
            val message = "You're doing great — keep going"
            val events = listOf(
                activity(EventPayload.TYPE_STREAK_UPDATED, t0 - 4_000, daysFree = 12, milestones = listOf(7)),
                activity(EventPayload.TYPE_RELAPSE_RECORDED, t0 - 3_000, daysFree = 0, relapses = 1, milestones = emptyList()),
                activity(EventPayload.TYPE_MILESTONE_REACHED, t0 - 2_000, daysFree = 30, relapses = 1, milestones = listOf(7, 30)),
                activity(EventPayload.TYPE_COACHING_STEP_UPDATED, t0 - 1_000, daysFree = 30, relapses = 1, milestones = listOf(7, 30), coaching = 3),
                activity(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, t0, daysFree = 30, relapses = 1, milestones = listOf(7, 30), coaching = 3, text = message),
            )
            val creator = identity.eventCreator()
            for (payload in events) {
                val result = creator.create(pairId, payload, identity.signer.beginSign())
                assertTrue("B ${payload.type} create failed: $result", result is EventCreator.CreateResult.Created)
            }
            val drained = identity.outboxDrainer().drain(pairId, uidB, uidA)
            assertTrue(
                "drain must deliver all five events: $drained",
                drained is MailboxOutboxDrainer.DrainResult.Sent && drained.eventsDelivered == 5,
            )

            val report = ep.partnerSyncCoordinator().syncOnce(null)
            harnessLog("A sync (receive leg): $report")

            // Stage 3 — the render read models: Partner tab summary + Home pinned card.
            val storeFactory = EntryPointAccessors
                .fromApplication(context, EnhancedModeTestEntryPoint::class.java)
                .appRulesStoreFactory()
            val dao = storeFactory.open()?.partnerActivityDao() ?: error("rules store unavailable")
            val row = dao.projection(pairId) ?: error("no projection row for $pairId")
            assertEquals("all five events must apply in chain order", 5L, row.lastSequence)
            assertEquals("coaching count must travel (text never does)", 3, row.coachingProgress)

            val ui = ep.partnerDashboardSource().load(System.currentTimeMillis())
            if (ui !is PartnerDashboardUi.Paired) {
                fail("dashboard not Paired: $ui")
                return@runBlocking
            }
            val activity = ui.activity ?: error("summary card has no received aggregate")
            assertEquals("days free from the last snapshot", 30, activity.daysFree)
            assertEquals("relapse count from the last snapshot", 1, activity.relapseCount)
            assertEquals("milestone days decode ascending", listOf(7, 30), activity.milestonesReached)
            assertEquals("pinned message is the Req 15 arrival surface", message, activity.pinnedMessageText)
            assertNotNull("last event occurred must be set once an event applied", activity.lastEventOccurredAtEpochMillis)

            val homePinned = dao.observePinnedMessages().first().firstOrNull()
            assertEquals("the Home card query must surface the same message", message, homePinned?.pinnedMessageText)
            assertEquals("pinned-at is the sender's event wall-clock", t0, homePinned?.pinnedMessageAtMs)

            // Stage 3b — a re-sync is a no-op (processed dedup + the monotonic guard).
            ep.partnerSyncCoordinator().syncOnce(null)
            assertEquals(
                "re-sync must not move the projection",
                5L,
                storeFactory.open()?.partnerActivityDao()?.projection(pairId)?.lastSequence,
            )

            // Stage 4 — A's own emission through the production journal, signed by the REAL
            // device keystore, delivered over the #111 per-pair rails to B.
            val aStore = storeFactory.open() ?: error("rules store unavailable")
            val sentAt = System.currentTimeMillis()
            val sent = PartnerActivityJournal.emitForProfile(
                db = aStore,
                profileId = "activity-leg-profile",
                kind = EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                occurredAtMs = sentAt,
                text = SENT_MESSAGE,
            )
            assertTrue("A journal emission must be accepted", sent)
            val report2 = ep.partnerSyncCoordinator().syncOnce(null)
            harnessLog("A sync (emit leg): $report2")
            assertTrue("A's journal row must link + sign", report2.eventsLinked >= 1)
            assertTrue("A's outbox must drain", report2.drained)

            val aSenderKeyId = identity.enrollmentDao.getByPairId(pairId)?.partnerSigningKeyId
                ?: error("B enrollment missing")
            // Chain length is not the assertion: the app's own PartnerSyncWorker runs alongside
            // this harness, so "B's chain is exactly 1 long" was true only on a device where
            // nothing else ever fired. Assert the state-independent invariants instead — the
            // pull's appends show up as exactly that much chain growth, and A's body is in it.
            val headBefore = identity.store.localHead(pairId, aSenderKeyId)
            val pull = identity.receiver().pull(pairId, uidB)
            harnessLog("B pull: $pull")
            assertTrue("B must ingest A's activity event: $pull", pull.appended >= 1)
            val head = identity.store.localHead(pairId, aSenderKeyId)
            assertNotNull("B must hold A's received chain head", head)
            assertEquals(
                "every appended message must be a chain row (contiguous ingest)",
                pull.appended.toLong(),
                head!!.lastSequence - (headBefore?.lastSequence ?: 0L),
            )
            val enrollmentB = identity.enrollmentDao.getByPairId(pairId) ?: error("B enrollment missing")
            val bCek = ep.cekDerivation().deriveCek(
                pairId,
                PartnerTestKeys.agreementPrivateKey(),
                EnrollmentTranscript.parsePublicKey(enrollmentB.partnerAgreementPubKey),
            )
            val receivedTexts = identity.store.chainFor(pairId).mapNotNull { chainRow ->
                val unsigned = decodeSigned(chainRow.envelope).unsigned
                val sessionKey = ep.cekDerivation().deriveSessionKey(bCek, unsigned.keyEpoch)
                decodePayload(ep.eventCipher().decrypt(sessionKey, unsigned.sequence, unsigned.ciphertext)).text
            }
            assertTrue(
                "A's journal emission must reach B decoded, body intact: $receivedTexts",
                SENT_MESSAGE in receivedTexts,
            )

            harnessLog(
                "RESULT activityLayer=PASS pair=$pairId lastSequence=5 daysFree=30 relapses=1 " +
                    "milestones=[7,30] coaching=3 pinned=\"$message\" bIngestedA=1",
            )
        }

    private companion object {
        /** Stage 4's body — a literal only this leg knows, so finding it in B's decrypted chain
         *  proves the event travelled, not that some other emission happened to land. */
        const val SENT_MESSAGE = "From A through the production journal"
    }

    private fun activity(
        type: String,
        atMs: Long,
        daysFree: Int,
        relapses: Int = 0,
        milestones: List<Int>,
        coaching: Int = 0,
        text: String = "",
    ) = EventPayload(
        type = type,
        text = text,
        protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
        schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
        stateAfter = ActivityStateAfter(
            daysFree = daysFree,
            relapseCount = relapses,
            milestonesReached = milestones,
            coachingProgress = coaching,
            eventOccurredAtMs = atMs,
        ),
    )
}

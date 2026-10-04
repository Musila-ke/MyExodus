package com.example.myexodus.accountability.sync

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.MainActivity
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.PartnerTestIdentity
import com.example.myexodus.accountability.PartnerTestKeys
import com.example.myexodus.accountability.RoundTripTestEntryPoint
import com.example.myexodus.accountability.activity.PartnerActivityJournal
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentTranscript
import com.example.myexodus.accountability.serialization.ActivityStateAfter
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.blocking.EnhancedModeTestEntryPoint
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.ui.partner.PartnerDashboardUi
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *  2. **Emit → deliver** (A → B, the #111 rails): A seeds the v16 consent chain for the fresh
 *     pair (inactive harness profile → habit → share_streak row), emits through the production journal
 *     ([PartnerActivityJournal.emitForProfile] — the same call the Req 15 composer and the domain
 *     transactions make), `syncOnce` reconciles it into a signed envelope with A's REAL device
 *     keystore (the reason this leg needs the warm window), links the pair, drains to B's mailbox,
 *     and B's production receive pipeline ingests + commits it.
 *
 *  3. **The v16 matrix, negative side, and the realtime kick**: a SECOND concurrent virtual
 *     partner pairs with A (own FirebaseApp/auth/Room file); a message addressed to the first
 *     pair must never surface in its mailbox (no consent row = whole matrix default), and its
 *     delivery to B rides the `activityEmitted` in-process kick — the test never calls
 *     `syncOnce` for it, and the periodic worker is 15 minutes away.
 *
 * Honest seam: the virtual partner carries no AppRulesStore, so B's sender half enters at
 * `EventCreator.create` with the payload shape `PartnerSyncCoordinator.toActivityPayload()`
 * builds (same version pair + `stateAfter`) — the journal layer above it is JVM-proven
 * (`PartnerActivityJournalTest`). Leaves A paired with the fresh virtual partners (roster
 * keeps prior harness pairs).
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
            // v16 consent (ADR #135): a message row is gated three times — write-side (some
            // shareStreak row must exist), reconcile (THIS pair needs its own share_streak
            // row) and re-anchor (the pair needs a visible habit or the row drops). Seed the
            // whole chain on a harness-inactive profile, so none of it is user-visible.
            seedConsentChain(aStore, pairId)
            val sentAt = System.currentTimeMillis()
            val sent = PartnerActivityJournal.emitForProfile(
                db = aStore,
                profileId = SEED_PROFILE_ID,
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
            val receivedTexts = chainTexts(identity, pairId, ep.cekDerivation(), ep.eventCipher())
            assertTrue(
                "A's journal emission must reach B decoded, body intact: $receivedTexts",
                SENT_MESSAGE in receivedTexts,
            )

            // Stage 5 — the v16 matrix's negative side: a SECOND concurrent virtual partner
            // (own FirebaseApp/auth/Room file) pairs with A, then A emits a message addressed
            // to the FIRST pair only. pair2 carries no consent row anywhere (v16 default =
            // nothing), so its queue can never consume the row and its mailbox stays empty.
            val identity2 = PartnerTestIdentity(
                context = context,
                cekDerivation = ep.cekDerivation(),
                eventCipher = ep.eventCipher(),
                epochStore = ep.identityEpochStore(),
                appName = B2_APP_NAME,
                dbName = B2_DB_NAME,
            )
            AppCheckTestSupport.useDebugProviderIfRequested(FirebaseApp.getInstance(), identity2.app)
            val uidB2 = identity2.signIn()
            val pairId2 = EnrollmentTranscript.durablePairIdOf(uidA, uidB2)
            pairDeviceWith(ep, identity2, uidA, uidB2, "Musila", "Second Partner")
            harnessLog("matrix leg: A=$uidA B2=$uidB2 pair2=$pairId2")

            assertTrue(
                "a message addressed to pair 1 must be accepted (pair 1 holds its consent row)",
                PartnerActivityJournal.emitForProfile(
                    db = aStore,
                    profileId = SEED_PROFILE_ID,
                    kind = EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                    occurredAtMs = System.currentTimeMillis(),
                    text = SECOND_MESSAGE,
                    targetPairId = pairId,
                ),
            )
            // #14 realtime: no syncOnce in this window — only the journal's activityEmitted
            // kick can move the row, and the periodic worker's 15-minute period cannot
            // explain a sub-minute arrival.
            val kickStart = System.currentTimeMillis()
            var kickMs = -1L
            var kickPulls = 0
            while (kickMs < 0 && System.currentTimeMillis() - kickStart < REALTIME_POLL_MS) {
                kickPulls += identity.receiver().pull(pairId, uidB).appended
                if (SECOND_MESSAGE in chainTexts(identity, pairId, ep.cekDerivation(), ep.eventCipher())) {
                    kickMs = System.currentTimeMillis() - kickStart
                } else {
                    delay(2_000)
                }
            }
            assertTrue("the kick must deliver B's message (pulls=$kickPulls)", kickMs >= 0)
            assertTrue("B must ingest the kicked event", kickPulls >= 1)
            harnessLog("realtime kick: pair-1 message reached B in ${kickMs}ms, pulls=$kickPulls")

            val pull2 = identity2.receiver().pull(pairId2, uidB2)
            harnessLog("unconsented partner pull: $pull2 chain=${identity2.store.chainFor(pairId2).size}")
            assertEquals("an unconsented partner must ingest nothing: $pull2", 0, pull2.appended)
            assertTrue(
                "no chain rows may exist for the unconsented pair",
                identity2.store.chainFor(pairId2).isEmpty(),
            )

            // Toggle-off (layer 1 of 3), on-device: once pair 1's share_streak row is gone the
            // write gate itself must refuse; reseed so the device ends as it started.
            aStore.pairSharingDao().deleteStreakFor(listOf(pairId))
            assertFalse(
                "without a share_streak row the journal must refuse the message",
                PartnerActivityJournal.emitForProfile(
                    db = aStore,
                    profileId = SEED_PROFILE_ID,
                    kind = EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                    occurredAtMs = System.currentTimeMillis(),
                    text = "must never travel",
                    targetPairId = pairId,
                ),
            )
            seedConsentChain(aStore, pairId)

            harnessLog(
                "RESULT activityLayer=PASS pair=$pairId lastSequence=5 daysFree=30 relapses=1 " +
                    "milestones=[7,30] coaching=3 pinned=\"$message\" bIngestedA=1 " +
                    "realtimeKickMs=$kickMs unconsentedAppended=${pull2.appended} b2Chain=0",
            )
        }

    /** Decrypts every chain row [identity] holds for [pairId] (the same CEK walk the receive
     *  pipeline uses) — the test-side view of "what actually arrived, body intact". */
    private suspend fun chainTexts(
        identity: PartnerTestIdentity,
        pairId: String,
        cekDerivation: CekDerivation,
        cipher: EventCipher,
    ): List<String> {
        val enrollment = identity.enrollmentDao.getByPairId(pairId)
            ?: error("partner enrollment missing for $pairId")
        val cek = cekDerivation.deriveCek(
            pairId,
            PartnerTestKeys.agreementPrivateKey(),
            EnrollmentTranscript.parsePublicKey(enrollment.partnerAgreementPubKey),
        )
        return identity.store.chainFor(pairId).mapNotNull { chainRow ->
            val unsigned = decodeSigned(chainRow.envelope).unsigned
            val sessionKey = cekDerivation.deriveSessionKey(cek, unsigned.keyEpoch)
            decodePayload(cipher.decrypt(sessionKey, unsigned.sequence, unsigned.ciphertext)).text
        }
    }

    /**
     * Property #11 / owner mandate 2026-10-04 (realtime), receive half: with MainActivity
     * STARTED, the [PartnerLiveSync] doorbell alone must land a partner's mailbox write —
     * no explicit sync call anywhere in the test after the write. The ON_START foreground
     * pass is drained against an empty mailbox BEFORE the write; the periodic worker is 15
     * minutes away, so a sub-minute projection update isolates the doorbell.
     */
    @Test
    fun doorbell_foregroundMailboxWriteDeliversWithoutAnExplicitPass(): Unit = runBlocking {
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
        pairDeviceWith(ep, identity, uidA, uidB, "Musila", "Doorbell Partner")

        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ON_START foreground pass finish against the still-empty mailbox first.
            delay(8_000L)

            val msg = DOORBELL_MESSAGE
            val t0 = System.currentTimeMillis()
            val created = identity.eventCreator().create(
                pairId,
                activity(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, t0, daysFree = 0, milestones = emptyList(), text = msg),
                identity.signer.beginSign(),
            )
            assertTrue("doorbell event create failed: $created", created is EventCreator.CreateResult.Created)
            val drained = identity.outboxDrainer().drain(pairId, uidB, uidA)
            assertTrue(
                "drain must write B's message to A's mailbox: $drained",
                drained is MailboxOutboxDrainer.DrainResult.Sent,
            )

            val dao = EntryPointAccessors
                .fromApplication(context, EnhancedModeTestEntryPoint::class.java)
                .appRulesStoreFactory().open()?.partnerActivityDao() ?: error("rules store unavailable")
            val start = System.currentTimeMillis()
            var elapsed = -1L
            while (elapsed < 0 && System.currentTimeMillis() - start < DOORBELL_POLL_MS) {
                if (dao.observePinnedMessages().first().any { it.pinnedMessageText == msg }) {
                    elapsed = System.currentTimeMillis() - start
                } else {
                    delay(1_000)
                }
            }
            assertTrue("the doorbell must deliver without an explicit pass: elapsed=$elapsed", elapsed >= 0)
            harnessLog("RESULT doorbell=PASS deliveredInMs=$elapsed pair=$pairId")
        }
    }

    /** v16 (ADR #135): profile → habit → pair consent, the minimum chain a message row needs
     *  to pass the write gate, this pair's reconcile filter and the re-anchor. Idempotent
     *  across re-runs; the profile is inactive, so nothing here surfaces in the app's UI. */
    private suspend fun seedConsentChain(store: AppRulesStore, pairId: String) {
        if (store.userProfileDao().getById(SEED_PROFILE_ID) == null) {
            store.userProfileDao().insert(
                UserProfile(
                    userId = SEED_PROFILE_ID,
                    name = "Activity Leg",
                    email = "activity-leg@example.com",
                    phoneNumber = null,
                    dateOfBirth = 0L,
                    hasAccessibilityPermission = false,
                    hasUsageStatsPermission = false,
                    hasVpnPermission = false,
                    hasNotificationPermission = false,
                    hasDeviceAdminPermission = false,
                    devicePublicKey = null,
                    recoveryEscrowKey = null,
                    isActive = false,
                ),
            )
        }
        if (store.habitProgressDao().getById(SEED_HABIT_ID) == null) {
            store.habitProgressDao().insert(
                HabitProgress(
                    progressId = SEED_HABIT_ID,
                    profileId = SEED_PROFILE_ID,
                    targetIdentifier = "Activity Leg",
                    currentCleanDaysStreak = 0,
                    totalRelapses = 0,
                    accessAttemptsCount = 0,
                    habitDurationYears = 1,
                    onboardingSeverityLevel = "MODERATE",
                    lastRelapseTimestamp = null,
                    assignedPartnershipId = null,
                    isSelected = true,
                ),
            )
        }
        store.pairSharingDao().upsertStreak(
            PairStreakSharing(
                pairId = pairId,
                habitProgressId = SEED_HABIT_ID,
                shareStreak = true,
                shareRelapses = false,
                shareMilestones = false,
                streakEnabledSinceMs = 0,
                relapseEnabledSinceMs = 0,
                milestoneEnabledSinceMs = 0,
            ),
        )
    }

    private companion object {
        /** Stage 4's body — a literal only this leg knows, so finding it in B's decrypted chain
         *  proves the event travelled, not that some other emission happened to land. */
        const val SENT_MESSAGE = "From A through the production journal"
        const val SECOND_MESSAGE = "Second message — pair 1 only"
        const val DOORBELL_MESSAGE = "Doorbell proof — delivered in seconds"
        const val DOORBELL_POLL_MS = 60_000L
        const val B2_APP_NAME = "myexodus-partner-test-b2"
        const val B2_DB_NAME = "partner-test-b2.db"
        const val REALTIME_POLL_MS = 60_000L
        const val SEED_PROFILE_ID = "activity-leg-profile"
        const val SEED_HABIT_ID = "activity-leg-habit"
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

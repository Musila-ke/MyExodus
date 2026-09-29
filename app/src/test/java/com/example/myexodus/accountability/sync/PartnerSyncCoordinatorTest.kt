package com.example.myexodus.accountability.sync

import android.app.Application
import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.EventCreator
import com.example.myexodus.accountability.crypto.AccountabilitySigner
import com.example.myexodus.accountability.crypto.EventCipher
import com.example.myexodus.accountability.crypto.IdentityEpochStore
import com.example.myexodus.accountability.enrollment.CekDerivation
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.accountability.serialization.decodePayload
import com.example.myexodus.accountability.serialization.decodeSigned
import com.example.myexodus.accountability.serialization.eventHash
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainStateStore
import com.example.myexodus.accountability.state.SessionKeyManager
import com.example.myexodus.blocking.AttemptRecorder
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.RecoverySession
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Partner sync (§14): recorded attempts become chain events (only while the biometric auth
 * window is warm) and the outbox drains to the partner mailbox. Covers the plan §6 flow end
 * to end with real crypto: blocked → "blocked:<target>" event, override → "override:<target>",
 * cold window defers (never prompts from background), and plan line 278's idempotency —
 * a second run links nothing and creates no duplicate event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PartnerSyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: AccountabilityDatabase
    private lateinit var store: ChainStateStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recovery: RecoveryManager
    private lateinit var recorder: AttemptRecorder
    private lateinit var coordinator: PartnerSyncCoordinator

    private val cipher = EventCipher()
    private val cekDerivation = CekDerivation()
    private val myAgreement = ecKeyPair()
    private val mySigning = ecKeyPair()
    private val partnerAgreement = ecKeyPair()
    private val partnerSigning = ecKeyPair()
    private val mySenderKeyId = sha256Hex(mySigning.public.encoded)
    private val pairId = "pair-sync-test"

    private val cek = cekDerivation.deriveCek(pairId, myAgreement.private, partnerAgreement.public)

    private var drainCalls = mutableListOf<Triple<String, String, String>>()
    private var pullCalls = mutableListOf<String>()
    private var pullWakeIds = mutableListOf<String?>()
    private var repairCalls = 0
    private var rejectionChecks = mutableListOf<Pair<String, String>>()
    private var rejectionsToReport = 0
    private val stepOrder = mutableListOf<String>()
    private val reachabilityPrunes = mutableListOf<Set<String>>()

    /** §19.5: the uids each pair resolves to. A test adds a second supported user here; a pair
     *  absent from the map models an unresolvable one (its slice is skipped, the rest run). */
    private val uids = mutableMapOf<String, Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        uids[pairId] = "me" to "partner"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, "test_${System.nanoTime()}.db")
            .build()
        store = ChainStateStore(db)
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        recorder = AttemptRecorder(factory)
        coordinator = coordinator(warmSigner())
    }

    @After
    fun tearDown() {
        db.close()
        recovery.resetAllData()
    }

    @Test
    fun unpaired_keepsAttemptsLocalAndCallsNoRemoteStep() = runTest {
        recorder.recordBlocked("r-1", "com.instagram.android")

        val report = coordinator.syncOnce()

        assertEquals(false, report.enrolled)
        assertEquals(0, report.eventsLinked)
        assertEquals("attempt stays local without a pair", 1, pending(pairId).size)
        assertTrue(drainCalls.isEmpty())
        assertTrue(pullCalls.isEmpty())
        assertEquals(0, repairCalls)
    }

    @Test
    fun blockedAttempt_warmWindow_becomesLinkedDrainedEvent() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.instagram.android")

        val report = coordinator.syncOnce()

        assertEquals(true, report.enrolled)
        assertEquals(1, report.eventsLinked)
        assertEquals(
            "linked attempt carries the chain sequence for this pair",
            1L,
            linkSequence(recent().single().id, pairId),
        )
        assertEquals(true, report.drained)
        assertEquals(2, report.drainedCount)
        assertEquals(3, report.pulledCount)
        assertEquals(1, repairCalls)
        assertEquals(2, report.repairRequestsCreated)

        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        assertTrue("partner can verify the event", signer.verify(mySigning.public, eventHash(signed.unsigned), signed.signature))
        val payload = decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("blocked:com.instagram.android", payload.text)

        val (pId, authorUid, partnerUid) = drainCalls.single()
        assertEquals(pairId, pId)
        assertEquals("me", authorUid)
        assertEquals("partner", partnerUid)
        assertEquals(listOf(pairId), pullCalls)
    }

    @Test
    fun overrideAttempt_producesOverridePayload() = runTest {
        enroll()
        recorder.recordOverride("r-1", "com.instagram.android")

        coordinator.syncOnce()

        val payload = decodePayload(
            cipher.decrypt(cek, 1L, decodeSigned(store.chainFor(pairId).single().envelope).unsigned.ciphertext),
        )
        assertEquals("override:com.instagram.android", payload.text)
    }

    @Test
    fun coldWindow_defersEventCreationButStillDrains() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.a")
        coordinator = coordinator(coldSigner())

        val report = coordinator.syncOnce()

        assertEquals(0, report.eventsLinked)
        assertEquals("cold window: attempt stays pending, delivered next run", 1, pending(pairId).size)
        assertTrue("drain is independent of the signing window", report.drained)
        assertEquals(3, report.pulledCount)
    }

    @Test
    fun secondRun_createsNoDuplicateEvent() = runTest {
        enroll()
        recorder.recordBlocked("r-1", "com.a")
        val first = coordinator.syncOnce()
        assertEquals(1, first.eventsLinked)

        val second = coordinator.syncOnce()

        assertEquals(0, second.eventsLinked)
        assertEquals("one event, one delivery — idempotent retry (plan §278)", 1, store.chainFor(pairId).size)
        assertEquals(2, drainCalls.size)
    }

    @Test
    fun drainFailure_doesNotSkipPullOrRepair() = runTest {
        enroll()
        coordinator = coordinator(warmSigner(), drain = MailboxOutboxDrainer.DrainResult.Failed("network down"))

        val report = coordinator.syncOnce()

        assertEquals(false, report.drained)
        assertEquals("pull still ran", 3, report.pulledCount)
        assertEquals(1, repairCalls)
    }

    /** §8 Step 18: the relay-rejection read-back runs before the drain — this pass's own
     *  writes are not yet SENT, so they can never be checked against a relay that has not
     *  run — and its finding rides the report. */
    @Test
    fun rejectionCheck_runsBeforeTheDrain_andItsFindingRidesTheReport() = runTest {
        enroll()
        rejectionsToReport = 2

        val report = coordinator.syncOnce()

        assertEquals(listOf(pairId to "partner"), rejectionChecks)
        assertEquals(2, report.rejectionsFound)
        assertEquals("rejection check precedes the drain", listOf("rejection", "drain"), stepOrder)
        assertEquals("rejections do not disturb the drain", true, report.drained)
    }

    /** §6 step 3 expedited path: the wake's messageId rides the work input into the pull, so
     *  the out-of-order arrival is handled before the watermark query could miss it. */
    @Test
    fun wakeMessageId_isForwardedToThePull() = runTest {
        enroll()

        coordinator.syncOnce("m-42")
        coordinator.syncOnce()

        assertEquals(listOf<String?>("m-42", null), pullWakeIds)
    }

    /** W5: a finished technique session rides the same drain as attempts — linked under the
     *  signing window, payload `recovery:<tech>:<outcome>`, technique + outcome only (INV-13). */
    @Test
    fun recoverySession_warmWindow_becomesLinkedRecoveryEvent() = runTest {
        enroll()
        insertSession()

        val report = coordinator.syncOnce()

        assertEquals(1, report.eventsLinked)
        assertEquals(
            "linked session carries the chain sequence for this pair",
            1L,
            factory.open()!!.recoverySessionDao().linkSequence("s-1", pairId),
        )
        val signed = decodeSigned(store.chainFor(pairId).single().envelope)
        val payload = decodePayload(cipher.decrypt(cek, signed.unsigned.sequence, signed.unsigned.ciphertext))
        assertEquals("recovery:urge_surfing:completed", payload.text)
    }

    /** W5 unpaired case: no pair, no remote step, row keeps its null sequence. */
    @Test
    fun unpaired_recoverySessionStaysLocalAndCallsNoRemoteStep() = runTest {
        insertSession()

        val report = coordinator.syncOnce()

        assertEquals(false, report.enrolled)
        assertEquals(0, report.eventsLinked)
        assertNull(
            "session stays unlinked without a pair",
            factory.open()!!.recoverySessionDao().linkSequence("s-1", pairId),
        )
        assertTrue(drainCalls.isEmpty())
    }

    /** A log-only episode (Req 10 — no technique practised) is born terminal and must never
     *  reach the partner, even from an enrolled device with a warm window. */
    @Test
    fun logOnlySession_isNeverMirrored() = runTest {
        enroll()
        insertSession(technique = RecoverySession.TECHNIQUE_NONE)

        val report = coordinator.syncOnce()

        assertEquals(0, report.eventsLinked)
        assertTrue("no chain event for a technique-less log", store.chainFor(pairId).isEmpty())
    }

    /**
     * §19.5 item 2 at the sync layer: every remote step runs once per enrollment, carrying that
     * enrollment's pairId and its own resolved partner. A second supported user is not skipped
     * because a first exists, and the per-pair counts add up on the report.
     */
    @Test
    fun multiPair_everyRemoteStepRunsPerPair_withItsOwnUids() = runTest {
        val secondPair = "pair-second"
        val secondPartnerSigning = ecKeyPair()
        val secondPartnerAgreement = ecKeyPair()
        enroll()
        enroll(secondPair, partnerSigningKey = secondPartnerSigning.public, partnerAgreementKey = secondPartnerAgreement.public)
        uids[secondPair] = "me" to "partner-two"

        val report = coordinator.syncOnce()

        assertEquals(true, report.enrolled)
        assertEquals("every enrollment resolved", true, report.uidsResolved)
        assertEquals(setOf(pairId, secondPair), drainCalls.map { it.first }.toSet())
        assertEquals(
            setOf(
                Triple(pairId, "me", "partner"),
                Triple(secondPair, "me", "partner-two"),
            ),
            drainCalls.toSet(),
        )
        assertEquals(setOf(pairId, secondPair), pullCalls.toSet())
        assertEquals(setOf(pairId to "partner", secondPair to "partner-two"), rejectionChecks.toSet())
        assertEquals("repair pass ran for both", 2, repairCalls)
        assertEquals(2 * 2, report.drainedCount)
        assertEquals(2 * 3, report.pulledCount)
        assertEquals(2 * 2, report.repairRequestsCreated)
    }

    /**
     * ADR #111 (was defect #52): with N enrollments, ONE recorded attempt and ONE recovery
     * session must mirror to EVERY pair — the pre-v12 single-valued `event_sequence` let the
     * first pair's pass claim the row and every later pair found nothing pending. Each pair's
     * own chain receives its own copy (own sequence, own ciphertext the pair can decrypt), and
     * the second run links nothing (per-pair exactly-once).
     */
    @Test
    fun multiPair_oneActivityRow_mirrorsToEveryPair_exactlyOnce() = runTest {
        val secondPair = "pair-second"
        val secondPartnerSigning = ecKeyPair()
        val secondPartnerAgreement = ecKeyPair()
        val secondCek = cekDerivation.deriveCek(secondPair, myAgreement.private, secondPartnerAgreement.public)
        enroll()
        enroll(secondPair, partnerSigningKey = secondPartnerSigning.public, partnerAgreementKey = secondPartnerAgreement.public)
        uids[secondPair] = "me" to "partner-two"
        recorder.recordBlocked("r-1", "com.instagram.android")
        insertSession()

        val report = coordinator.syncOnce()

        assertEquals("attempt + session, once per pair", 4, report.eventsLinked)
        val attemptId = recent().single().id
        for (pId in listOf(pairId, secondPair)) {
            assertEquals("chain of $pId holds both events", 2, store.chainFor(pId).size)
            assertEquals(1L, linkSequence(attemptId, pId))
            assertEquals(2L, factory.open()!!.recoverySessionDao().linkSequence("s-1", pId))
            assertTrue("nothing left pending for $pId", pending(pId).isEmpty())
        }
        assertEquals(
            "the second pair decrypts the same activity text",
            "blocked:com.instagram.android",
            decodePayload(
                cipher.decrypt(
                    secondCek,
                    1L,
                    decodeSigned(store.chainFor(secondPair).first().envelope).unsigned.ciphertext,
                ),
            ).text,
        )

        val second = coordinator.syncOnce()

        assertEquals("per-pair exactly-once", 0, second.eventsLinked)
        assertEquals(2, store.chainFor(pairId).size)
        assertEquals(2, store.chainFor(secondPair).size)
    }

    /** An unresolvable second pair degrades to "that slice was skipped" — the first pair's
     *  pass still completes, and the report says the resolution was partial. */
    @Test
    fun multiPair_anUnresolvablePairIsSkipped_neverBlockingTheRest() = runTest {
        val secondPair = "pair-second"
        enroll()
        enroll(secondPair, partnerSigningKey = ecKeyPair().public, partnerAgreementKey = ecKeyPair().public)
        // No uids entry for secondPair: resolving it fails, as it would offline/deleted.

        val report = coordinator.syncOnce()

        assertEquals("partial resolution is reported, not hidden", false, report.uidsResolved)
        assertEquals(listOf(pairId), pullCalls)
        assertEquals(listOf(pairId), drainCalls.map { it.first })
    }

    /**
     * Appendix A.2/A.3: a journal row becomes one `TYPE_*` activity event per pair — the wire
     * body carries the kind, the version pair, and the whole `stateAfter` snapshot, and the row
     * is left pending for a pair whose pass ran under a cold window (the row is per-pair, so a
     * delivered pair never hides it from an undelivered one).
     */
    @Test
    fun activityJournal_warmWindow_becomesLinkedActivityEvent_perPair() = runTest {
        val secondPair = "pair-second"
        val secondPartnerSigning = ecKeyPair()
        val secondPartnerAgreement = ecKeyPair()
        val secondCek = cekDerivation.deriveCek(secondPair, myAgreement.private, secondPartnerAgreement.public)
        enroll()
        enroll(secondPair, partnerSigningKey = secondPartnerSigning.public, partnerAgreementKey = secondPartnerAgreement.public)
        uids[secondPair] = "me" to "partner-two"
        insertActivity(eventId = "ev-1", kind = EventPayload.TYPE_MILESTONE_REACHED, daysFree = 30, milestones = "7,30")

        val report = coordinator.syncOnce()

        assertEquals("one row, one event per pair", 2, report.eventsLinked)
        val dao = factory.open()!!.partnerActivityDao()
        for ((pId, cekForPair) in listOf(pairId to cek, secondPair to secondCek)) {
            assertEquals(1L, dao.linkSequence("ev-1", pId))
            assertTrue("pending for $pId is drained", dao.pendingForPair(pId, sinceMs = 0L, limit = 10).isEmpty())
            val payload = decodePayload(
                cipher.decrypt(cekForPair, 1L, decodeSigned(store.chainFor(pId).single().envelope).unsigned.ciphertext),
            )
            assertEquals(EventPayload.TYPE_MILESTONE_REACHED, payload.type)
            assertEquals(EventPayload.ACTIVITY_PROTOCOL_VERSION, payload.protocolVersion)
            assertEquals(EventPayload.ACTIVITY_SCHEMA_VERSION, payload.schemaVersion)
            assertEquals(30, payload.stateAfter!!.daysFree)
            assertEquals(listOf(7, 30), payload.stateAfter!!.milestonesReached)
        }

        val second = coordinator.syncOnce()
        assertEquals("per-pair exactly-once", 0, second.eventsLinked)
    }

    /** §10.6: the motivational body rides `text` verbatim — the receiving projection is what
     *  bound-checks and pins it (never the emitter's job to trim). */
    @Test
    fun motivationalMessage_carriesItsBodyOnTheWire() = runTest {
        enroll()
        insertActivity(eventId = "ev-msg", kind = EventPayload.TYPE_MOTIVATIONAL_MESSAGE, text = "Proud of you.")

        coordinator.syncOnce()

        val payload = decodePayload(
            cipher.decrypt(cek, 1L, decodeSigned(store.chainFor(pairId).single().envelope).unsigned.ciphertext),
        )
        assertEquals(EventPayload.TYPE_MOTIVATIONAL_MESSAGE, payload.type)
        assertEquals("Proud of you.", payload.text)
    }

    /** Mirrors the attempt/session behaviour: a cold window stops the pass, and the row is still
     *  pending for the next warm run (never dropped, never double-delivered). */
    @Test
    fun activityJournal_coldWindow_leavesTheRowPending() = runTest {
        enroll()
        coordinator = coordinator(coldSigner())
        insertActivity(eventId = "ev-cold", kind = EventPayload.TYPE_STREAK_UPDATED)

        val report = coordinator.syncOnce()

        assertEquals(0, report.eventsLinked)
        assertEquals(1, factory.open()!!.partnerActivityDao().pendingForPair(pairId, sinceMs = 0L, limit = 10).size)
        assertNull(factory.open()!!.partnerActivityDao().linkSequence("ev-cold", pairId))
    }

    /**
     * Owner ruling 2026-09-29 (backlog boundary): a pair's fan-out starts at its enrollment
     * time, so a newcomer is never handed the pairing's pre-history — and the bound holds on
     * every queue, not just the journal. Rows stamped at the pairing moment itself are part of
     * the newly paired world (the bound is inclusive), and the excluded rows stay pending
     * locally: bounded, not lost.
     *
     * Times are relative to *now*, not the fixture constant: a backdated row would fall outside
     * the 30-day retention window and the sweep would delete it before reconcile ever ran, which
     * would make this pass for the wrong reason.
     */
    @Test
    fun reconcile_boundsEveryQueueToThePairingMoment() = runTest {
        val pairedAt = System.currentTimeMillis()
        enroll(enrolledAt = pairedAt)
        val store = factory.open()!!
        val attempts = store.attemptLogDao()
        val beforeAttempt = attempts.insert(attemptAt(pairedAt - 1_000L))
        val atAttempt = attempts.insert(attemptAt(pairedAt))
        insertActivity(
            eventId = "ev-before", kind = EventPayload.TYPE_STREAK_UPDATED,
            occurredAtMs = pairedAt - 1_000L, createdAtMs = pairedAt - 1_000L,
        )
        insertActivity(
            eventId = "ev-at", kind = EventPayload.TYPE_STREAK_UPDATED,
            occurredAtMs = pairedAt, createdAtMs = pairedAt,
        )
        insertSession(id = "s-before", startedAt = pairedAt - 1_000L)
        insertSession(id = "s-at", startedAt = pairedAt)

        val report = coordinator.syncOnce()

        assertEquals("exactly the three at-or-after rows are delivered", 3, report.eventsLinked)
        assertNull("pre-pairing attempt is never turned into a chain event", linkSequence(beforeAttempt, pairId))
        assertNotNull("paired-moment attempt is delivered", linkSequence(atAttempt, pairId))
        assertNull(
            "pre-pairing session is never mirrored",
            store.recoverySessionDao().linkSequence("s-before", pairId),
        )
        assertNotNull(store.recoverySessionDao().linkSequence("s-at", pairId))
        assertNull(store.partnerActivityDao().linkSequence("ev-before", pairId))
        assertNotNull(store.partnerActivityDao().linkSequence("ev-at", pairId))
        assertEquals(
            "the excluded rows stay pending locally — bounded, never dropped",
            listOf("ev-before"),
            store.partnerActivityDao().pendingForPair(pairId, sinceMs = 0L, limit = 10).map { it.eventId },
        )
    }

    /** §19.5: with no enrollment there is no partner to carry the row to — it stays local and
     *  the pass calls no remote step (the unpaired-device contract). */
    @Test
    fun activityJournal_unpaired_staysLocal() = runTest {
        insertActivity(eventId = "ev-solo", kind = EventPayload.TYPE_STREAK_UPDATED)

        val report = coordinator.syncOnce()

        assertEquals(false, report.enrolled)
        assertEquals(0, report.eventsLinked)
        assertNull(factory.open()!!.partnerActivityDao().linkSequence("ev-solo", pairId))
    }

    /**
     * Appendix A.3 retention: rows past the 30-day mailbox bound are swept (a state snapshot
     * that old is superseded by any newer event), rows inside it survive, and the sweep runs
     * even on the unpaired device — after the last unpair there is no enrollment left, which is
     * exactly when the gone pair's projection/link state must still be pruned.
     */
    @Test
    fun sweep_prunesExpiredRows_andOrphanedPairState_keepingTheLivePair() = runTest {
        enroll()
        val dao = factory.open()!!.partnerActivityDao()
        val now = System.currentTimeMillis()
        insertActivity(eventId = "ev-old", kind = EventPayload.TYPE_STREAK_UPDATED, createdAtMs = now - 31L * 24 * 60 * 60 * 1000)
        insertActivity(eventId = "ev-fresh", kind = EventPayload.TYPE_STREAK_UPDATED, createdAtMs = now)
        dao.linkEvent("ev-old", "pair-gone", 1L)
        dao.applyStateAfter("pair-gone", 5, 0, "", 0, now, 1L)
        dao.linkEvent("ev-fresh", pairId, 1L)
        dao.applyStateAfter(pairId, 7, 0, "7", 0, now, 1L)

        coordinator.syncOnce()

        // Probed from a pair that has no links at all, so the answer is the journal's own state.
        assertEquals(
            "expired row swept, fresh row survives",
            listOf("ev-fresh"),
            dao.pendingForPair("pair-never", sinceMs = 0L, limit = 10).map { it.eventId },
        )
        assertEquals("live pair's card kept", 7, dao.projection(pairId)!!.daysFree)
        assertNull("orphan link pruned", dao.linkSequence("ev-old", "pair-gone"))
        assertNull("orphan projection pruned", dao.projection("pair-gone"))
    }

    /** The gone pair's rows are pruned even when the unpair was the *last* one — the sweep
     *  deliberately runs before the empty-enrollment early return. */
    @Test
    fun sweep_runsOnAnUnpairedDevice() = runTest {
        val dao = factory.open()!!.partnerActivityDao()
        dao.applyStateAfter("pair-gone", 5, 0, "", 0, 1L, 1L)

        coordinator.syncOnce()

        assertNull(dao.projection("pair-gone"))
    }

    // ---- OPD #30(b): the reachability sweep (see PairingReachabilityStoreTest for the store) ----

    @Test
    fun reachabilityPrune_seesExactlyTheEnrolledPairIds() = runTest {
        enroll()
        enroll(pairId = "pair-two")

        coordinator.syncOnce()

        assertEquals(setOf(pairId, "pair-two"), reachabilityPrunes.single())
    }

    @Test
    fun reachabilityPrune_stillRunsWithNoEnrollmentLeft() = runTest {
        // After the last unpair there is no enrollment — which is exactly when the orphaned
        // flags for the gone pairs must still be dropped.
        coordinator.syncOnce()

        assertEquals(setOf<String>(), reachabilityPrunes.single())
    }

    // ---- helpers ----

    // Defaults are "just written": a fixture timestamped years in the past is a row the
    // 30-day retention sweep deletes before reconcile can emit it (the sweep runs first in
    // syncOnce), which would make every emission test fail for the wrong reason.
    private suspend fun insertActivity(
        eventId: String,
        kind: String,
        text: String = "",
        occurredAtMs: Long = System.currentTimeMillis(),
        createdAtMs: Long = occurredAtMs,
        daysFree: Int = 3,
        relapseCount: Int = 0,
        milestones: String = "",
        coachingProgress: Int = 0,
    ) = factory.open()!!.partnerActivityDao().insertJournal(
        PartnerActivityOutbox(
            eventId = eventId,
            kind = kind,
            text = text,
            occurredAtMs = occurredAtMs,
            daysFree = daysFree,
            relapseCount = relapseCount,
            milestonesReached = milestones,
            coachingProgress = coachingProgress,
            protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
            schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
            createdAtMs = createdAtMs,
        ),
    )

    private fun coordinator(
        signer: AccountabilitySigner,
        drain: MailboxOutboxDrainer.DrainResult = MailboxOutboxDrainer.DrainResult.Sent(2),
    ) = PartnerSyncCoordinator(
        enrollmentDao = db.enrollmentDao(),
        storeFactory = factory,
        creator = EventCreator(
            store = store,
            enrollmentDao = db.enrollmentDao(),
            cekDerivation = cekDerivation,
            eventCipher = cipher,
            signer = signer,
            epochStore = IdentityEpochStore(db.identityEpochDao()),
            agreementPrivateKey = { myAgreement.private },
            sessionKeyManager = SessionKeyManager(store),
        ),
        signer = signer,
        resolveUids = { pId -> uids[pId] },
        drainOutbox = { pId, authorUid, partnerUid ->
            stepOrder += "drain"
            drainCalls += Triple(pId, authorUid, partnerUid)
            drain
        },
        checkRejections = { pId, partnerUid ->
            stepOrder += "rejection"
            rejectionChecks += pId to partnerUid
            rejectionsToReport
        },
        pullMailbox = { pId, myUid, messageId ->
            pullCalls += pId
            pullWakeIds += messageId
            FirestoreMailboxReceiver.PullResult(appended = 3)
        },
        repairPass = { repairCalls++; 2 },
        pruneReachability = { reachabilityPrunes += it },
    )

    private fun enroll(
        pairId: String = this.pairId,
        partnerSigningKey: PublicKey = partnerSigning.public,
        partnerAgreementKey: PublicKey = partnerAgreement.public,
        enrolledAt: Long = ENROLLMENT_AT,
    ) = runBlocking {
        db.enrollmentDao().upsert(
            EnrollmentRecord(
                pairId = pairId,
                mySenderKeyId = mySenderKeyId,
                partnerSigningKeyId = sha256Hex(partnerSigningKey.encoded),
                partnerSigningPubKey = partnerSigningKey.encoded,
                partnerAgreementPubKey = partnerAgreementKey.encoded,
                createdAtEpochMillis = enrolledAt,
            ),
        )
    }

    private fun warmSigner() = SignerFake(privateKey = mySigning.private)

    private fun coldSigner() = SignerFake(privateKey = mySigning.private, cold = true)

    private val signer = warmSigner()

    private suspend fun pending(pairId: String) =
        factory.open()!!.attemptLogDao().pendingEventCreation(pairId, ENROLLMENT_AT, 10)

    private suspend fun linkSequence(attemptId: Long, pairId: String) =
        factory.open()!!.attemptLogDao().linkSequence(attemptId, pairId)

    private suspend fun insertSession(
        id: String = "s-1",
        technique: String = "urge_surfing",
        state: String = RecoverySession.STATE_COMPLETED,
        startedAt: Long = ENROLLMENT_AT,
    ) = factory.open()!!.recoverySessionDao().insert(
        RecoverySession(
            id = id,
            technique = technique,
            state = state,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = startedAt + 60_000,
            durationSec = 60,
            zoneId = "Africa/Nairobi",
            entry = RecoverySession.ENTRY_TAB,
        ),
    )

    private suspend fun recent() = factory.open()!!.attemptLogDao().recent(1)

    private fun attemptAt(at: Long) = AttemptLog(
        occurredAtEpochMillis = at,
        ruleId = "r-1",
        blockedTarget = "com.instagram.android",
        outcome = AttemptLog.OUTCOME_BLOCKED,
    )

    /** Stands in for the Keystore boundary: warm returns a plain JCA Signature over the test
     *  keypair; cold throws exactly what AndroidKeyStore throws after the 900s window lapses. */
    private class SignerFake(
        private val privateKey: PrivateKey,
        private val cold: Boolean = false,
    ) : AccountabilitySigner() {
        override fun beginSign(): Signature {
            if (cold) throw UserNotAuthenticatedException()
            return Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey) }
        }
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    companion object {
        /** Fixture pairing time: every hand-built row is stamped at or after it, so the
         *  enrollment bound is exercised rather than bypassed. */
        private const val ENROLLMENT_AT = 1_700_000_000_000L

        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

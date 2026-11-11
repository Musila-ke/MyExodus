package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attempt-log semantics (blocking-core plan §6): every enforced block and forced override is
 * recorded durably (the local source of truth, §14); pendingEventCreation surfaces only
 * attempts not yet mirrored to the queried pair (ADR #111), oldest first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttemptLogTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var recorder: AttemptRecorder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        recorder = AttemptRecorder(factory, context)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    /**
     * §8.9: with the App Lock vault closed the attempt log is unreachable, so the block waits
     * in the bounded outbox and lands exactly once on the first opened flush — the replay is
     * idempotent through the same 30 s dedupe the live path uses.
     */
    @Test
    fun lockedVault_holdsAttemptsAndFlushesExactlyOnce() = runTest {
        context.getSharedPreferences("myexodus_attempt_outbox", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
        val gated = AppRulesStoreFactory(
            context, recovery, DatabaseHealthStore(), vaultAccessAllowed = { allowed.get() },
        )
        val gatedRecorder = AttemptRecorder(gated, context)

        gatedRecorder.recordBlocked("r-1", "com.locked")
        assertEquals("the locked block must be held, not dropped", 1, outboxCount())

        allowed.set(true)
        gatedRecorder.flushPending()
        gatedRecorder.flushPending()

        val rows = gated.open()!!.attemptLogDao().recent(10)
        assertEquals(1, rows.count { it.blockedTarget == "com.locked" })
        assertEquals("the outbox drains", 0, outboxCount())
    }

    private fun outboxCount(): Int {
        val raw = context.getSharedPreferences("myexodus_attempt_outbox", Context.MODE_PRIVATE)
            .getString("entries", null) ?: return 0
        return org.json.JSONArray(raw).length()
    }

    @Test
    fun block_isRecordedDurably() = runTest {
        recorder.recordBlocked("r-1", "com.instagram.android")

        val dao = factory.open()!!.attemptLogDao()
        val rows = dao.recent(10)
        assertEquals(1, rows.size)
        assertEquals(AttemptLog.OUTCOME_BLOCKED, rows[0].outcome)
        assertEquals("com.instagram.android", rows[0].blockedTarget)
        assertNull("fresh attempts have no linked event", rows[0].eventSequence)
    }

    @Test
    fun pendingEventCreation_returnsUnlinkedOldestFirst() = runTest {
        recorder.recordBlocked("r-1", "com.a")
        recorder.recordBlocked("r-2", "com.b")

        val dao = factory.open()!!.attemptLogDao()
        dao.linkEvent(dao.recent(10)[1].id, pairId = "pair-a", sequence = 7L)

        val pending = dao.pendingEventCreation("pair-a", sinceMs = 0L, limit = 10)
        assertEquals(1, pending.size)
        assertEquals("com.b", pending[0].blockedTarget)
    }

    /** ADR #111: a row delivered to one pair still pends for every other, and the link records
     *  the chain sequence per pair. Re-linking the same pair is a no-op (exactly-once). */
    @Test
    fun linkEvent_recordsPerPairSequence_withoutHidingTheRowFromOtherPairs() = runTest {
        recorder.recordBlocked("r-1", "com.a")
        val dao = factory.open()!!.attemptLogDao()
        val row = dao.recent(10).single()

        dao.linkEvent(row.id, pairId = "pair-a", sequence = 3L)

        assertEquals(3L, dao.linkSequence(row.id, "pair-a"))
        assertTrue(dao.pendingEventCreation("pair-a", sinceMs = 0L, limit = 10).isEmpty())
        assertEquals(
            "pair-b must still see the row pending",
            listOf(row.id),
            dao.pendingEventCreation("pair-b", sinceMs = 0L, limit = 10).map { it.id },
        )
        assertNull(dao.linkSequence(row.id, "pair-b"))

        val relink = runCatching { dao.linkEvent(row.id, pairId = "pair-a", sequence = 9L) }
        assertTrue("re-linking the same pair must not throw", relink.isSuccess)
        assertEquals("the first delivery's sequence stands", 3L, dao.linkSequence(row.id, "pair-a"))
    }

    /** The pre-v12 marker: `event_sequence` set means the row was delivered once before
     *  per-pair links existed. It is excluded for EVERY pair — the old delivery is never
     *  duplicated, and the historical N−1 gap is accepted (ADR #111). */
    @Test
    fun legacyLinkedRow_isExcludedForEveryPair() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        dao.insert(
            AttemptLog(
                occurredAtEpochMillis = 1_700_000_000_000L,
                ruleId = "r-1",
                blockedTarget = "com.a",
                outcome = AttemptLog.OUTCOME_BLOCKED,
                eventSequence = 7L,
            ),
        )

        assertTrue(dao.pendingEventCreation("pair-a", sinceMs = 0L, limit = 10).isEmpty())
        assertTrue(dao.pendingEventCreation("pair-b", sinceMs = 0L, limit = 10).isEmpty())
    }

    /**
     * Owner ruling 2026-09-29: the fan-out bound is the queried pair's enrollment time — a
     * newcomer is never handed pre-pairing history. [sinceMs] is inclusive: a row stamped
     * exactly at pairing time is part of the newly paired world.
     */
    @Test
    fun pendingEventCreation_boundsToThePairingMoment() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        dao.insert(attempt(at = 1_000L, target = "com.before"))
        dao.insert(attempt(at = 2_000L, target = "com.at-pairing"))
        dao.insert(attempt(at = 3_000L, target = "com.after"))

        val pending = dao.pendingEventCreation("pair-a", sinceMs = 2_000L, limit = 10)

        assertEquals(
            "pre-pairing history is not the newcomer's to see; the pairing-moment row is",
            listOf("com.at-pairing", "com.after"),
            pending.map { it.blockedTarget },
        )
    }

    /** Finding 2026-11-02: the a11y decision and the FGS poll both observe one blocked
     *  launch. The recorder collapses the pair into one attempt; a different rule/target, or
     *  the same pair past the dedupe window, still records; overrides are never deduped. */
    @Test
    fun recordBlocked_collapsesOneLaunchSeenByBothPaths() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        recorder.recordBlocked("r-1", "com.a")
        recorder.recordBlocked("r-1", "com.a")
        assertEquals("the second path's view of the same wall is not a new attempt", 1, dao.count())

        recorder.recordBlocked("r-2", "com.a")
        recorder.recordBlocked("r-1", "com.b")
        assertEquals("a different rule or target is a different attempt", 3, dao.count())
    }

    /** Device 2026-11-02: the AVD clock had once been set a month ahead, leaving rows stamped
     *  in the future. A one-sided dedupe window treated those rows as "just now" and silently
     *  suppressed every later wall; a future stamp is clock skew, not this launch. */
    @Test
    fun recordBlocked_ignoresFutureDatedRows() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        dao.insert(attempt(at = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000, target = "com.a"))

        recorder.recordBlocked("r-1", "com.a")

        assertEquals("a future-stamped row is not evidence of this launch", 2, dao.count())
    }

    /** Owner 2026-11-02: a block the partner could not see until the 15-min worker was the
     *  "no notification on partner" report. A recorded attempt kicks the same immediate pass
     *  every other shared write uses; a dedupe-skip is not a new attempt and must not kick. */
    @Test
    fun recordedAttempt_kicksAPartnerSyncPass() = runTest {
        val kicks = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PartnerSyncSignals.activityEmitted.collect { kicks += it }
        }

        recorder.recordBlocked("r-1", "com.a")
        runCurrent()
        assertEquals(1, kicks.size)

        recorder.recordBlocked("r-1", "com.a") // 30 s dedupe window
        runCurrent()
        assertEquals("a collapsed duplicate is not a new partner-visible attempt", 1, kicks.size)
    }

    /** Owner report 2026-11-10 ("partner got another notification... 2 notifications"): the
     *  FGS retries a cold-start eviction for up to 10 s after the a11y path logged the wall,
     *  so a second observation about 12 s later must still collapse into one episode. */
    @Test
    fun recordBlocked_collapsesTheGraceRetry_intoOneEpisode() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        dao.insert(
            AttemptLog(
                occurredAtEpochMillis = System.currentTimeMillis() - 12_000L,
                ruleId = "r-1",
                blockedTarget = "com.a",
                outcome = AttemptLog.OUTCOME_BLOCKED,
                eventSequence = null,
            ),
        )

        recorder.recordBlocked("r-1", "com.a")

        assertEquals("the grace retry is the same wall episode", 1, dao.count())
    }

    @Test
    fun recordBlocked_recordsAgainOnceTheDedupeWindowHasPassed() = runTest {
        val dao = factory.open()!!.attemptLogDao()
        dao.insert(
            AttemptLog(
                occurredAtEpochMillis = System.currentTimeMillis() - 31_000L,
                ruleId = "r-1",
                blockedTarget = "com.a",
                outcome = AttemptLog.OUTCOME_BLOCKED,
                eventSequence = null,
            ),
        )

        recorder.recordBlocked("r-1", "com.a")

        assertEquals("a deliberate re-open past the episode window is a new attempt", 2, dao.count())
    }

    private fun attempt(at: Long, target: String) = AttemptLog(
        occurredAtEpochMillis = at,
        ruleId = "r-1",
        blockedTarget = target,
        outcome = AttemptLog.OUTCOME_BLOCKED,
        eventSequence = null,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

package com.example.myexodus.recovery

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
import com.example.myexodus.data.db.entity.RecoverySession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v2 FR-5 session machine on the W7 data rail: STARTED → COMPLETED | ABANDONED |
 * INTERRUPTED, first terminal state wins, the sweep only closes stale STARTED rows, and the
 * mirror queue is terminal technique-bearing rows only (a log-only episode is never mirrored).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoverySessionRepositoryTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var sessions: RecoverySessionRepository

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        sessions = RecoverySessionRepository(factory)
    }

    @After
    fun tearDown() = recovery.resetAllData()

    @Test
    fun start_thenFinish_recordsDurationAndTerminalState() = runTest {
        val id = sessions.start("urge_surfing", 7, RecoverySession.ENTRY_TAB, "Africa/Nairobi", now)

        val started = session(id)
        assertEquals(RecoverySession.STATE_STARTED, started.state)
        assertFalse(started.isTerminal)

        sessions.finish(id, RecoverySession.STATE_COMPLETED, intensityEnd = 3, nowEpochMs = now + 120_000)

        val ended = session(id)
        assertEquals(RecoverySession.STATE_COMPLETED, ended.state)
        assertEquals(3, ended.intensityEnd)
        assertEquals(now + 120_000, ended.endedAtEpochMs)
        assertEquals(120, ended.durationSec)
    }

    @Test
    fun secondFinish_isANoOp_firstTerminalStateWins() = runTest {
        val id = sessions.start("delay_timer", null, RecoverySession.ENTRY_TAB, "UTC", now)
        sessions.finish(id, RecoverySession.STATE_ABANDONED, null, now + 30_000)

        sessions.finish(id, RecoverySession.STATE_COMPLETED, 2, now + 60_000)

        assertEquals(
            "a UI end racing the sweep must not relabel a finished session",
            RecoverySession.STATE_ABANDONED,
            session(id).state,
        )
    }

    @Test
    fun finish_refusesNonTerminalStates() = runTest {
        val id = sessions.start("sober", null, RecoverySession.ENTRY_TAB, "UTC", now)

        val started = runCatching { sessions.finish(id, RecoverySession.STATE_STARTED, null, now) }
        val interrupted = runCatching { sessions.finish(id, RecoverySession.STATE_INTERRUPTED, null, now) }

        assertTrue("STARTED is not an end", started.exceptionOrNull() is IllegalArgumentException)
        assertTrue("INTERRUPTED belongs to the sweep", interrupted.exceptionOrNull() is IllegalArgumentException)
        assertEquals(RecoverySession.STATE_STARTED, session(id).state)
    }

    @Test
    fun logOnly_isBornTerminal_andNeverMirrorable() = runTest {
        sessions.logOnly(4, RecoverySession.ENTRY_TAB, "UTC", now)

        val logged = sessions.page(10, 0).single()
        assertEquals(RecoverySession.TECHNIQUE_NONE, logged.technique)
        assertEquals(RecoverySession.STATE_COMPLETED, logged.state)
        assertEquals(0, logged.durationSec)
        assertTrue(
            "no technique practised — nothing to mirror",
            sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10).isEmpty(),
        )
    }

    @Test
    fun assistRow_keepsCategoryActionAndCheckIn_andIsNeverMirrored() = runTest {
        val id = sessions.start(
            RecoverySession.TECHNIQUE_NONE,
            intensityStart = null,
            entry = RecoverySession.ENTRY_BLOCK_SCREEN,
            zoneId = "UTC",
            nowEpochMs = now,
            category = UrgeCategory.PORN.id,
            actionId = UrgeCatalog.BREATHING,
        )

        sessions.finish(
            id,
            RecoverySession.STATE_COMPLETED,
            intensityEnd = null,
            nowEpochMs = now + 90_000,
            checkIn = UrgeCheckIn.SAME.id,
        )

        val row = session(id)
        assertEquals(UrgeCategory.PORN.id, row.category)
        assertEquals(UrgeCatalog.BREATHING, row.actionId)
        assertEquals(UrgeCheckIn.SAME.id, row.checkIn)
        assertEquals(RecoverySession.STATE_COMPLETED, row.state)
        assertTrue(
            "an assist-only action must never reach a partner mirror",
            sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10).isEmpty(),
        )
    }

    @Test
    fun interruptStale_closesOnlyStartedRowsPastTheCutoff() = runTest {
        val stale = sessions.start("urge_surfing", 5, RecoverySession.ENTRY_TAB, "UTC", now - 20 * 60_000)
        val fresh = sessions.start("urge_surfing", 5, RecoverySession.ENTRY_TAB, "UTC", now - 5 * 60_000)
        val done = sessions.start("urge_surfing", 5, RecoverySession.ENTRY_TAB, "UTC", now - 30 * 60_000)
        sessions.finish(done, RecoverySession.STATE_COMPLETED, null, now - 29 * 60_000)

        val closed = sessions.interruptStale(now)

        assertEquals(1, closed)
        val rows = sessions.page(10, 0).associateBy { it.id }
        val swept = rows.getValue(stale)
        assertEquals(RecoverySession.STATE_INTERRUPTED, swept.state)
        assertEquals(
            "v2 M2.4 bound: endedAt = startedAt + 15 min",
            now - 20 * 60_000 + RecoverySession.STALE_SESSION_MS,
            swept.endedAtEpochMs,
        )
        assertEquals(15 * 60, swept.durationSec)
        assertEquals(RecoverySession.STATE_STARTED, rows.getValue(fresh).state)
        assertEquals("a terminal row is never re-ended", RecoverySession.STATE_COMPLETED, rows.getValue(done).state)
    }

    @Test
    fun pendingMirror_queuesTerminalTechniqueRowsOldestFirst_andLinkRemovesThemPerPair() = runTest {
        val older = sessions.start("delay_timer", null, RecoverySession.ENTRY_TAB, "UTC", now - 60_000)
        sessions.finish(older, RecoverySession.STATE_ABANDONED, null, now - 30_000)
        val newer = sessions.start("sober", 7, RecoverySession.ENTRY_TAB, "UTC", now)
        sessions.finish(newer, RecoverySession.STATE_COMPLETED, 3, now + 60_000)
        sessions.start("values_card", null, RecoverySession.ENTRY_TAB, "UTC", now)
        sessions.logOnly(4, RecoverySession.ENTRY_TAB, "UTC", now)

        val queued = sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10)

        assertEquals("live and log-only rows stay out of the mirror queue", listOf(older, newer), queued.map { it.id })

        sessions.linkEvent(older, "pair-a", 42L)

        assertEquals(listOf(newer), sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10).map { it.id })
        assertEquals(
            "ADR #111: the same row is still pending for another pair",
            listOf(older, newer),
            sessions.pendingMirror("pair-b", sinceMs = 0L, limit = 10).map { it.id },
        )
        assertEquals(
            42L,
            factory.open()!!.recoverySessionDao().linkSequence(older, "pair-a"),
        )
        assertNull(
            "the second pair has no link yet",
            factory.open()!!.recoverySessionDao().linkSequence(older, "pair-b"),
        )
    }

    /** Owner ruling 2026-11-07: every row carries the habit that was displayed when it was
     *  written, and both the page and the summary count filter on it. Unlinked rows stay
     *  visible under All habits. */
    @Test
    fun rowsCarryTheirHabit_andPageAndCountsFilterByIt() = runTest {
        sessions.logOnly(3, RecoverySession.ENTRY_TAB, "UTC", now, habitId = "habit-a")
        val technique = sessions.start("sober", null, RecoverySession.ENTRY_TAB, "UTC", now, habitId = "habit-b")
        sessions.finish(technique, RecoverySession.STATE_COMPLETED, null, now + 60_000)
        sessions.logOnly(6, RecoverySession.ENTRY_HOME, "UTC", now + 120_000)
        factory.open()!!.recoverySessionDao().insert(
            RecoverySession(
                id = "s-legacy",
                technique = RecoverySession.TECHNIQUE_NONE,
                state = RecoverySession.STATE_COMPLETED,
                startedAtEpochMs = now - 60_000,
                endedAtEpochMs = now - 60_000,
                durationSec = 0,
                zoneId = "UTC",
                entry = RecoverySession.ENTRY_TAB,
            ),
        )

        assertEquals(
            "null habit = every row, legacy ones included",
            4,
            sessions.page(10, 0).size,
        )
        assertEquals(
            listOf("habit-a"),
            sessions.page(10, 0, habitId = "habit-a").map { it.habitId },
        )
        assertEquals(
            listOf("habit-b"),
            sessions.page(10, 0, habitId = "habit-b").map { it.habitId },
        )
        val all = sessions.countsByStateSince(0L, habitId = null).associateBy { it.technique }
        assertEquals("three bare logs, linked and unlinked alike", 3, all.getValue(RecoverySession.TECHNIQUE_NONE).count)
        assertEquals(1, all.getValue("sober").count)
        assertEquals(
            "the filtered summary counts only the requested habit's rows",
            1,
            sessions.countsByStateSince(0L, habitId = "habit-b").single().count,
        )
    }

    /** W7 per-row deletion: the session and its mirror links go, everything else stays. Links
     *  follow the session so no pair can be handed the event a second time. */
    @Test
    fun delete_removesTheRowAndItsMirrorLinks_andLeavesOtherRowsAlone() = runTest {
        val kept = sessions.start("sober", null, RecoverySession.ENTRY_TAB, "UTC", now - 60_000)
        sessions.finish(kept, RecoverySession.STATE_COMPLETED, null, now - 30_000)
        val doomed = sessions.start("delay_timer", null, RecoverySession.ENTRY_TAB, "UTC", now)
        sessions.finish(doomed, RecoverySession.STATE_COMPLETED, null, now + 60_000)
        sessions.linkEvent(doomed, "pair-a", 7L)

        sessions.delete(doomed)

        assertEquals(listOf(kept), sessions.page(10, 0).map { it.id })
        assertNull(
            "the removed session's link must not outlive it",
            factory.open()!!.recoverySessionDao().linkSequence(doomed, "pair-a"),
        )
        assertEquals(
            "a pair that never received the row sees it disappear with it",
            listOf(kept),
            sessions.pendingMirror("pair-b", sinceMs = 0L, limit = 10).map { it.id },
        )
    }

    /** The pre-v12 marker, recovery side: `event_sequence` set means the row was delivered once
     *  before per-pair links existed — excluded for every pair, never duplicated (ADR #111). */
    @Test
    fun legacyLinkedSession_isExcludedForEveryPair() = runTest {
        factory.open()!!.recoverySessionDao().insert(
            RecoverySession(
                id = "s-legacy",
                technique = "urge_surfing",
                state = RecoverySession.STATE_COMPLETED,
                startedAtEpochMs = now,
                endedAtEpochMs = now + 60_000,
                durationSec = 60,
                zoneId = "UTC",
                entry = RecoverySession.ENTRY_TAB,
                eventSequence = 9L,
            ),
        )

        assertTrue(sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10).isEmpty())
        assertTrue(sessions.pendingMirror("pair-b", sinceMs = 0L, limit = 10).isEmpty())
    }

    /**
     * Owner ruling 2026-09-29: the mirror queue is bounded to the pair's enrollment time — a
     * session practised before the pairing is not mirrored to the newcomer.
     */
    @Test
    fun pendingMirror_boundsToThePairingMoment() = runTest {
        val before = sessions.start("sober", null, RecoverySession.ENTRY_TAB, "UTC", now - 120_000)
        sessions.finish(before, RecoverySession.STATE_COMPLETED, 30, now - 90_000)
        val after = sessions.start("delay_timer", null, RecoverySession.ENTRY_TAB, "UTC", now)
        sessions.finish(after, RecoverySession.STATE_COMPLETED, 10, now + 10_000)

        assertEquals(
            listOf(after),
            sessions.pendingMirror("pair-new", sinceMs = now - 60_000, limit = 10).map { it.id },
        )
    }

    private suspend fun session(id: String): RecoverySession =
        sessions.page(10, 0).first { it.id == id }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

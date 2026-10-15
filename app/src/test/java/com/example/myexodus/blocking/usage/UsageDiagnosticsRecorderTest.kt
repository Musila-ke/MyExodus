package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.telemetry.CrashReporting
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Usage-ledger plan §8.4/§8.7/§8.9 at the recorder boundary, against the real (Robolectric)
 * Room database: the baseline comes from the ledger (never a separate store), a silence past
 * cadence-plus-tolerance becomes exactly one GAP_DETECTED row while tracking is active (untracked
 * silence is expected and heartbeats only), a live pipeline earns a deduplicating heartbeat
 * instead, and escalation (prompt eligibility) is only ever set from a real platform recovery
 * answer — AD-13, no cause is ever asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsageDiagnosticsRecorderTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AppRulesStore
    private lateinit var ledger: UsageLedgerRepository
    private lateinit var state: AccountingDiagnosticsState
    private lateinit var recorder: UsageDiagnosticsRecorder

    /** Fixed monotonic "now" the pass is called with; baseline rows sit relative to it. */
    private val elapsedNow = 10 * 60 * 60 * 1000L
    private val wallNow = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        .plusHours(12).toInstant().toEpochMilli()
    private val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        db = factory.open()!!
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        ledger = UsageLedgerRepository(factory, dispatcher, CrashReporting(context))
        state = AccountingDiagnosticsState(context)
        recorder = UsageDiagnosticsRecorder(ledger, state, CrashReporting(context))
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
        state.clearEligibility()
    }

    // --- §8.4 baseline + §8.7 liveness ---

    @Test
    fun firstPass_noBaseline_noGap_andTheSourceEarnsAHeartbeat() = runBlocking {
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow))

        val rows = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L)
        assertEquals(1, rows.size)
        assertEquals(AccountingDiagnostics.SENTINEL_PACKAGE, rows.single().packageName)
        assertEquals(UsageLedgerSource.PATH_A, rows.single().source)
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
    }

    @Test
    fun recentObservation_noGapAndNoHeartbeat_needed() = runBlocking {
        seedAccounting(endedAtElapsed = elapsedNow - 5_000L, endedAtWall = wallNow - 5_000L)

        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow))

        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
    }

    @Test
    fun staleBaseline_recordsExactlyOneGapRow_andReturnsTheRecoveryWindow() = runBlocking {
        val gap = 10 * 60 * 1000L
        seedAccounting(endedAtElapsed = elapsedNow - gap, endedAtWall = wallNow - gap)

        val request = recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow)

        val gaps = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L)
        assertEquals(1, gaps.size)
        val row = gaps.single()
        assertEquals(UsageLedgerKind.GAP_DETECTED, row.kind)
        assertEquals(0L, row.creditedMs)
        assertEquals(elapsedNow - gap, row.elapsedStartMs)
        assertEquals(elapsedNow, row.elapsedEndMs)
        // The gap row is itself the new baseline anchor — no heartbeat joins it.
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())

        assertNotNull(request!!)
        assertEquals(gap, request.gapSpanMs)
        assertEquals(wallNow, request.window.last + 1)
        assertTrue(request.window.first >= midnight)
        // The recorded elapsed interval is the window mapped into monotonic space — it must end
        // exactly at the observation that closed the gap.
        assertEquals(elapsedNow, request.elapsedToMs)
        assertEquals(elapsedNow - (wallNow - request.window.first), request.elapsedFromMs)
    }

    @Test
    fun afterAGapRow_theNextCycleIsQuiet_untilTheIntervalElapses() = runBlocking {
        seedAccounting(endedAtElapsed = elapsedNow - 10 * 60 * 1000L, endedAtWall = wallNow - 10 * 60 * 1000L)
        recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow)

        // 20 s later the gap row itself is the newest evidence, still inside the liveness
        // interval: neither a new gap nor a heartbeat.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 20_000L, wallNow + 20_000L))
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).size)
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())

        // One interval on, liveness resumes: exactly one heartbeat, still no second gap.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 30_000L, wallNow + 30_000L))
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).size)
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).size)
    }

    @Test
    fun bootMismatch_recordsTheBoundaryGap_andOffersTheWallWindow() = runBlocking {
        // §8.5's reboot flow: the interval spanning the boot is never measured by subtraction.
        val lastSeenWall = wallNow - 10 * 60 * 1000L
        seedAccounting(endedAtElapsed = elapsedNow - 10 * 60 * 1000L, endedAtWall = lastSeenWall, boot = BOOT - 1)

        val request = recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow)

        val row = db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).single()
        // Zero-length: the boundary is recorded, nothing is invented for the dead window, and
        // the row can never be counted as a measured outage (§8.7 surfaces).
        assertEquals(elapsedNow, row.elapsedStartMs)
        assertEquals(elapsedNow, row.elapsedEndMs)
        assertEquals(0L, row.creditedMs)
        assertFalse(AccountingDiagnostics.isMeasuredOutage(row))
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())

        assertNotNull(request!!)
        assertTrue(request.bootBoundary)
        // The window in wall time is (last observation, now]; in elapsed space it can only be
        // the post-boot mapping — the pre-boot part has no frame here.
        assertEquals(lastSeenWall, request.window.first)
        assertEquals(wallNow, request.window.last + 1)
        assertEquals(0L, request.elapsedFromMs)
        assertEquals(elapsedNow, request.elapsedToMs)
        assertEquals(10 * 60 * 1000L, request.gapSpanMs)
    }

    @Test
    fun rebootBoundary_becomesTheNewBaseline_soTheNextCycleIsQuiet() = runBlocking {
        seedAccounting(endedAtElapsed = elapsedNow - 10 * 60 * 1000L, endedAtWall = wallNow - 10 * 60 * 1000L, boot = BOOT - 1)
        recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow)

        // The boundary row is the newest evidence: the new boot is not re-judged, and no
        // heartbeat joins it inside the interval.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 20_000L, wallNow + 20_000L))
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).size)
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())
    }

    @Test
    fun rebootBoundary_fullyBeforeToday_hasNoRecoverableWindow() = runBlocking {
        // Last seen yesterday, now just past midnight: the window clips to midnight, so it is
        // (midnight, now] — and the boundary row is still the record.
        val yesterdayWall = midnight - 60_000L
        seedAccounting(endedAtElapsed = 1_000L, endedAtWall = yesterdayWall, boot = BOOT - 1)
        val justPastMidnight = midnight + 30_000L

        val request = recorder.pass(UsageLedgerSource.PATH_A, BOOT, 30_000L, justPastMidnight)

        assertNotNull(request!!)
        assertEquals(midnight, request.window.first)
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).size)
    }

    @Test
    fun unreadableBootCount_recordsAnAnomaly_andTouchesNothing() = runBlocking {
        seedAccounting(endedAtElapsed = elapsedNow - 10 * 60 * 1000L, endedAtWall = wallNow - 10 * 60 * 1000L)

        // systemBootCount() answers -1 when the setting cannot be read; nothing is attributable
        // to a frame that cannot be named, and nothing is invented to fill in for it.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, -1, elapsedNow, wallNow))

        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).isEmpty())
    }

    @Test
    fun baselineIsMergedAcrossBothPaths_soAnAuthorityHandoffIsNotAnOutage() = runBlocking {
        // Path A's last row is ancient; Path B — now the authority holder — wrote 5 s ago.
        seedAccounting(endedAtElapsed = elapsedNow - 40 * 60 * 1000L, endedAtWall = wallNow - 40 * 60 * 1000L, source = UsageLedgerSource.PATH_A)
        seedAccounting(endedAtElapsed = elapsedNow - 5_000L, endedAtWall = wallNow - 5_000L, source = UsageLedgerSource.PATH_B, elapsedStart = elapsedNow - 10_000L)

        assertNull(recorder.pass(UsageLedgerSource.PATH_B, BOOT, elapsedNow, wallNow))
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
    }

    @Test
    fun heartbeatsAreThrottledToTheInterval_soACloselySpacedPassWritesNothing() = runBlocking {
        recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow)
        // The heartbeat row itself is the baseline: a pass inside the interval has nothing to
        // add. (Even if it did insert, the elapsed bucket makes the event id identical — the
        // repository would report a duplicate.)
        recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 1_000L, wallNow + 1_000L)

        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).size)
    }

    // --- §8.7 under "nothing tracked" (owner-reported false outage, 2026-10-03) ---

    @Test
    fun trackingInactive_silenceRecordsNoOutage_butStillRefreshesTheBaseline() = runBlocking {
        // 18 h of pipeline silence with no tracked rule — the owner's report: the ledger must
        // never describe it as an interruption (the outage surfaces read GAP_DETECTED rows alone).
        val hours = 18 * 60 * 60 * 1000L
        seedAccounting(endedAtElapsed = elapsedNow - hours, endedAtWall = wallNow - hours)

        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow, trackingActive = false))

        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
        // The heartbeat still lands: the baseline advances to now, which is what lets a later
        // activation measure from its own moment instead of inheriting this hole.
        assertEquals(1, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).size)
    }

    @Test
    fun activationAfterAnUntrackedSilence_startsMeasuringFromActivation() = runBlocking {
        val hours = 18 * 60 * 60 * 1000L
        seedAccounting(endedAtElapsed = elapsedNow - hours, endedAtWall = wallNow - hours)
        // While untracked: heartbeat only, no gap row.
        recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow, trackingActive = false)

        // A rule is enabled; the next cycle judges silence only from the fresh baseline, so the
        // pre-tracking hole is invisible to the outage surfaces.
        assertNull(
            recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 30_000L, wallNow + 30_000L, trackingActive = true),
        )

        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
    }

    @Test
    fun trackingInactive_acrossAReboot_writesNoBoundaryRow_butRefreshesTheBoot() = runBlocking {
        seedAccounting(
            endedAtElapsed = elapsedNow - 10 * 60 * 1000L,
            endedAtWall = wallNow - 10 * 60 * 1000L,
            boot = BOOT - 1,
        )

        // No tracked rule → a boot change is not an interruption either; heartbeatDue fires
        // immediately on the mismatch, so the new boot becomes the baseline anyway.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow, wallNow, trackingActive = false))

        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
        assertEquals(BOOT, db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.HEARTBEAT, 0L).single().bootCount)

        // Activation inside the same boot starts from that heartbeat — still no outage.
        assertNull(recorder.pass(UsageLedgerSource.PATH_A, BOOT, elapsedNow + 30_000L, wallNow + 30_000L))
        assertTrue(db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, 0L).isEmpty())
    }

    // --- §8.7 escalation outcome ---

    @Test
    fun recordRecovery_longGapWithNoRecoverableActivity_marksEligible() {
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = 0L, wallNow, bootBoundary = false,
        )

        assertTrue(state.promptEligible(wallNow))
    }

    @Test
    fun recordRecovery_recoverableActivity_clearsEligibility() {
        state.markPromptEligible(wallNow - 1_000L, AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1)
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = 60_000L, wallNow, bootBoundary = false,
        )

        assertFalse(state.promptEligible(wallNow))
    }

    @Test
    fun recordRecovery_unverifiableWindow_neverEscalates() {
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = null, wallNow, bootBoundary = false,
        )

        assertFalse(state.promptEligible(wallNow))
    }

    @Test
    fun recordRecovery_shortGap_neverEscalates() {
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS - 1, recoverableMs = 0L, wallNow, bootBoundary = false,
        )

        assertFalse(state.promptEligible(wallNow))
    }

    @Test
    fun recordRecovery_rebootWindow_neverEscalates_andDoesNotClearAnExistingEligibility() {
        // §8.7: escalation requires the boot count unchanged since the last observation. A
        // reboot window is recovery-only — and it says nothing about an earlier in-boot outage
        // either, so an eligibility already recorded stays.
        state.markPromptEligible(wallNow - 60_000L, AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1)

        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = 0L, wallNow, bootBoundary = true,
        )

        assertTrue(state.promptEligible(wallNow))
    }

    // --- ADR #149: the resolved-through watermark + the offer's own gap evidence ---

    @Test
    fun recordRecovery_numericAnswer_resolvesThroughNow_andStoresTheOfferGap() {
        val gap = AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1
        assertEquals(0L, state.gapsResolvedThroughMs)

        recorder.recordRecovery(gap, recoverableMs = 0L, wallNow, bootBoundary = false)

        assertEquals(wallNow, state.gapsResolvedThroughMs)
        assertEquals(gap, state.promptEligibleGapMs)
    }

    @Test
    fun recordRecovery_unverifiableWindow_leavesTheWatermarkAlone() {
        // No platform answer = the window stays reportable ("may not be counted" is true for it).
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = null, wallNow, bootBoundary = false,
        )

        assertEquals(0L, state.gapsResolvedThroughMs)
    }

    @Test
    fun recordRecovery_rebootWindow_leavesTheWatermarkAlone() {
        // The reboot query's window starts at the newest evidence, so windows *before* it were
        // never queried — resolving through now would hide an old unqueried gap.
        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = 0L, wallNow, bootBoundary = true,
        )

        assertEquals(0L, state.gapsResolvedThroughMs)
    }

    @Test
    fun recordRecovery_ineligibleOutcome_stillResolves_andDropsTheOfferGap() {
        state.markPromptEligible(wallNow - 60_000L, 400_000L)

        recorder.recordRecovery(
            AccountingDiagnostics.ESCALATION_THRESHOLD_MS + 1, recoverableMs = 60_000L, wallNow, bootBoundary = false,
        )

        assertEquals(wallNow, state.gapsResolvedThroughMs)
        assertEquals(0L, state.promptEligibleGapMs)
    }

    @Test
    fun seedResolvedThrough_firesOnlyWhileNothingWasEverResolved() {
        recorder.seedResolvedThrough(wallNow)
        assertEquals(wallNow, state.gapsResolvedThroughMs)

        // Once any resolution exists (seeded or real), the seed is done: it must never keep
        // advancing, because advancing past an unanswered gap would hide an honest report.
        recorder.seedResolvedThrough(wallNow + 60_000L)
        assertEquals(wallNow, state.gapsResolvedThroughMs)

        // And the watermark itself never moves backward.
        state.markGapsResolvedThrough(wallNow - 60_000L)
        assertEquals(wallNow, state.gapsResolvedThroughMs)
    }

    // --- helpers ---

    private suspend fun seedAccounting(
        endedAtElapsed: Long,
        endedAtWall: Long,
        boot: Int = BOOT,
        source: String = UsageLedgerSource.PATH_A,
        elapsedStart: Long = endedAtElapsed - 5_000L,
    ) {
        ledger.appendStandalone(
            LedgerEventDraft(
                kind = UsageLedgerKind.ACCOUNTING,
                packageName = PKG,
                creditedMs = endedAtElapsed - elapsedStart,
                elapsedStartMs = elapsedStart,
                elapsedEndMs = endedAtElapsed,
                bootCount = boot,
                source = source,
                wallClockTsMs = endedAtWall,
            ),
        )
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PKG = "com.instagram.android"
        const val BOOT = 7
    }
}

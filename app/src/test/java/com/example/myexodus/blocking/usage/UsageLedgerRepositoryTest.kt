package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.telemetry.CrashReporting
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * Usage-ledger plan §8.3–§8.13 at the repository boundary, against the real (Robolectric) Room
 * database: idempotent append, collision reporting, the transaction contract, correction
 * semantics, the projection rebuild, retention, and the invariant tests that need a live store
 * (totals from the journal, frozen day keys, diagnostic-row exclusion).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsageLedgerRepositoryTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AppRulesStore
    private lateinit var repo: UsageLedgerRepository

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now().toString()
    private val yesterday = LocalDate.now().minusDays(1).toString()
    private val noon = LocalDate.now().atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()

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
        repo = UsageLedgerRepository(factory, dispatcher, CrashReporting(context))
        runBlocking {
            db.userProfileDao().insert(
                UserProfile(
                    userId = PROFILE_ID, name = "You", email = "", phoneNumber = null, dateOfBirth = 0L,
                    hasAccessibilityPermission = false, hasUsageStatsPermission = false,
                    hasVpnPermission = false, hasNotificationPermission = false,
                    hasDeviceAdminPermission = false, devicePublicKey = null, recoveryEscrowKey = null,
                    isActive = true,
                ),
            )
            db.restrictionRuleDao().insert(
                RestrictionRule(
                    ruleId = RULE_ID, profileId = PROFILE_ID, blockedPackage = PKG, targetType = "APP",
                    isAlwaysOn = false, dailyLimitMs = LIMIT_MS, timeRangeStart = null, timeRangeEnd = null,
                    activeDaysOfWeek = "1111111", blockExpiresAt = 0L, severityLevel = "MEDIUM",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    // --- §8.3 append + §8.6 idempotency ---

    @Test
    fun appendStandalone_writesTheRow_andReportsTheClosingDaysCredit() = runBlocking {
        val outcome = repo.appendStandalone(draft(creditedMs = 5_000L))
        assertEquals(AppendOutcome.Written(today, 5_000L), outcome)
        assertEquals(5_000L, repo.dayTotal(PKG, today))
        val row = db.usageLedgerDao().findByEventId(idFor(0L, 5_000L))!!
        assertEquals(today, row.dayKey)
        assertEquals(UsageLedgerKind.ACCOUNTING, row.kind)
        assertEquals(UsageLedgerSource.PATH_A, row.source)
    }

    @Test
    fun append_sameObservationTwice_isDuplicate_andTheTotalNeverGrows() = runBlocking {
        assertEquals(AppendOutcome.Written(today, 5_000L), repo.appendStandalone(draft()))
        // Invariant #1: a retry — same id, same payload — must never increase the total.
        assertEquals(AppendOutcome.Duplicate, repo.appendStandalone(draft()))
        assertEquals(5_000L, repo.dayTotal(PKG, today))
    }

    @Test
    fun append_sameIdentityNewPayload_isCollision_andChangesNothing() = runBlocking {
        assertEquals(AppendOutcome.Written(today, 5_000L), repo.appendStandalone(draft(creditedMs = 5_000L)))
        // Identity comes from the interval, so a replay with a different credit is a bug, not
        // a retry: reported and rejected, never merged into the day.
        assertEquals(
            AppendOutcome.Collision(idFor(0L, 5_000L)),
            repo.appendStandalone(draft(creditedMs = 4_000L)),
        )
        assertEquals(5_000L, repo.dayTotal(PKG, today))
    }

    @Test
    fun append_rejectsAnInvalidDraft_withoutWriting() = runBlocking {
        assertEquals(AppendOutcome.Rejected("empty package_name"), repo.appendStandalone(draft(packageName = "")))
        assertEquals(AppendOutcome.Rejected("negative credited_ms"), repo.appendStandalone(draft(creditedMs = -1L)))
        assertTrue(db.usageLedgerDao().dayTotals(UsageLedgerKind.CREDITING).isEmpty())
    }

    @Test
    fun append_insideTheCallersTransaction_commitsAndRollsBackWithIt() = runBlocking {
        // §8.3: the journal row and whatever the caller commits move together.
        val committed = db.withWriteTransaction { repo.append(draft()) }
        assertEquals(AppendOutcome.Written(today, 5_000L), committed)
        assertEquals(5_000L, repo.dayTotal(PKG, today))

        var threw = false
        try {
            db.withWriteTransaction {
                repo.append(draft(elapsedStartMs = 50_000L, elapsedEndMs = 55_000L))
                throw IllegalStateException("caller aborted")
            }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertNull(db.usageLedgerDao().findByEventId(idFor(50_000L, 55_000L)))
        assertEquals(5_000L, repo.dayTotal(PKG, today))
    }

    // --- §8.5 day-boundary split through the store ---

    @Test
    fun append_crossingMidnight_writesTwoContiguousRows_whoseCreditsSumExactly() = runBlocking {
        val close = LocalDate.now().atStartOfDay(zone).plusMinutes(10)
        val d = draft(
            creditedMs = 1_200_000L,
            elapsedStartMs = 0L,
            elapsedEndMs = 1_200_000L,
            wallClockTsMs = close.toInstant().toEpochMilli(),
        )
        assertEquals(AppendOutcome.Written(today, 600_000L), repo.appendStandalone(d))

        val first = db.usageLedgerDao().findByEventId(idFor(0L, 600_000L))!!
        val second = db.usageLedgerDao().findByEventId(idFor(600_000L, 1_200_000L))!!
        assertEquals(yesterday, first.dayKey)
        assertEquals(today, second.dayKey)
        // Invariant #9: exactly two rows, contiguous, summing to the original credit.
        assertEquals(first.elapsedEndMs, second.elapsedStartMs)
        assertEquals(1_200_000L, first.creditedMs + second.creditedMs)
        assertEquals(600_000L, repo.dayTotal(PKG, yesterday))
        assertEquals(600_000L, repo.dayTotal(PKG, today))
    }

    // --- §8.12 corrections ---

    @Test
    fun correct_compensatesWithoutTouchingTheTarget_andRejectsUnknownTargets() = runBlocking {
        repo.appendStandalone(draft())
        val targetId = idFor(0L, 5_000L)

        assertEquals(
            AppendOutcome.Rejected("unresolvable references_event_id"),
            repo.correct("no-such-event", -1_000L, noon, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE),
        )

        assertTrue(
            repo.correct(targetId, -2_000L, noon + 60_000, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE)
                is AppendOutcome.Written,
        )
        assertEquals(3_000L, repo.dayTotal(PKG, today))
        // The referenced row is never mutated — the correction is a compensating row.
        assertEquals(5_000L, db.usageLedgerDao().findByEventId(targetId)!!.creditedMs)
        assertEquals(targetId, db.usageLedgerDao().findByEventId(
            UsageLedgerMapper.correctionEventIdFor(targetId, noon + 60_000),
        )!!.referencesEventId)
    }

    @Test
    fun correct_isIdempotentPerInstant_soRetriesLand_butDistinctInstantsBothApply() = runBlocking {
        repo.appendStandalone(draft())
        val targetId = idFor(0L, 5_000L)

        assertTrue(
            repo.correct(targetId, -500L, noon + 60_000, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE)
                is AppendOutcome.Written,
        )
        assertEquals(
            AppendOutcome.Duplicate,
            repo.correct(targetId, -500L, noon + 60_000, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE),
        )
        assertEquals(4_500L, repo.dayTotal(PKG, today))

        assertTrue(
            repo.correct(targetId, -300L, noon + 120_000, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE)
                is AppendOutcome.Written,
        )
        assertEquals(4_200L, repo.dayTotal(PKG, today))
    }

    // --- §8.3 projection rebuild (invariant #2) ---

    @Test
    fun rebuildProjection_restoresEveryTrackerFromTheJournal_andReDerivesBlocked() = runBlocking {
        repo.appendStandalone(draft(creditedMs = 4_000_000L, elapsedEndMs = 4_000_000L))
        repo.appendStandalone(draft(creditedMs = 4_000_000L, elapsedStartMs = 5_000_000L, elapsedEndMs = 9_000_000L))
        // A drifted tracker (under-count and a stale block) and a phantom row for a package
        // with no journal entries at all.
        db.dailyUsageTrackerDao().insert(tracker(ms = 0L, blocked = false))
        db.dailyUsageTrackerDao().insert(tracker(pkg = "com.other", ms = 1_234L, blocked = true))

        assertEquals(2, repo.rebuildProjection())

        val rebuilt = trackerRow()!!
        assertEquals(8_000_000L, rebuilt.msUsedToday)
        // 8_000_000 >= the rule's 2h limit → the block is re-derived, not just the counter.
        assertTrue(rebuilt.blockedToday)
        val phantom = trackerRow(pkg = "com.other")!!
        assertEquals(0L, phantom.msUsedToday)
        assertEquals(false, phantom.blockedToday)
        // Already consistent → nothing rewritten (the rebuild is a fixed point).
        assertEquals(0, repo.rebuildProjection())
    }

    @Test
    fun rebuildProjection_restoresEachTrackerToItsOwnAnchorDay_notToday() = runBlocking {
        // Invariant #10: day_key is frozen at write; the rebuild must use the tracker's own
        // anchor, so yesterday's journal rows never land on today's counter.
        repo.appendStandalone(
            draft(creditedMs = 3_000L, elapsedEndMs = 3_000L, wallClockTsMs = noon - 24 * 3_600_000L),
        )
        repo.appendStandalone(draft(creditedMs = 1_000L, elapsedStartMs = 10_000L, elapsedEndMs = 11_000L))
        db.dailyUsageTrackerDao().insert(tracker(anchor = yesterday, ms = 0L, blocked = false))
        db.dailyUsageTrackerDao().insert(tracker(ms = 0L, blocked = false))

        repo.rebuildProjection()

        assertEquals(3_000L, trackerRow(anchor = yesterday)!!.msUsedToday)
        assertEquals(1_000L, trackerRow()!!.msUsedToday)
    }

    @Test
    fun diagnosticRows_areExcludedFromDayTotals() = runBlocking {
        repo.appendStandalone(draft(creditedMs = 5_000L))
        repo.appendStandalone(draft(kind = UsageLedgerKind.HEARTBEAT, creditedMs = 0L, elapsedStartMs = 6_000L, elapsedEndMs = 6_000L))
        // Deliberately non-zero: the kind filter, not a zero credit, is what excludes it
        // (invariant #3).
        repo.appendStandalone(draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 500L, elapsedStartMs = 7_000L, elapsedEndMs = 8_000L))
        repo.appendStandalone(draft(kind = UsageLedgerKind.RULE_EVALUATION, creditedMs = 0L, elapsedStartMs = 9_000L, elapsedEndMs = 9_000L))

        assertEquals(5_000L, repo.dayTotal(PKG, today))
    }

    // --- §8.4 baseline reads, §8.7 gap evidence ---

    @Test
    fun latestForSource_tracksThatSourcesOwnBaseline() = runBlocking {
        repo.appendStandalone(draft(creditedMs = 1_000L, elapsedStartMs = 0L, elapsedEndMs = 1_000L))
        repo.appendStandalone(
            draft(creditedMs = 1_000L, elapsedStartMs = 2_000L, elapsedEndMs = 3_000L, source = UsageLedgerSource.PATH_B),
        )
        assertEquals(1_000L, repo.latestForSource(UsageLedgerSource.PATH_A)!!.elapsedEndMs)
        assertEquals(3_000L, repo.latestForSource(UsageLedgerSource.PATH_B)!!.elapsedEndMs)
        assertNull(repo.latestForSource("PATH_C"))
    }

    @Test
    fun gapsSince_returnsGapRowsOnly_afterTheCutoff() = runBlocking {
        repo.appendStandalone(draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 0L, elapsedStartMs = 0L, elapsedEndMs = 0L, wallClockTsMs = noon - 7_200_000L))
        repo.appendStandalone(draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 0L, elapsedStartMs = 1L, elapsedEndMs = 1L))
        repo.appendStandalone(draft(kind = UsageLedgerKind.HEARTBEAT, creditedMs = 0L, elapsedStartMs = 2L, elapsedEndMs = 2L))

        val gaps = repo.gapsSince(noon - 3_600_000L)
        assertEquals(1, gaps.size)
        assertEquals(UsageLedgerKind.GAP_DETECTED, gaps.single().kind)
        assertEquals(1L, gaps.single().elapsedStartMs)
    }

    @Test
    fun outageSpansSince_takesMeasuredSilences_onlyInsideTheCutoff() = runBlocking {
        val day = 24 * 3_600_000L
        // A measured interruption: five minutes of silence, fresh.
        repo.appendStandalone(
            draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 0L, elapsedStartMs = 0L, elapsedEndMs = 300_000L, wallClockTsMs = noon - 3_600_000L),
        )
        // The reboot boundary: a zero-length GAP_DETECTED tag, equally fresh — never an outage.
        repo.appendStandalone(
            draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 0L, elapsedStartMs = 300_000L, elapsedEndMs = 300_000L, wallClockTsMs = noon - 2_000_000L),
        )
        // A measured interruption older than the cutoff.
        repo.appendStandalone(
            draft(kind = UsageLedgerKind.GAP_DETECTED, creditedMs = 0L, elapsedStartMs = 0L, elapsedEndMs = 999_000L, wallClockTsMs = noon - 30 * day),
        )

        assertEquals(listOf(300_000L), repo.outageSpansSince(noon - day))
    }

    // --- §8.8 retention (invariant #6) ---

    @Test
    fun prune_deletesOnlyRowsOlderThanTheCutoff_neverTheBoundaryOrNewer() = runBlocking {
        val ninetyDays = LedgerRetention.RETENTION_DAYS * 24 * 60 * 60 * 1000L
        repo.appendStandalone(
            draft(creditedMs = 1_000L, elapsedStartMs = 0L, elapsedEndMs = 1_000L, wallClockTsMs = noon - ninetyDays - 1),
        )
        // Exactly at the cutoff: not older, so it stays (strict <).
        repo.appendStandalone(
            draft(creditedMs = 1_000L, elapsedStartMs = 2_000L, elapsedEndMs = 3_000L, wallClockTsMs = noon - ninetyDays),
        )
        repo.appendStandalone(
            draft(creditedMs = 1_000L, elapsedStartMs = 4_000L, elapsedEndMs = 5_000L, wallClockTsMs = noon),
        )

        assertEquals(1, repo.prune(noon))
        assertNull(db.usageLedgerDao().findByEventId(idFor(0L, 1_000L)))
        assertEquals(0, repo.prune(noon))
        assertEquals(2, db.usageLedgerDao().dayTotals(UsageLedgerKind.CREDITING).size)
    }

    @Test
    fun prune_withASmallBatch_loopsUntilTheBatchComesBackShort() = runBlocking {
        val cut = noon - LedgerRetention.RETENTION_DAYS * 24 * 60 * 60 * 1000L - 1
        repeat(3) { i ->
            repo.appendStandalone(
                draft(creditedMs = 0L, elapsedStartMs = i.toLong(), elapsedEndMs = i.toLong(), wallClockTsMs = cut),
            )
        }
        assertEquals(3, repo.prune(noon, batchSize = 1))
    }

    // --- helpers ---

    private fun idFor(from: Long, to: Long): String =
        UsageLedgerMapper.eventIdFor(UsageLedgerSource.PATH_A, PKG, from, to, BOOT)

    private fun draft(
        creditedMs: Long = 5_000L,
        elapsedStartMs: Long = 0L,
        elapsedEndMs: Long = 5_000L,
        kind: Int = UsageLedgerKind.ACCOUNTING,
        wallClockTsMs: Long = noon,
        source: String = UsageLedgerSource.PATH_A,
        packageName: String = PKG,
    ) = LedgerEventDraft(
        kind = kind,
        packageName = packageName,
        creditedMs = creditedMs,
        elapsedStartMs = elapsedStartMs,
        elapsedEndMs = elapsedEndMs,
        bootCount = BOOT,
        source = source,
        wallClockTsMs = wallClockTsMs,
    )

    private fun tracker(pkg: String = PKG, anchor: String = today, ms: Long = 0L, blocked: Boolean = false) =
        DailyUsageTracker(
            trackerId = UUID.randomUUID().toString(),
            profileId = PROFILE_ID,
            packageName = pkg,
            localDateAnchor = anchor,
            timezoneIdAtAnchor = zone.id,
            msUsedToday = ms,
            warning1State = "NOT_DUE",
            warning2State = "NOT_DUE",
            blockedToday = blocked,
        )

    private suspend fun trackerRow(pkg: String = PKG, anchor: String = today): DailyUsageTracker? =
        db.dailyUsageTrackerDao().findByKey(PROFILE_ID, pkg, anchor)

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        const val PROFILE_ID = "p-1"
        const val RULE_ID = "r-1"
        const val PKG = "com.instagram.android"
        const val BOOT = 7
        const val LIMIT_MS = 7_200_000L // 2h
    }
}

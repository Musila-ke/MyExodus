package com.example.myexodus.blocking.usage

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.telemetry.CrashReporting
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task #47 — the adversarial matrix (user ask 2026-09-27: "stress test, try breaking, think of
 * edge cases as many as possible"). Every test here is one matrix row that the existing suites
 * did *not* already pin: day-boundary equalities, input-domain edges, a timezone move, a
 * correction racing retention, two writers racing one observation, a rule deleted mid-day, a
 * 50-day span, and a seeded randomized stream asserting the tracker↔journal invariant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsageLedgerAdversarialTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var db: AppRulesStore
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var repo: UsageLedgerRepository
    private lateinit var engine: UsageAccountingEngine

    private val nairobi = ZoneId.of("Africa/Nairobi")
    private val noon = ZonedDateTime.of(LocalDateTime.of(2026, 9, 28, 12, 0), nairobi)
        .toInstant().toEpochMilli()
    private val today = "2026-09-28"

    private var wallNowMs = noon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, BOOT)
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        db = factory.open()!!
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        val crashReporting = CrashReporting(context)
        repo = UsageLedgerRepository(factory, dispatcher, crashReporting)
        engine = UsageAccountingEngine(factory, dispatcher, crashReporting, repo, context) { wallNowMs }
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
            db.restrictionRuleDao().insert(rule())
        }
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    // --- day-boundary equalities (the boundary itself, not near it) ---

    @Test
    fun matrix_intervalEndingExactlyAtMidnight_belongsToTheDayItBegan() {
        val midnight = ZonedDateTime.of(LocalDateTime.of(2026, 9, 29, 0, 0, 0), nairobi)
            .toInstant().toEpochMilli()
        val d = draft(creditedMs = 600_000L, elapsedStartMs = 100_000L, elapsedEndMs = 700_000L, wallClockTsMs = midnight)

        val slices = UsageLedgerMapper.splitAtDayBoundary(d, nairobi)

        // [start, end) reaches the boundary but contains no instant of the new day.
        assertEquals(600_000L, slices.sumOf { it.creditedMs })
        assertEquals(today, slices.first { it.creditedMs > 0 }.dayKey)
    }

    @Test
    fun matrix_intervalStartingExactlyAtMidnight_belongsToTheNewDay() {
        val midnight = ZonedDateTime.of(LocalDateTime.of(2026, 9, 29, 0, 0, 0), nairobi)
            .toInstant().toEpochMilli()
        val d = draft(creditedMs = 60_000L, elapsedStartMs = 0L, elapsedEndMs = 60_000L, wallClockTsMs = midnight + 60_000)

        val slices = UsageLedgerMapper.splitAtDayBoundary(d, nairobi)

        assertEquals(1, slices.size)
        assertEquals("2026-09-29", slices.single().dayKey)
    }

    @Test
    fun matrix_fiftyDaySpan_conservesEveryMillisecond_andNeverGoesNegative() {
        // Far past any real interval (gaps are capped at 6 h), but the mapper is public
        // arithmetic: it must conserve the credit even where proportional math is extreme.
        val span = 50L * 24 * 3_600_000L
        val d = draft(creditedMs = span, elapsedStartMs = 0L, elapsedEndMs = span, wallClockTsMs = noon)

        val slices = UsageLedgerMapper.splitAtDayBoundary(d, nairobi)

        assertEquals(span, slices.sumOf { it.creditedMs })
        assertTrue(slices.all { it.creditedMs >= 0 })
    }

    // --- input domain ---

    @Test
    fun matrix_packageNameBoundary_255Accepted_oneByteMoreRejected() {
        val ok = draft(packageName = "a".repeat(255))
        val over = draft(packageName = "a".repeat(256))
        assertEquals(null, UsageLedgerMapper.rejectionReason(ok))
        assertEquals("package_name over 255", UsageLedgerMapper.rejectionReason(over))
    }

    @Test
    fun matrix_creditedAtTheSpanBoundary_accepted_onePastRejected() {
        val at = draft(creditedMs = 5_000L, elapsedStartMs = 0L, elapsedEndMs = 5_000L)
        val past = draft(creditedMs = 5_001L, elapsedStartMs = 0L, elapsedEndMs = 5_000L)
        assertEquals(null, UsageLedgerMapper.rejectionReason(at))
        assertEquals("credited_ms exceeds elapsed span", UsageLedgerMapper.rejectionReason(past))
    }

    // --- timezone move mid-day (AC-9: a written day_key is never rewritten) ---

    @Test
    fun matrix_timezoneMove_keepsWrittenDayKeys_andLosesNoCredit() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi"))
            repo.appendStandalone(
                draft(creditedMs = 60_000L, elapsedStartMs = 0L, elapsedEndMs = 60_000L, wallClockTsMs = noon),
            )
            // Move west across the date line: local date steps back to 2026-09-27.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"))
            repo.appendStandalone(
                draft(creditedMs = 30_000L, elapsedStartMs = 60_000L, elapsedEndMs = 90_000L, wallClockTsMs = noon + 1_000L),
            )
        } finally {
            TimeZone.setDefault(original)
        }

        // The first row's key is frozen at the zone it was written under; the second lands on
        // the moved zone's date. Credit is conserved across the two — never lost, never doubled.
        assertEquals(60_000L, repo.dayTotal(PKG, today))
        assertEquals(30_000L, repo.dayTotal(PKG, "2026-09-27"))
        assertEquals(
            90_000L,
            repo.dayTotal(PKG, today) + repo.dayTotal(PKG, "2026-09-27"),
        )
    }

    // --- retention × corrections ---

    @Test
    fun matrix_correctionAfterItsTargetWasPruned_isRejected_withTheReason() = runBlocking {
        val aged = noon - (LedgerRetention.RETENTION_DAYS + 1) * 24 * 3_600_000L
        repo.appendStandalone(
            draft(creditedMs = 1_000L, elapsedStartMs = 0L, elapsedEndMs = 1_000L, wallClockTsMs = aged),
        )
        val targetId = UsageLedgerMapper.eventIdFor(UsageLedgerSource.PATH_A, PKG, 0L, 1_000L, BOOT)
        assertEquals(1, repo.prune(noon))

        assertEquals(
            AppendOutcome.Rejected("unresolvable references_event_id"),
            repo.correct(targetId, -500L, noon, BOOT, 90_000L, UsageLedgerSource.MAINTENANCE),
        )
    }

    // --- two writers racing one observation ---

    @Test
    fun matrix_twoWritersRacingTheSameObservation_creditExactlyOnce() = runBlocking {
        val factoryDispatcher = Dispatchers.IO.limitedParallelism(1)
        val crashReporting = CrashReporting(context)
        // Two repository instances on independent dispatchers: the shape of PATH_A and PATH_B
        // both delivering the same platform observation.
        val a = UsageLedgerRepository(factory, factoryDispatcher, crashReporting)
        val b = UsageLedgerRepository(factory, Dispatchers.IO.limitedParallelism(1), crashReporting)
        val d = draft(creditedMs = 60_000L, elapsedStartMs = 0L, elapsedEndMs = 60_000L, wallClockTsMs = noon)

        val outcomes = listOf(a, b).map { r -> async { r.appendStandalone(d) } }.awaitAll()

        assertEquals(1, outcomes.count { it is AppendOutcome.Written })
        assertEquals(1, outcomes.count { it is AppendOutcome.Duplicate })
        assertEquals(60_000L, repo.dayTotal(PKG, today))
    }

    // --- rule deleted mid-day, recreated the same day ---

    @Test
    fun matrix_ruleDeletedMidDay_recreatedSameDay_keepsAccruedUsage() = runBlocking {
        engine.processUsageObservation(PKG, 60_000L, 100_000L, UsageLedgerSource.PATH_A)
        assertEquals(60_000L, tracker().msUsedToday)

        db.restrictionRuleDao().delete(rule())
        assertEquals(
            EnforcementAction.NoAction,
            engine.processUsageObservation(PKG, 60_000L, 160_000L, UsageLedgerSource.PATH_A),
        )
        assertEquals(60_000L, tracker().msUsedToday)

        db.restrictionRuleDao().insert(rule())
        engine.processUsageObservation(PKG, 60_000L, 220_000L, UsageLedgerSource.PATH_A)
        assertEquals(120_000L, tracker().msUsedToday)
        assertEquals(120_000L, repo.dayTotal(PKG, today))
    }

    // --- the umbrella invariant, under a seeded randomized stream ---

    @Test
    fun matrix_randomizedStream_trackerStaysEqualToTheJournalSum() = runBlocking {
        val rng = Random(47)
        var elapsed = 0L
        var wall = noon

        repeat(300) { i ->
            when (rng.nextInt(10)) {
                in 0..6 -> { // a real observation
                    val inc = rng.nextInt(60_000) + 1L
                    engine.processUsageObservation(PKG, inc, elapsed, UsageLedgerSource.PATH_A)
                    elapsed += inc
                    wall += inc
                }
                7 -> { // the same tick re-delivered
                    engine.processUsageObservation(PKG, 5_000L, elapsed, UsageLedgerSource.PATH_A)
                    engine.processUsageObservation(PKG, 5_000L, elapsed, UsageLedgerSource.PATH_A)
                    elapsed += 5_000L
                    wall += 5_000L
                }
                8 -> { // a rejected draft must not move the projection
                    repo.appendStandalone(draft(creditedMs = -1L, elapsedStartMs = elapsed, elapsedEndMs = elapsed + 10L))
                    repo.appendStandalone(draft(creditedMs = 10_000L, elapsedStartMs = elapsed, elapsedEndMs = elapsed + 10L))
                    elapsed += 10L
                }
                else -> { // a rule flip: pause, observe (ignored), resume
                    db.restrictionRuleDao().update(rule().copy(isEnabled = false))
                    engine.processUsageObservation(PKG, 7_000L, elapsed, UsageLedgerSource.PATH_A)
                    db.restrictionRuleDao().update(rule().copy(isEnabled = true))
                    elapsed += 7_000L
                }
            }
            if (i % 50 == 0) wall += 1_000L
        }

        val t = tracker()
        assertNotNull(t)
        // Invariant: the live counter equals Σ credit for its own day, computed from the
        // journal — recomputing it via the rebuild path changes nothing.
        assertEquals(repo.dayTotal(PKG, t!!.localDateAnchor), t.msUsedToday)
        assertEquals(0, repo.rebuildProjection())
    }

    @Test
    fun matrix_streamCrossingRealMidnight_rollsTheTracker_andTheJournalHoldsBothDays() = runBlocking {
        // The engine resolves the day from the device zone; pin it so the host zone (UTC in CI)
        // cannot move the midnight instant off the date this fixture asserts.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi"))
            val zone = ZoneId.of("Africa/Nairobi")
            val midnight = LocalDateTime.of(2026, 9, 29, 0, 0, 0).atZone(zone).toInstant().toEpochMilli()
            wallNowMs = midnight - 120_000L // 23:58
            val rng = Random(47)
            var elapsed = 0L

            repeat(120) {
                val inc = rng.nextInt(20_000) + 1L
                wallNowMs += inc
                engine.processUsageObservation(PKG, inc, elapsed, UsageLedgerSource.PATH_A)
                elapsed += inc
                if (rng.nextInt(10) == 0) wallNowMs += 5_000L // idle gaps are not credit
            }

            // The day rolled under the stream: the tracker is anchored to the new day, holds only
            // that day's credit, and the journal is the surviving record of the old one.
            val t = tracker()
            assertEquals("2026-09-29", t.localDateAnchor)
            assertEquals(repo.dayTotal(PKG, "2026-09-29"), t.msUsedToday)
            assertTrue(repo.dayTotal(PKG, "2026-09-28") > 0L)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // --- helpers ---

    private fun rule() = RestrictionRule(
        ruleId = RULE_ID, profileId = PROFILE_ID, blockedPackage = PKG, targetType = "APP",
        isAlwaysOn = false, dailyLimitMs = 30 * 60_000L, timeRangeStart = null, timeRangeEnd = null,
        activeDaysOfWeek = "1111111", blockExpiresAt = 0L, severityLevel = "MEDIUM",
    )

    private suspend fun tracker() =
        db.dailyUsageTrackerDao().findByPackage(PROFILE_ID, PKG)!!

    private fun draft(
        creditedMs: Long = 5_000L,
        elapsedStartMs: Long = 0L,
        elapsedEndMs: Long = 5_000L,
        kind: Int = UsageLedgerKind.ACCOUNTING,
        wallClockTsMs: Long = noon,
        packageName: String = PKG,
    ) = LedgerEventDraft(
        kind = kind,
        packageName = packageName,
        creditedMs = creditedMs,
        elapsedStartMs = elapsedStartMs,
        elapsedEndMs = elapsedEndMs,
        bootCount = BOOT,
        source = UsageLedgerSource.PATH_A,
        wallClockTsMs = wallClockTsMs,
    )

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
    }
}

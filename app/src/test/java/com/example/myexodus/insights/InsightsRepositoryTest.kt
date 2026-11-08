package com.example.myexodus.insights

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
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Insights read model (owner task #22) resolves every journal in one store pass: attempts
 * from `attempt_log`, usage from the ledger's frozen day totals (crediting kinds only), urge
 * practice from `recovery_session`, and each habit's record. The window is seven local days,
 * and the store refusing to open is `null` — never a fabricated all-zero success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InsightsRepositoryTest {

    private val zone = ZoneId.of("Africa/Nairobi")
    private val now: Long = at(LocalDate.of(2026, 10, 22), hour = 12)

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var repository: InsightsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context,
            FakeKeyManager(),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        repository = InsightsRepository(factory)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun load_resolvesEveryJournalInOneSnapshot() = runTest {
        val store = factory.open()!!
        store.attemptLogDao().apply {
            insert(attempt(LocalDate.of(2026, 10, 15), 23, "com.outside", AttemptLog.OUTCOME_BLOCKED))
            insert(attempt(LocalDate.of(2026, 10, 20), 10, "com.instagram.android", AttemptLog.OUTCOME_BLOCKED))
            insert(attempt(LocalDate.of(2026, 10, 20), 11, "com.instagram.android", AttemptLog.OUTCOME_BLOCKED))
            insert(attempt(LocalDate.of(2026, 10, 20), 12, "com.instagram.android", AttemptLog.OUTCOME_OVERRIDE))
            insert(attempt(LocalDate.of(2026, 10, 22), 9, "com.youtube", AttemptLog.OUTCOME_BLOCKED))
        }
        store.usageLedgerDao().apply {
            insert(ledger("ig-1", UsageLedgerKind.ACCOUNTING, "com.instagram.android", "2026-10-20", 600_000))
            insert(ledger("ig-2", UsageLedgerKind.CORRECTION, "com.instagram.android", "2026-10-20", 300_000))
            insert(ledger("ig-heartbeat", UsageLedgerKind.HEARTBEAT, "com.instagram.android", "2026-10-20", 999_999))
            insert(ledger("yt-1", UsageLedgerKind.ACCOUNTING, "com.youtube", "2026-10-21", 1_200_000))
            insert(ledger("old-1", UsageLedgerKind.ACCOUNTING, "com.old", "2026-10-01", 500_000))
            // A zero-total package is not time spent; the day sheet must not list it.
            insert(ledger("zero-1", UsageLedgerKind.ACCOUNTING, "com.zero", "2026-10-20", 0))
        }
        store.recoverySessionDao().apply {
            insert(session("s-1", "box", RecoverySession.STATE_COMPLETED, LocalDate.of(2026, 10, 21)))
            insert(session("s-2", RecoverySession.TECHNIQUE_NONE, RecoverySession.STATE_COMPLETED, LocalDate.of(2026, 10, 21)))
            insert(session("s-3", "box", RecoverySession.STATE_ABANDONED, LocalDate.of(2026, 10, 20)))
            insert(session("s-4", "box", RecoverySession.STATE_INTERRUPTED, LocalDate.of(2026, 10, 19)))
            insert(session("s-old", "box", RecoverySession.STATE_COMPLETED, LocalDate.of(2026, 9, 1)))
        }
        seedProfileWithHabit(store)

        val snapshot = repository.load(now, zone)!!

        assertEquals(7, snapshot.days.size)
        assertEquals(LocalDate.of(2026, 10, 22), snapshot.days.last().date)
        assertEquals(3, snapshot.blockedTotal)
        assertEquals(1, snapshot.overridesTotal)
        val day20 = snapshot.days.first { it.date == LocalDate.of(2026, 10, 20) }
        assertEquals(2, day20.blocked)
        assertEquals(1, day20.overrides)
        // The tap-through detail is the same day's rows, not a second aggregation.
        assertEquals(listOf(InsightTarget("com.instagram.android", 2)), day20.blockedTargets)
        assertEquals(listOf(InsightTarget("com.instagram.android", 1)), day20.overrideTargets)
        assertEquals(listOf(InsightPackage("com.instagram.android", 900_000)), day20.packages)
        assertEquals(
            listOf(InsightTarget("com.youtube", 1)),
            snapshot.days.first { it.date == LocalDate.of(2026, 10, 22) }.blockedTargets,
        )

        assertEquals(listOf("com.instagram.android", "com.youtube"), snapshot.topTargets.map { it.target })
        assertEquals(listOf(2, 1), snapshot.topTargets.map { it.count })

        assertEquals(2_100_000L, snapshot.screenTimeTotalMs)
        assertEquals(0L, snapshot.screenTimeTodayMs)
        assertEquals(listOf("com.youtube", "com.instagram.android"), snapshot.topPackages.map { it.packageName })
        assertEquals(listOf(1_200_000L, 900_000L), snapshot.topPackages.map { it.creditedMs })

        assertEquals(1, snapshot.recovery.completedTechniques)
        assertEquals(1, snapshot.recovery.loggedOnly)
        assertEquals(1, snapshot.recovery.abandoned)
        assertEquals(1, snapshot.recovery.interrupted)
        assertEquals(5L, snapshot.recovery.allTime)

        val habit = snapshot.habits.single()
        assertEquals("h-1", habit.habitId)
        assertEquals("ac", habit.label)
        assertEquals(5L * 24 * 60 * 60 * 1000, habit.bestElapsedMs)
        assertEquals(2, habit.relapses)
        assertFalse(snapshot.isEmpty)
    }

    /** Owner report 2026-10-23 (#54): the usage card's total must be exactly the sum of the
     *  seven bars it renders, and "today" must be the last bar — anything else reads as a
     *  contradiction on screen. Also pins the rolling window's oldest edge: a row on the
     *  seventh day counts, the day before it does not. */
    @Test
    fun load_usageTotalsReconcileWithTheRenderedBars() = runTest {
        val store = factory.open()!!
        store.usageLedgerDao().apply {
            insert(ledger("first-day", UsageLedgerKind.ACCOUNTING, "com.instagram.android", "2026-10-16", 300_000))
            insert(ledger("before-window", UsageLedgerKind.ACCOUNTING, "com.instagram.android", "2026-10-15", 7_200_000))
            insert(ledger("today", UsageLedgerKind.ACCOUNTING, "com.youtube", "2026-10-22", 60_000))
        }

        val snapshot = repository.load(now, zone)!!

        assertEquals(7, snapshot.days.size)
        assertEquals(snapshot.days.sumOf { it.creditedMs }, snapshot.screenTimeTotalMs)
        assertEquals(360_000L, snapshot.screenTimeTotalMs)
        assertEquals(snapshot.days.last().creditedMs, snapshot.screenTimeTodayMs)
        assertEquals(60_000L, snapshot.screenTimeTodayMs)
    }

    @Test
    fun load_emptyStore_isHonestlyEmpty() = runTest {
        val snapshot = repository.load(now, zone)!!

        assertEquals(7, snapshot.days.size)
        assertTrue(snapshot.days.all { it.blocked == 0 && it.overrides == 0 && it.creditedMs == 0L })
        assertEquals(0, snapshot.blockedTotal)
        assertEquals(0L, snapshot.screenTimeTotalMs)
        assertTrue(snapshot.habits.isEmpty())
        assertEquals(0L, snapshot.recovery.allTime)
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun load_topTargetsIsCappedAtThree_withAlphabeticalTieBreak() = runTest {
        val store = factory.open()!!
        store.attemptLogDao().apply {
            insert(attempt(LocalDate.of(2026, 10, 21), 11, "com.delta", AttemptLog.OUTCOME_BLOCKED))
            repeat(3) { insert(attempt(LocalDate.of(2026, 10, 21), 9 + it, "com.bravo", AttemptLog.OUTCOME_BLOCKED)) }
            insert(attempt(LocalDate.of(2026, 10, 21), 9, "com.charlie", AttemptLog.OUTCOME_BLOCKED))
            insert(attempt(LocalDate.of(2026, 10, 21), 10, "com.alpha", AttemptLog.OUTCOME_BLOCKED))
        }

        val snapshot = repository.load(now, zone)!!

        assertEquals(listOf("com.bravo", "com.alpha", "com.charlie"), snapshot.topTargets.map { it.target })
    }

    // --- helpers ---

    private fun at(date: LocalDate, hour: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun attempt(date: LocalDate, hour: Int, target: String, outcome: String) =
        AttemptLog(
            occurredAtEpochMillis = at(date, hour),
            ruleId = "r-$target",
            blockedTarget = target,
            outcome = outcome,
        )

    private fun ledger(
        eventId: String,
        kind: Int,
        packageName: String,
        dayKey: String,
        creditedMs: Long,
    ) = UsageLedger(
        eventId = eventId,
        kind = kind,
        wallClockTsMs = now,
        elapsedStartMs = 0,
        elapsedEndMs = creditedMs,
        bootCount = 1,
        packageName = packageName,
        dayKey = dayKey,
        creditedMs = creditedMs,
        source = UsageLedgerSource.PATH_A,
        sourceVersion = "test",
    )

    private fun session(id: String, technique: String, state: String, date: LocalDate) =
        RecoverySession(
            id = id,
            technique = technique,
            state = state,
            startedAtEpochMs = at(date, 8),
            zoneId = zone.id,
            entry = RecoverySession.ENTRY_TAB,
        )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private suspend fun seedProfileWithHabit(store: AppRulesStore) {
        store.userProfileDao().insert(
            UserProfile(
                userId = "p-1",
                name = "Test",
                email = "t@example.com",
                phoneNumber = null,
                dateOfBirth = 0L,
                hasAccessibilityPermission = false,
                hasUsageStatsPermission = false,
                hasVpnPermission = false,
                hasNotificationPermission = false,
                hasDeviceAdminPermission = false,
                devicePublicKey = null,
                recoveryEscrowKey = null,
                isActive = true,
            ),
        )
        store.habitProgressDao().insert(
            HabitProgress(
                progressId = "h-1",
                profileId = "p-1",
                targetIdentifier = "ac",
                currentCleanDaysStreak = 4,
                totalRelapses = 2,
                accessAttemptsCount = 0,
                habitDurationYears = 1,
                onboardingSeverityLevel = "MILD",
                lastRelapseTimestamp = null,
                assignedPartnershipId = null,
                allTimeMaxStreakElapsedMs = 5L * 24 * 60 * 60 * 1000,
                isSelected = true,
            ),
        )
    }
}

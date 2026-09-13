package com.example.myexodus.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §8.1 poll-scan policy: who may launch BlockerActivity (blocking-core plan §8.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppBlockScannerTest {

    private lateinit var scanner: AppBlockScanner

    private val restrictedRule = Rule(
        ruleId = "r-blocked",
        packageName = "com.target.app",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = "UTC",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

    private fun snapshot(vararg rules: Rule): RulesSnapshot =
        RulesSnapshot(rules.associateBy { it.packageName }, emptyList(), 1L)

    @Before
    fun setUp() {
        val emergency = EmergencyExclusionManager(
            ApplicationProvider.getApplicationContext(),
            roleHolderQuery = { emptyList() },
            dialerQuery = { setOf("com.android.dialer") },
        ).apply { refresh() }
        scanner = AppBlockScanner(BlockDecisionEngine { RulesSnapshot.EMPTY }, emergency)
    }

    @Test
    fun nullOrOwnPackage_neverLaunches() {
        assertEquals(AppBlockDecision.Nothing, scanner.evaluate(null, "com.example.myexodus", snapshot(restrictedRule), 0L))
        assertEquals(
            AppBlockDecision.Nothing,
            scanner.evaluate("com.example.myexodus", "com.example.myexodus", snapshot(restrictedRule), 0L),
        )
    }

    @Test
    fun protectedDialer_neverLaunches() {
        assertEquals(
            AppBlockDecision.Nothing,
            scanner.evaluate("com.android.dialer", "com.example.myexodus", snapshot(restrictedRule), 0L),
        )
    }

    @Test
    fun unrestrictedPackage_neverLaunches() {
        assertEquals(
            AppBlockDecision.Nothing,
            scanner.evaluate("com.other.app", "com.example.myexodus", snapshot(restrictedRule), 0L),
        )
    }

    @Test
    fun restrictedPackage_launchesWithRuleIdAndSeverity() {
        val engine = BlockDecisionEngine { snapshot(restrictedRule) }
        val engineScanner = AppBlockScanner(
            engine,
            EmergencyExclusionManager(
                ApplicationProvider.getApplicationContext(),
                roleHolderQuery = { emptyList() },
                dialerQuery = { emptySet() },
            ),
        )
        val outcome = engineScanner.evaluate("com.target.app", "com.example.myexodus", snapshot(restrictedRule), 1_000L)
        assertTrue(outcome is AppBlockDecision.Launch)
        assertEquals("r-blocked", (outcome as AppBlockDecision.Launch).ruleId)
        assertEquals("HIGH", outcome.severityLevel)
    }

    @Test
    fun expiredRule_neverLaunches() {
        // Rule 3: blockExpiresAt > 0 && now > blockExpiresAt lifts the restriction.
        val expired = restrictedRule.copy(blockExpiresAt = 500L)
        val engine = BlockDecisionEngine { snapshot(expired) }
        val engineScanner = AppBlockScanner(
            engine,
            EmergencyExclusionManager(
                ApplicationProvider.getApplicationContext(),
                roleHolderQuery = { emptyList() },
                dialerQuery = { emptySet() },
            ),
        )
        assertEquals(
            AppBlockDecision.Nothing,
            engineScanner.evaluate("com.target.app", "com.example.myexodus", snapshot(expired), 1_000L),
        )
    }

    @Test
    fun blockDecisionWithoutMatchingSnapshotRule_neverLaunches() {
        // Engine says Block but the snapshot has no rule row (inconsistent snapshot — the
        // scanner must not launch with an unknown ruleId).
        val engine = BlockDecisionEngine { snapshot(restrictedRule) }
        val engineScanner = AppBlockScanner(
            engine,
            EmergencyExclusionManager(
                ApplicationProvider.getApplicationContext(),
                roleHolderQuery = { emptyList() },
                dialerQuery = { emptySet() },
            ),
        )
        assertEquals(
            AppBlockDecision.Nothing,
            engineScanner.evaluate("com.target.app", "com.example.myexodus", RulesSnapshot.EMPTY, 1_000L),
        )
    }
}

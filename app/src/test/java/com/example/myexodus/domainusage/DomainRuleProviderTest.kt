package com.example.myexodus.domainusage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshotPublisher
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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

/** S2.6: the adapter over the published website rules, against a real Room store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DomainRuleProviderTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var publisher: RulesSnapshotPublisher
    private lateinit var provider: DomainRuleProvider
    private val counters = EngineCounters()
    private val psl = PublicSuffixList.parse(listOf("com", "ke", "co.ke"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        publisher = RulesSnapshotPublisher(context, factory)
        provider = DomainRuleProvider(publisher, psl, counters)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun onlyRulesWithAnEnabledLimitAreEmitted() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        dao.insert(urlRule("limited.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        dao.insert(urlRule("nolimit.example.com", ruleId = "r-2", dailyLimitMs = 0L))
        publisher.loadFromDb()

        val rules = provider.snapshot()
        assertEquals(listOf("r-1"), rules.map { it.id })
        assertEquals("limited.example.com", rules.single().pattern)
    }

    @Test
    fun limitOutsideTheRangeIsClampedAndCounted() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        dao.insert(urlRule("a.example.com", ruleId = "r-low", dailyLimitMs = 1_000L))
        dao.insert(urlRule("b.example.com", ruleId = "r-high", dailyLimitMs = 200_000_000L))
        dao.insert(urlRule("c.example.com", ruleId = "r-ok", dailyLimitMs = 3_600_000L))
        publisher.loadFromDb()

        val byId = provider.snapshot().associateBy { it.id }
        assertEquals(MeteringConfig.MIN_DAILY_LIMIT_MS, byId.getValue("r-low").dailyLimitMs)
        assertEquals(MeteringConfig.MAX_DAILY_LIMIT_MS, byId.getValue("r-high").dailyLimitMs)
        assertEquals(3_600_000L, byId.getValue("r-ok").dailyLimitMs)
        assertEquals(2L, counters.get(Counter.rules_invalid))
    }

    @Test
    fun storedHostsThatFailNormalizationAreSkippedAndCounted() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        dao.insert(urlRule("localhost", ruleId = "r-bad", dailyLimitMs = 3_600_000L))
        dao.insert(urlRule("co.ke", ruleId = "r-suffix", dailyLimitMs = 3_600_000L))
        publisher.loadFromDb()

        assertTrue(provider.snapshot().isEmpty())
        assertEquals(2L, counters.get(Counter.rules_invalid))
    }

    @Test
    fun patternChangeIncrementsRevisionAndUnchangedPatternDoesNot() = runTest {
        seedProfile()
        val repository = RulesRepository(factory, publisher)
        publisher.loadFastPath()

        repository.insertRule(urlRule("a.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        assertEquals(0L, provider.snapshot().single().revision)

        repository.updateRule(
            urlRule("a.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L, severityLevel = "LOW"),
        )
        assertEquals("an unrelated edit must not bump the revision", 0L, provider.snapshot().single().revision)

        repository.updateRule(urlRule("b.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        val after = provider.snapshot().single()
        assertEquals(1L, after.revision)
        assertEquals("b.example.com", after.pattern)
    }

    @Test
    fun aSeededRevisionIsAdoptedBeforeTheFirstObservation() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        dao.insert(urlRule("a.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        publisher.loadFromDb()

        provider.seedRevisions(mapOf("r-1" to 4L))

        assertEquals(
            "D-32: a restarted process adopts the persisted high-water mark",
            4L,
            provider.snapshot().single().revision,
        )
    }

    @Test
    fun seedingOnlyEverRaises_andAnEditStartsAboveTheSeed() = runTest {
        seedProfile()
        val repository = RulesRepository(factory, publisher)
        publisher.loadFastPath()
        repository.insertRule(urlRule("a.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        assertEquals(0L, provider.snapshot().single().revision)

        provider.seedRevisions(mapOf("r-1" to 2L, "r-absent" to 9L))
        assertEquals("a seed above the observed value wins", 2L, provider.snapshot().single().revision)

        provider.seedRevisions(mapOf("r-1" to 1L))
        assertEquals("a seed below it never rewinds the revision", 2L, provider.snapshot().single().revision)

        repository.updateRule(urlRule("b.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))
        assertEquals("the edit's new revision starts above the seed", 3L, provider.snapshot().single().revision)
    }

    @Test
    fun enforcedTodayFollowsTheDayScope() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        dao.insert(urlRule("a.example.com", ruleId = "r-today", dailyLimitMs = 3_600_000L, days = "1111111"))
        dao.insert(urlRule("b.example.com", ruleId = "r-offday", dailyLimitMs = 3_600_000L, days = "0000000"))
        publisher.loadFromDb()

        val byId = provider.snapshot().associateBy { it.id }
        assertTrue(byId.getValue("r-today").enforcedToday)
        assertFalse(byId.getValue("r-offday").enforcedToday)
    }

    @Test
    fun expiryIsCarriedOntoTheDomainRule_auditA02() = runTest {
        seedProfile()
        val dao = factory.open()!!.restrictionRuleDao()
        // Far-future constant rather than a wall-clock read: wall-clock calls are forbidden in
        // domainusage test files (S1.4), and the publisher already drops expired rules.
        val expiry = 4_102_444_800_000L // 2100-01-01T00:00:00Z
        dao.insert(urlRule("a.example.com", ruleId = "r-exp", dailyLimitMs = 3_600_000L, blockExpiresAt = expiry))
        publisher.loadFromDb()

        val rule = provider.snapshot().single()
        assertEquals(expiry, rule.expiresAt)
        assertFalse(rule.isExpiredAt(expiry))
        assertTrue(rule.isExpiredAt(expiry + 1))
    }

    @Test
    fun rulesFlowReEmitsWhenTheRuleTablePublishes() = runTest {
        seedProfile()
        val repository = RulesRepository(factory, publisher)
        publisher.loadFastPath()

        val next = async { provider.rules.drop(1).first() }
        repository.insertRule(urlRule("limited.example.com", ruleId = "r-1", dailyLimitMs = 3_600_000L))

        assertEquals(listOf("limited.example.com"), next.await().map { it.pattern })
    }

    // --- helpers ---

    private suspend fun seedProfile() {
        factory.open()!!.userProfileDao().insert(
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
    }

    private fun urlRule(
        host: String,
        ruleId: String,
        dailyLimitMs: Long,
        days: String = "1111111",
        severityLevel: String = "HIGH",
        blockExpiresAt: Long = 0L,
    ) = RestrictionRule(
        ruleId = ruleId,
        profileId = "p-1",
        blockedPackage = host,
        targetType = "WEBSITE",
        isAlwaysOn = false,
        dailyLimitMs = dailyLimitMs,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = days,
        blockExpiresAt = blockExpiresAt,
        severityLevel = severityLevel,
    )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

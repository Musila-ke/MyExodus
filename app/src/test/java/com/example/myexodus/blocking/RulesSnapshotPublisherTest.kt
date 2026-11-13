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
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fast-path + authoritative snapshot publication (plan §8 Step 2) against a real Room store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RulesSnapshotPublisherTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var publisher: RulesSnapshotPublisher
    private lateinit var fakeLegacyKeys: FakeKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeLegacyKeys = FakeKeyManager()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, fakeLegacyKeys, ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        publisher = RulesSnapshotPublisher(context, factory)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun fastPath_noCache_publishesEmptyAndEntersLoading() {
        val published = publisher.loadFastPath()
        assertSame(RulesSnapshot.EMPTY, published)
        assertEquals(SnapshotState.Loading, publisher.state.value)
        assertEquals(0L, published.version)
    }

    @Test
    fun fastPath_withCache_publishesLastKnownGood() = runTest {
        seedProfile()
        val store = factory.open()!!
        store.restrictionRuleDao().insert(appRule("com.instagram.android"))
        publisher.loadFromDb()
        val authoritative = publisher.snapshot
        assertTrue(authoritative.appRules.containsKey("com.instagram.android"))

        // Simulated restart: fresh publisher must restore from the on-disk cache.
        val freshPublisher = RulesSnapshotPublisher(context, factory)
        val restored = freshPublisher.loadFastPath()
        assertEquals(authoritative, restored)
        assertEquals(SnapshotState.Loading, freshPublisher.state.value)
    }

    @Test
    fun fastPath_afterAuthoritative_isNoOp() = runTest {
        seedProfile()
        publisher.loadFastPath()
        publisher.loadFromDb()
        val authoritative = publisher.snapshot
        publisher.loadFastPath()
        assertSame("Fast path must never regress an authoritative snapshot", authoritative, publisher.snapshot)
    }

    @Test
    fun deviceProtected_restoresReadySnapshotAndNeverRegressesAuthoritative() = runTest {
        seedProfile()
        val store = factory.open()!!
        store.restrictionRuleDao().insert(appRule("com.instagram.android"))
        store.restrictionRuleDao().insert(urlRule("betting.example.com"))
        publisher.loadFromDb()
        val authoritative = publisher.snapshot

        // Simulated pre-unlock restart: fresh publisher restores from the DE copy as Ready.
        val freshPublisher = RulesSnapshotPublisher(context, factory)
        val restored = freshPublisher.loadDeviceProtected()
        assertEquals("DE restore must publish the full snapshot (plan §8.5: ids + windows)",
            authoritative, restored)
        assertEquals("DE restore publishes Ready — enforcement data exists pre-unlock",
            SnapshotState.Ready(authoritative.version), freshPublisher.state.value)

        // Pre-unlock publish must not block or regress the post-unlock authoritative load.
        freshPublisher.loadFromDb()
        assertEquals("Authoritative load supersedes the DE restore", authoritative, freshPublisher.snapshot)
        assertNull("loadDeviceProtected is a no-op once anything has been published",
            freshPublisher.loadDeviceProtected())
    }

    @Test
    fun deviceProtected_noCache_returnsNull() {
        val freshPublisher = RulesSnapshotPublisher(context, factory)
        assertNull(freshPublisher.loadDeviceProtected())
        assertEquals(SnapshotState.Uninitialized, freshPublisher.state.value)
    }

    @Test
    fun resetAllData_clearsTheDeviceProtectedSnapshotToo() = runTest {
        // Finding #11 (2026-10-16): the user-confirmed reset destroyed the CE cache but not
        // the §8.5 DE copy, so a wiped install kept the restricted-package list on disk and
        // the next locked boot could enforce pre-reset rules. The reset contract covers both.
        seedProfile()
        factory.open()!!.restrictionRuleDao().insert(appRule("com.instagram.android"))
        publisher.loadFromDb() // writes both cache copies

        recovery.resetAllData()

        // Simulated post-reset reboot: the DE restore must find nothing.
        val freshPublisher = RulesSnapshotPublisher(context, factory)
        assertNull("DE snapshot must not survive a user-confirmed reset",
            freshPublisher.loadDeviceProtected())
        assertEquals(SnapshotState.Uninitialized, freshPublisher.state.value)
    }

    @Test
    fun loadFromDb_publishesActiveProfileRulesWithRoomAuthorityVersion() = runTest {
        seedProfile()
        val store = factory.open()!!
        store.restrictionRuleDao().insert(appRule("com.instagram.android"))
        store.restrictionRuleDao().insert(urlRule("betting.example.com"))

        // Direct DAO writes bypass the version (plan §6: version increments only inside the
        // repository's write transaction), so Room's policy_version stays at the seeded 0.
        publisher.loadFromDb()
        assertEquals(SnapshotState.Ready(0L), publisher.state.value)
        assertEquals(0L, publisher.snapshot.version)
        assertEquals(1, publisher.snapshot.appRules.size)
        assertEquals(1, publisher.snapshot.urlRules.size)

        store.restrictionRuleDao().insert(appRule("com.tiktok.android"))
        publisher.loadFromDb()
        assertEquals(SnapshotState.Ready(0L), publisher.state.value)
        assertEquals(2, publisher.snapshot.appRules.size)
    }

    @Test
    fun loadFromDb_dbReadFails_degradesKeepingLastSnapshot() = runTest {
        // Fast path publishes last-known-good before the store dies.
        seedProfile()
        factory.open()!!.restrictionRuleDao().insert(appRule("com.instagram.android"))
        publisher.loadFastPath()
        publisher.loadFromDb()
        val lastGood = publisher.snapshot

        // Drop the rules table behind Room's back: the next authoritative read must fail.
        androidx.sqlite.driver.AndroidSQLiteDriver().open(recovery.rulesDbFile.absolutePath).use { conn ->
            conn.prepare("DROP TABLE restriction_rules").use { it.step() }
        }
        publisher.loadFromDb()

        val state = publisher.state.value
        assertTrue("Expected Degraded, got $state", state is SnapshotState.Degraded)
        assertEquals("Engine keeps the last published snapshot", lastGood, publisher.snapshot)
    }

    @Test
    fun loadFromDb_cacheWriteFails_stillPublishes_andDoesNotThrow() = runTest {
        // Finding #10 (2026-10-16, B-10 disk-full class): the CE cache write was the one
        // unwrapped disk write — its IOException aborted the publish and escaped into the
        // handler-less fire-and-forget scopes that cold-load this (crash-loop on every boot
        // and service start). Caches are best-effort speed-ups; the publish is the authority.
        seedProfile()
        factory.open()!!.restrictionRuleDao().insert(appRule("com.instagram.android"))
        // Force the write to fail: a directory occupies the temp path writeTo writes to.
        val tmpPath = java.io.File(context.filesDir, "rules_snapshot.json.tmp")
        tmpPath.mkdirs()
        assertTrue("fixture must occupy the temp path", tmpPath.isDirectory)
        try {
            publisher.loadFromDb()
        } finally {
            tmpPath.delete()
        }
        assertEquals(SnapshotState.Ready(0L), publisher.state.value)
        assertTrue(publisher.snapshot.appRules.containsKey("com.instagram.android"))
    }

    @Test
    fun repositoryWrite_republishesAndEnforcesCaps() = runTest {
        seedProfile()
        val repository = RulesRepository(factory, publisher)
        publisher.loadFastPath()

        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(1L, publisher.snapshot.version)
        assertTrue(publisher.snapshot.appRules.containsKey("com.instagram.android"))

        repository.updateRule(appRule("com.instagram.android", ruleId = "r-1", severityLevel = "CRITICAL"))
        assertEquals("CRITICAL", publisher.snapshot.appRules.getValue("com.instagram.android").severityLevel)

        // Caps are enforced against counts; seed to the limit via the DAO, then reject.
        val dao = factory.open()!!.restrictionRuleDao()
        for (i in 2..RulesSnapshot.MAX_APP_RULES) {
            dao.insert(appRule("com.pkg.$i", ruleId = "r-app-$i"))
        }
        publisher.loadFromDb()
        assertEquals(2000, publisher.snapshot.appRules.size)

        val e = runCatching { repository.insertRule(appRule("com.pkg.overflow")) }.exceptionOrNull()
        assertTrue("Expected RulesetLimitException, got $e", e is RulesetLimitException)
        assertTrue((e as RulesetLimitException).message!!.contains("2,000"))

        for (i in 1..RulesSnapshot.MAX_URL_RULES) {
            dao.insert(urlRule("site$i.example.com", ruleId = "r-url-$i"))
        }
        publisher.loadFromDb()
        val urlE = runCatching { repository.insertRule(urlRule("overflow.example.com")) }.exceptionOrNull()
        assertTrue("Expected RulesetLimitException for URL cap, got $urlE", urlE is RulesetLimitException)
    }

    /** #7: metadata-only create outcome — the reason code travels, the target never does. */
    @Test
    fun ruleCreate_reportsFeatureOutcome_metadataOnly() = runTest {
        seedProfile()
        val outcomes = mutableListOf<Triple<String, String, String?>>()
        val repository = RulesRepository(
            factory, publisher,
            featureTelemetry = { event, outcome, reason -> outcomes += Triple(event, outcome, reason) },
        )

        repository.insertRule(appRule("com.instagram.android"))
        assertEquals(listOf(Triple("rule_create", "ok", null)), outcomes)

        outcomes.clear()
        runCatching { repository.insertRule(appRule("com.example.myexodus")) }
        assertEquals(listOf(Triple("rule_create", "failed", "self_block")), outcomes)

        val dao = factory.open()!!.restrictionRuleDao()
        for (i in 2..RulesSnapshot.MAX_APP_RULES) {
            dao.insert(appRule("com.pkg.$i", ruleId = "r-app-$i"))
        }
        outcomes.clear()
        runCatching { repository.insertRule(appRule("com.pkg.overflow")) }
        assertEquals(listOf(Triple("rule_create", "failed", "limit")), outcomes)
    }

    @Test
    fun inactiveProfileRules_areNotEnforced() = runTest {
        seedProfile()
        val store = factory.open()!!
        store.userProfileDao().insert(
            profile("p-2", active = false),
        )
        store.restrictionRuleDao().insert(appRule("com.instagram.android", profileId = "p-2"))
        publisher.loadFastPath()
        publisher.loadFromDb()
        assertFalse(publisher.snapshot.appRules.containsKey("com.instagram.android"))
    }

    // --- helpers ---

    private suspend fun seedProfile() {
        factory.open()!!.userProfileDao().insert(profile("p-1", active = true))
    }

    private fun profile(userId: String, active: Boolean) = UserProfile(
        userId = userId,
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
        isActive = active,
    )

    private fun appRule(packageName: String, ruleId: String = "r-${System.nanoTime()}", profileId: String = "p-1", severityLevel: String = "HIGH") =
        RestrictionRule(
            ruleId = ruleId,
            profileId = profileId,
            blockedPackage = packageName,
            targetType = "APP",
            isAlwaysOn = true,
            dailyLimitMs = 0L,
            timeRangeStart = null,
            timeRangeEnd = null,
            activeDaysOfWeek = "1111111",
            blockExpiresAt = 0L,
            severityLevel = severityLevel,
        )

    private fun urlRule(host: String, ruleId: String = "r-${System.nanoTime()}", profileId: String = "p-1") =
        RestrictionRule(
            ruleId = ruleId,
            profileId = profileId,
            blockedPackage = host,
            targetType = "WEBSITE",
            isAlwaysOn = true,
            dailyLimitMs = 0L,
            timeRangeStart = null,
            timeRangeEnd = null,
            activeDaysOfWeek = "1111111",
            blockExpiresAt = 0L,
            severityLevel = "HIGH",
        )

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}

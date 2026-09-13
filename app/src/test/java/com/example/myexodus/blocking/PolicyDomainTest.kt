package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.DatabaseKeyManager
import com.example.myexodus.data.db.KeyFailure
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Room-backed PolicyVersion semantics (blocking-core plan §6: rule write + version bump in
 * one transaction; version is Room's authority, not a publisher counter) and the seeded
 * RecoveryPolicy singleton (plan §4 Policy Domain).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PolicyDomainTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private lateinit var publisher: RulesSnapshotPublisher
    private lateinit var repository: RulesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(context, FakeKeyManager(), ActivityLogKeyManager(context), healthStore)
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
        publisher = RulesSnapshotPublisher(context, factory)
        repository = RulesRepository(factory, publisher)
        runBlocking { factory.open()!!.userProfileDao().insert(profile()) }
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun repositoryWrite_bumpsRoomVersionAndSnapshotInLockstep() = runTest {
        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(1L, publisher.snapshot.version)
        assertEquals(1L, factory.open()!!.policyVersionDao().current())

        repository.deleteRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(2L, publisher.snapshot.version)
        assertEquals(2L, factory.open()!!.policyVersionDao().current())
    }

    @Test
    fun failedWrite_doesNotBumpVersion() = runTest {
        repository.insertRule(appRule("com.instagram.android", ruleId = "r-1"))
        assertEquals(1L, factory.open()!!.policyVersionDao().current())

        // Duplicate PK aborts the insert (OnConflictStrategy.ABORT) inside the write
        // transaction; the version bump must roll back with it.
        val e = runCatching { repository.insertRule(appRule("com.instagram.android", ruleId = "r-1")) }
            .exceptionOrNull()
        assertTrue("Expected constraint failure, got $e", e is SQLiteConstraintException)
        assertEquals("Version must not advance on a failed write", 1L, factory.open()!!.policyVersionDao().current())
        assertEquals(1L, publisher.snapshot.version)
    }

    @Test
    fun recoveryPolicy_seededWithDefaults() = runTest {
        val policy = factory.open()!!.recoveryPolicyDao().current()
        assertNotNull(policy)
        assertEquals(60L, policy!!.defaultFrictionSeconds)
        assertEquals(600L, policy.overrideCooldownSeconds)
    }

    @Test
    fun recoveryPolicy_updatePersists() = runTest {
        factory.open()!!.recoveryPolicyDao().update(120L, 900L)
        val policy = factory.open()!!.recoveryPolicyDao().current()
        assertEquals(120L, policy!!.defaultFrictionSeconds)
        assertEquals(900L, policy.overrideCooldownSeconds)
    }

    private fun profile() = UserProfile(
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
    )

    private fun appRule(packageName: String, ruleId: String) = RestrictionRule(
        ruleId = ruleId,
        profileId = "p-1",
        blockedPackage = packageName,
        targetType = "APP",
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

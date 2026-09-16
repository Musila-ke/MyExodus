package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema-constraint behavior tests (plan Section 12, JVM). Runs against the default
 * AndroidSQLiteDriver (unencrypted — encryption is exercised by the instrumentation suite).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseConstraintsTest {

    private lateinit var context: Context
    private lateinit var db: AppRulesStore
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        // Same callback production registers in AppRulesStoreFactory.build(): without it the
        // raw-SQL CHECKs and partial index never exist.
        db = Room.databaseBuilder(context, AppRulesStore::class.java, dbName)
            .addCallback(object : RoomDatabase.Callback() {
                override suspend fun onCreate(connection: SQLiteConnection) {
                    SchemaConstraints.apply(connection)
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun profiles() = db.userProfileDao()
    private fun rules() = db.restrictionRuleDao()
    private fun usage() = db.dailyUsageTrackerDao()

    // --- Rule 4: the app's own package can never be blocked (INSERT + UPDATE) ---

    @Test
    fun rule4_exactSelfPackageRejectedOnInsert() = runTest {
        profiles().insert(activeProfile())
        assertThrowsSuspending<ProtectedPackageException> {
            rules().insert(rule(blockedPackage = "com.example.myexodus"))
        }
    }

    @Test
    fun rule4_mixedCaseRejectedOnInsert() = runTest {
        profiles().insert(activeProfile())
        assertThrowsSuspending<ProtectedPackageException> {
            rules().insert(rule(blockedPackage = "com.example.MyExodus"))
        }
    }

    @Test
    fun rule4_trailingSpaceRejectedOnInsert() = runTest {
        profiles().insert(activeProfile())
        assertThrowsSuspending<ProtectedPackageException> {
            rules().insert(rule(blockedPackage = "com.example.myexodus "))
        }
    }

    @Test
    fun rule4_otherPackageAccepted() = runTest {
        profiles().insert(activeProfile())
        rules().insert(rule(blockedPackage = "com.instagram.android"))
        assertEquals(1, rules().observeByProfile(PROFILE_ID).first().size)
    }

    @Test
    fun rule4_rejectedOnUpdate() = runTest {
        profiles().insert(activeProfile())
        rules().insert(rule(blockedPackage = "com.instagram.android"))
        val existing = rules().observeByProfile(PROFILE_ID).first().single()
        assertThrowsSuspending<ProtectedPackageException> {
            rules().update(existing.copy(blockedPackage = "com.example.MyExodus"))
        }
    }

    // --- Single active profile ---
    // The v1-era partial unique index (ux_user_active) is gone: Room cannot model partial
    // indexes and a DB carrying one fails post-migration validation on every open (device spike
    // run 19). Single-active is a profile-management invariant (deactivate-on-activate in the
    // V1 UI workstream), not a DB constraint; observeActive LIMIT 1 defines which profile the
    // engines consult.

    @Test
    fun inactiveProfilesAllowedAlongsideActive() = runTest {
        profiles().insert(activeProfile())
        profiles().insert(activeProfile(userId = "other", isActive = false))
        assertTrue(profiles().observeActive().first()?.userId == PROFILE_ID)
    }

    // --- Duplicate usage record (unique profile_id/package_name/local_date_anchor) ---

    @Test
    fun duplicateUsageRecordRejected() = runTest {
        profiles().insert(activeProfile())
        usage().insert(usageRecord())
        assertThrowsSuspending<SQLiteConstraintException> {
            usage().insert(usageRecord())
        }
    }

    // --- Constraints survive reopen; raw-SQL constraint names present ---

    @Test
    fun constraintsEnforcedAfterReopen() = runTest {
        profiles().insert(activeProfile())
        rules().insert(rule(blockedPackage = "com.instagram.android"))
        db.close()
        db = Room.databaseBuilder(context, AppRulesStore::class.java, dbName).build()
        assertThrowsSuspending<ProtectedPackageException> {
            db.restrictionRuleDao().insert(rule(blockedPackage = "com.example.myexodus"))
        }
        assertTrue(
            schemaSql("restriction_rules")?.contains(SchemaConstraints.CHECK_SELF_BLOCK) == true,
        )
    }

    private suspend inline fun <reified T : Throwable> assertThrowsSuspending(
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
    }

    private fun schemaSql(table: String): String? = AndroidSQLiteDriver()
        .open(context.getDatabasePath(dbName).absolutePath)
        .use { conn ->
            conn.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { stmt ->
                stmt.step()
                stmt.getText(0)
            }
        }

    // --- Fixtures ---

    private fun activeProfile(userId: String = PROFILE_ID, isActive: Boolean = true) = UserProfile(
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
        isActive = isActive,
    )

    private fun rule(blockedPackage: String) = RestrictionRule(
        ruleId = "rule-${System.nanoTime()}",
        profileId = PROFILE_ID,
        blockedPackage = blockedPackage,
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 3_600_000L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "MEDIUM",
    )

    private fun usageRecord() = DailyUsageTracker(
        trackerId = "t-${System.nanoTime()}",
        profileId = PROFILE_ID,
        packageName = "com.instagram.android",
        localDateAnchor = LocalDate.now().toString(),
        timezoneIdAtAnchor = "UTC",
        msUsedToday = 1_000L,
        warning1State = "NOT_DUE",
        warning2State = "NOT_DUE",
        blockedToday = false,
    )

    private companion object {
        const val PROFILE_ID = "profile-1"
    }
}

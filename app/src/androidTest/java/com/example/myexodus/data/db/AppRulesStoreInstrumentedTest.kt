package com.example.myexodus.data.db

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device suite for the plain rules store (Enhanced Mode spec §8 Step 1). Every test
 * starts from a clean slate via [RecoveryManager.resetAllData].
 */
@RunWith(AndroidJUnit4::class)
class AppRulesStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val legacyKeys = AndroidDatabaseKeyManager(context)
    private val activityKeys = ActivityLogKeyManager(context)

    @Before
    fun deviceSafetyGuard() = assumeEmulatorOnly()

    private fun newFactory(): Triple<AppRulesStoreFactory, DatabaseHealthStore, RecoveryManager> {
        val healthStore = DatabaseHealthStore()
        val recovery = RecoveryManager(context, legacyKeys, activityKeys, RulesStoreKeyManager(context), healthStore)
        recovery.resetAllDataEmulatorOnly()
        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        return Triple(factory, healthStore, recovery)
    }

    private fun schemaSql(table: String): String? {
        val path = context.getDatabasePath("myexodus_rules.db").absolutePath
        return AndroidSQLiteDriver().open(path).use { conn ->
            conn.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { stmt ->
                stmt.step()
                stmt.getText(0)
            }
        }
    }

    @Test
    fun freshCreate_roundTrip() = runTest {
        val (factory, healthStore, _) = newFactory()
        val db = factory.open()
        assertNotNull(db)
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)

        db!!.userProfileDao().insert(profile())
        val read = db.userProfileDao().getById(PROFILE_ID)
        assertNotNull(read)
        assertEquals("Test", read!!.name)
    }

    @Test
    fun restart_reopensExistingDatabase() = runTest {
        val (factory1, _, _) = newFactory()
        val db1 = factory1.open()
        assertNotNull(db1)
        db1!!.userProfileDao().insert(profile())

        // Simulated app restart: a brand-new factory must re-open the same file.
        val healthStore2 = DatabaseHealthStore()
        val recovery2 = RecoveryManager(context, legacyKeys, activityKeys, RulesStoreKeyManager(context), healthStore2)
        val factory2 = AppRulesStoreFactory(context, recovery2, healthStore2)
        val db2 = factory2.open()
        assertNotNull(db2)
        assertEquals(DatabaseHealth.Ready, healthStore2.health.value)
        assertEquals("Test", db2!!.userProfileDao().getById(PROFILE_ID)!!.name)
    }

    @Test
    fun schema_constraintsPresentInSqliteMaster() = runTest {
        val (factory, _, _) = newFactory()
        val db = factory.open()
        assertNotNull(db)
        // Room builds the schema lazily on first DAO access; trigger it before raw-connection checks.
        db!!.userProfileDao().getById(PROFILE_ID)

        val rulesSql = schemaSql("restriction_rules")
        assertTrue(rulesSql?.contains(SchemaConstraints.CHECK_SELF_BLOCK) == true)
        assertTrue(rulesSql?.contains("severity_valid") == true)
        // No ux_user_active assertion here: that v1-era partial index is deliberately retired
        // (documented in SchemaConstraints.kt, dropped by AppRulesStoreFactory on open).
    }

    @Test
    fun corruptFileOnDisk_surfacesRecoverableDataFound() = runTest {
        val healthStore = DatabaseHealthStore()
        val recovery = RecoveryManager(context, legacyKeys, activityKeys, RulesStoreKeyManager(context), healthStore)
        recovery.resetAllDataEmulatorOnly()
        // Place a corrupt orphan BEFORE the open, as a crashed recovery would.
        val corrupt = java.io.File(context.getDatabasePath("myexodus_rules.db").absolutePath + ".corrupt")
        corrupt.writeText("partial")

        val factory = AppRulesStoreFactory(context, recovery, healthStore)
        assertNotNull(factory.open())
        assertEquals(DatabaseHealth.RecoverableDataFound, healthStore.health.value)

        recovery.discardCorruptFiles()
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)
    }

    @Test
    fun resetAndReopen_destroysDataAndRegeneratesEmptyDatabase() = runTest {
        val (factory, healthStore, _) = newFactory()
        val db1 = factory.open()
        assertNotNull(db1)
        db1!!.userProfileDao().insert(profile())

        val db2 = factory.resetAndReopen()
        assertNotNull(db2)
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)
        assertNull(db2!!.userProfileDao().getById(PROFILE_ID))
        db2.userProfileDao().insert(profile())
        assertEquals("Test", db2.userProfileDao().getById(PROFILE_ID)!!.name)
    }

    private fun profile() = UserProfile(
        userId = PROFILE_ID,
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

    private companion object {
        const val PROFILE_ID = "profile-1"
    }
}

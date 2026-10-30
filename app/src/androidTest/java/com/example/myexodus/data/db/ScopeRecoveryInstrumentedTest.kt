package com.example.myexodus.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.account.KeystoreScopeSnapshotReader
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR #239: the retained scope is read from a byte copy under its own DEK. The original must
 * stay byte-identical (never opened, rekeyed or checkpointed), a lost key must never be
 * re-minted, and a name that is not a scope hash must never become a path.
 */
@RunWith(AndroidJUnit4::class)
class ScopeRecoveryInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory

    @Before
    fun setUp() {
        assumeEmulatorOnly()
        System.loadLibrary("sqlcipher")
        recovery = RecoveryManager(
            context,
            AndroidDatabaseKeyManager(context),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            DatabaseHealthStore(),
        )
        recovery.resetAllDataEmulatorOnly()
        LocalDatabaseMigration.resetForTest()
        LocalDatabaseMigration.resetJournalForTest(context)
        listOf(UID, OTHER_UID).forEach(::deleteScope)
        // Production wiring: the seeded scope must be a real SQLCipher file keyed by that
        // scope's own DEK, otherwise the reader would be tested against a plaintext stand-in.
        val rulesKeys = RulesStoreKeyManager(context)
        factory = AppRulesStoreFactory(
            context,
            recovery,
            DatabaseHealthStore(),
            RulesStoreEncryption(rulesKeys, recovery),
        )
    }

    @After
    fun tearDown() {
        if (!onSanctionedEmulator() || !::factory.isInitialized) return
        listOf(UID, OTHER_UID).forEach(::deleteScope)
    }

    @Test
    fun theRetainedScopeIsReadFromACopy_andTheOriginalStaysByteIdentical() = runTest {
        seedScopedStore()
        LocalDatabaseMigration.switchScopeTo(context, OTHER_UID)

        val hash = LocalDatabaseMigration.uidHash(UID)
        val rules = File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName)
        val wal = File(rules.path + "-wal")
        val before = listOf(rules, wal).map { it.readBytes() }

        val reader = KeystoreScopeSnapshotReader(context, factory)
        val snapshot = reader.snapshotOf(hash)

        assertNotNull("the retained scope must be readable", snapshot)
        assertEquals(listOf(HABIT_ID), snapshot!!.habits.map { it.progressId })
        assertArrayEquals(
            "the original is never opened, rekeyed or checkpointed",
            before[0],
            rules.readBytes(),
        )
        assertArrayEquals("its WAL is copied, not consumed", before[1], wal.readBytes())
        assertFalse(
            "the staging copy and its sidecars are deleted",
            listOf("", "-wal", "-shm", "-journal", ".lck")
                .any { File(databaseDir(), "myexodus_rules_recovery_$hash.db$it").exists() },
        )
    }

    @Test
    fun theRetainedRulesAndUsage_areReadFromTheSameCopy_leavingTheOriginalUntouched() = runTest {
        seedScopedStore()
        LocalDatabaseMigration.switchScopeTo(context, OTHER_UID)
        val hash = LocalDatabaseMigration.uidHash(UID)
        val rules = File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName)
        val wal = File(rules.path + "-wal")
        val before = listOf(rules, wal).map { it.readBytes() }

        val data = KeystoreScopeSnapshotReader(context, factory).localDataOf(hash)

        assertEquals(listOf(RULE_PACKAGE), data!!.rules.map { it.blockedPackage })
        assertEquals(listOf(LEDGER_EVENT_ID), data.ledger.map { it.eventId })
        assertArrayEquals(before[0], rules.readBytes())
        assertArrayEquals(before[1], wal.readBytes())
        assertFalse(
            listOf("", "-wal", "-shm", "-journal", ".lck")
                .any { File(databaseDir(), "myexodus_rules_recovery_$hash.db$it").exists() },
        )
    }

    @Test
    fun aLostScopeKey_isNeverRecreated_andTheFileStaysUntouched() = runTest {
        seedScopedStore()
        LocalDatabaseMigration.switchScopeTo(context, OTHER_UID)
        val hash = LocalDatabaseMigration.uidHash(UID)
        scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, hash)
            .resetKeyMaterial()

        val reader = KeystoreScopeSnapshotReader(context, factory)

        assertNull("a missing key must fail closed", reader.snapshotOf(hash))
        assertFalse(
            "the retained file must never gain a replacement key",
            scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, hash)
                .hasWrappedKey(),
        )
        assertTrue(File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName).exists())
    }

    @Test
    fun aNameThatIsNotAScopeHash_isRefusedBeforeTouchingTheFilesystem() = runTest {
        val reader = KeystoreScopeSnapshotReader(context, factory)

        assertNull(reader.snapshotOf("../../files/secret"))
        assertNull(reader.snapshotOf("ABCDEF0123456789ABCDEF0123456789"))
    }

    private suspend fun seedScopedStore() {
        // The factory resolves the file name from the in-memory scope, which only start()/the
        // migration seam populate — a prefs-only switch would seed the legacy store instead.
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val db = factory.open()
        assertNotNull("the scoped store must open under its own key", db)
        db!!.userProfileDao().insert(
            UserProfile(
                userId = PROFILE_ID,
                name = "Retained",
                email = "",
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
        db.habitProgressDao().insert(
            HabitProgress(
                progressId = HABIT_ID,
                profileId = PROFILE_ID,
                targetIdentifier = "Porn",
                currentCleanDaysStreak = 3,
                totalRelapses = 1,
                accessAttemptsCount = 2,
                habitDurationYears = 5,
                onboardingSeverityLevel = "MODERATE",
                lastRelapseTimestamp = null,
                assignedPartnershipId = null,
                isSelected = true,
            ),
        )
        db.restrictionRuleDao().insert(
            RestrictionRule(
                ruleId = RULE_ID,
                profileId = PROFILE_ID,
                blockedPackage = RULE_PACKAGE,
                targetType = "APP",
                isAlwaysOn = true,
                dailyLimitMs = 0,
                timeRangeStart = null,
                timeRangeEnd = null,
                activeDaysOfWeek = "127",
                blockExpiresAt = 0,
                severityLevel = "MEDIUM",
            ),
        )
        db.usageLedgerDao().insert(
            UsageLedger(
                eventId = LEDGER_EVENT_ID,
                kind = UsageLedgerKind.ACCOUNTING,
                wallClockTsMs = 1_700_000_000_000L,
                elapsedStartMs = 0,
                elapsedEndMs = 60_000,
                bootCount = 0,
                packageName = RULE_PACKAGE,
                dayKey = "2026-10-30",
                creditedMs = 60_000,
                source = UsageLedgerSource.PATH_A,
                sourceVersion = "instrumented",
            ),
        )
    }

    private fun databaseDir(): File = context.getDatabasePath("x").parentFile!!

    private fun scopedKeyManager(alias: String, prefs: String, hash: String) =
        KeystoreWrappedDekManager(context, alias + "_$hash", prefs + "_$hash")

    private fun deleteScope(uid: String) {
        val scope = LocalDatabaseMigration.Scope(LocalDatabaseMigration.uidHash(uid))
        listOf(scope.rulesName, scope.activityName, scope.accountabilityName).forEach { name ->
            val file = File(databaseDir(), name)
            listOf("", "-wal", "-shm", "-journal", ".lck").forEach { File(file.path + it).delete() }
        }
        scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, scope.uidHash)
            .resetKeyMaterial()
        scopedKeyManager(ActivityLogKeyManager.LEGACY_KEK_ALIAS, ActivityLogKeyManager.LEGACY_PREFS, scope.uidHash)
            .resetKeyMaterial()
    }

    private companion object {
        const val UID = "scope-recovery-test-uid"
        const val OTHER_UID = "scope-recovery-other-uid"
        const val PROFILE_ID = "profile-recovery-1"
        const val HABIT_ID = "habit-recovery-1"
        const val RULE_ID = "rule-recovery-1"
        const val RULE_PACKAGE = "com.example.blocked"
        const val LEDGER_EVENT_ID = "ledger-recovery-1"
    }
}

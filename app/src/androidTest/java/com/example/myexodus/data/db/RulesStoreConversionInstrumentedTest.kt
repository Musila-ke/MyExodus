package com.example.myexodus.data.db

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
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
 * Real SQLCipher conversion round trips on a realistic pre-populated plaintext install
 * (habit-streaks plan §12): convert-on-open with row preservation, the two-launch backup
 * confirmation, the crash window (main missing + `.bak` present), and the wrong-wrapped-DEK
 * path surfacing KeyUnavailable without touching data. These must run on-device — Robolectric
 * cannot load the SQLCipher native library, so the JVM suite covers only the file-state
 * machine (PlaintextConversionFileOpsTest). Every test wipes all state via
 * [RecoveryManager.resetAllData].
 */
@RunWith(AndroidJUnit4::class)
class RulesStoreConversionInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val legacyKeys = AndroidDatabaseKeyManager(context)
    private val activityKeys = ActivityLogKeyManager(context)
    private val rulesKeys = RulesStoreKeyManager(context)

    @Before
    fun setUp() {
        assumeEmulatorOnly()
        recovery().resetAllDataEmulatorOnly()
    }

    @After
    fun tearDown() {
        recovery().resetAllDataEmulatorOnly()
    }

    private fun recovery(health: DatabaseHealthStore = DatabaseHealthStore()): RecoveryManager =
        RecoveryManager(context, legacyKeys, activityKeys, rulesKeys, health)

    /** Production-shaped factory: real Keystore key manager + real SQLCipher driver. */
    private fun encryptedFactory(health: DatabaseHealthStore): Pair<AppRulesStoreFactory, RecoveryManager> {
        val recoveryManager = recovery(health)
        val factory = AppRulesStoreFactory(
            context, recoveryManager, health, RulesStoreEncryption(rulesKeys, recoveryManager),
        )
        return factory to recoveryManager
    }

    /** A pre-encryption install: plaintext factory (null encryption), production DB path. */
    private suspend fun seedPlaintextInstall() {
        val factory = AppRulesStoreFactory(context, recovery(), DatabaseHealthStore(), encryption = null)
        val store = factory.open()!!
        store.userProfileDao().insert(profile())
        store.restrictionRuleDao().insert(rule("r-1", "com.plain.one"))
        store.restrictionRuleDao().insert(rule("r-2", "com.plain.two"))
        store.attemptLogDao().insert(
            AttemptLog(
                occurredAtEpochMillis = 1_700_000_000_000L,
                ruleId = "r-1",
                blockedTarget = "com.plain.one",
                outcome = AttemptLog.OUTCOME_BLOCKED,
                eventSequence = null,
            ),
        )
        store.close()
        assertTrue("seeded install must be a plaintext database", isPlaintext(recovery().rulesDbFile))
    }

    @Test
    fun existingPlaintextInstall_convertsPreservingRowsAndKeepsBackup() = runTest {
        seedPlaintextInstall()
        val health = DatabaseHealthStore()
        val (factory, recoveryManager) = encryptedFactory(health)

        val store = factory.open()
        assertNotNull("conversion must produce an openable encrypted store", store)

        assertTrue("conversion must have generated the wrapped DEK", rulesKeys.hasWrappedKey())
        assertEquals(DatabaseHealth.Ready, health.health.value)
        assertFalse("the live file must be ciphertext", isPlaintext(recoveryManager.rulesDbFile))
        assertTrue(
            "the pre-conversion backup must survive the conversion launch",
            recoveryManager.hasConversionBackup(),
        )
        assertTrue(
            "the backup must hold the original plaintext database",
            isPlaintext(recoveryManager.rulesDbBackupFile),
        )
        assertFalse("conversion must clean its temp file", recoveryManager.rulesDbTempFile.exists())

        assertEquals("Test", store!!.userProfileDao().getById(PROFILE_ID)!!.name)
        assertEquals("com.plain.one", store.restrictionRuleDao().getById("r-1")!!.blockedPackage)
        assertEquals("com.plain.two", store.restrictionRuleDao().getById("r-2")!!.blockedPackage)
        assertEquals(1L, store.attemptLogDao().count())
        store.close()
    }

    @Test
    fun secondColdStart_confirmsConversionAndDeletesBackup() = runTest {
        seedPlaintextInstall()
        val (factory1, recoveryManager) = encryptedFactory(DatabaseHealthStore())
        factory1.open()!!.close()
        assertTrue(recoveryManager.hasConversionBackup())

        // Relaunch: a cold start that begins with the backup present confirms and removes it.
        val health = DatabaseHealthStore()
        val (factory2, recoveryManager2) = encryptedFactory(health)
        val store2 = factory2.open()
        assertNotNull(store2)

        assertEquals(DatabaseHealth.Ready, health.health.value)
        assertFalse(
            "only a launch that started with the backup may delete it",
            recoveryManager2.hasConversionBackup(),
        )
        assertFalse(isPlaintext(recoveryManager2.rulesDbFile))
        assertEquals("com.plain.one", store2!!.restrictionRuleDao().getById("r-1")!!.blockedPackage)
        store2.close()
    }

    @Test
    fun crashWindow_missingMainWithBackup_restoresAndReconverts() = runTest {
        seedPlaintextInstall()
        val (factory1, recoveryManager) = encryptedFactory(DatabaseHealthStore())
        factory1.open()!!.close()

        // Crash window of the swap's delete+rename fallback: main gone, plaintext .bak the only
        // copy, a stray temp file from the interrupted run alongside it.
        recoveryManager.rulesDbFile.delete()
        recoveryManager.rulesDbTempFile.writeBytes(ByteArray(16) { 1 })

        val health = DatabaseHealthStore()
        val (factory2, recoveryManager2) = encryptedFactory(health)
        val store2 = factory2.open()
        assertNotNull("the backup must be restored and re-converted", store2)

        assertEquals(DatabaseHealth.Ready, health.health.value)
        assertFalse(isPlaintext(recoveryManager2.rulesDbFile))
        assertFalse(recoveryManager2.rulesDbTempFile.exists())
        assertEquals("com.plain.one", store2!!.restrictionRuleDao().getById("r-1")!!.blockedPackage)
        assertEquals("com.plain.two", store2.restrictionRuleDao().getById("r-2")!!.blockedPackage)
        store2.close()
    }

    @Test
    fun wrongWrappedDek_surfacesKeyUnavailableWithoutTouchingData() = runTest {
        seedPlaintextInstall()
        val (factory1, _) = encryptedFactory(DatabaseHealthStore())
        factory1.open()!!.close()
        // Confirm the conversion so no plaintext backup remains to be restored.
        val (factory2, recoveryManager2) = encryptedFactory(DatabaseHealthStore())
        factory2.open()!!.close()
        assertFalse(recoveryManager2.hasConversionBackup())

        // Corrupt the wrapped DEK: valid base64, wrong bytes -> the GCM unwrap must fail.
        context.getSharedPreferences("myexodus_rules_db_keys", Context.MODE_PRIVATE).edit(commit = true) {
            putString("dek_iv", Base64.encodeToString(ByteArray(12) { 0x9E.toByte() }, Base64.NO_WRAP))
            putString("dek_blob", Base64.encodeToString(ByteArray(48) { 0x9E.toByte() }, Base64.NO_WRAP))
        }
        val main = recoveryManager2.rulesDbFile
        val encryptedBytes = main.readBytes()

        val health = DatabaseHealthStore()
        val (factory3, _) = encryptedFactory(health)
        assertNull("an unopenable key must not yield a plaintext store", factory3.open())

        assertEquals(DatabaseHealth.KeyUnavailable, health.health.value)
        assertTrue("the data file must be left in place", main.exists())
        assertFalse(isPlaintext(main))
        assertArrayEquals(
            "a failed open must not modify the encrypted file",
            encryptedBytes,
            main.readBytes(),
        )
    }

    private fun isPlaintext(file: java.io.File): Boolean =
        PlaintextToEncryptedMigration(RulesStoreKeyManager(context), recovery()).isPlaintextDatabase(file)

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

    private fun rule(ruleId: String, blocked: String) = RestrictionRule(
        ruleId = ruleId,
        profileId = PROFILE_ID,
        blockedPackage = blocked,
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "0,1,2,3,4,5,6",
        blockExpiresAt = Long.MAX_VALUE,
        severityLevel = "HIGH",
    )

    private companion object {
        const val PROFILE_ID = "conv-profile"
    }
}

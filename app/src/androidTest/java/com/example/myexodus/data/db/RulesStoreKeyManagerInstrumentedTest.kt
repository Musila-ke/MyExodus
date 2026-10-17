package com.example.myexodus.data.db

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.entity.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Keystore + real SQLCipher round trip for the encrypted rules store (habit-streaks plan
 * §12): DEK wrap/unwrap across manager instances, the stored blob never being the raw key,
 * cold install → relaunch decrypt, and the at-rest header check. Every test wipes all state
 * through `resetAllDataEmulatorOnly` — the gated no-op-off-emulator wrapper (DeviceSafety.kt);
 * a raw [RecoveryManager.resetAllData] here would wipe the handset's production store.
 */
@RunWith(AndroidJUnit4::class)
class RulesStoreKeyManagerInstrumentedTest {

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
        val recoveryManager = RecoveryManager(context, legacyKeys, activityKeys, rulesKeys, health)
        val factory = AppRulesStoreFactory(
            context, recoveryManager, health, RulesStoreEncryption(rulesKeys, recoveryManager),
        )
        return factory to recoveryManager
    }

    @Test
    fun dekRoundTrip_sameKeyAcrossManagerInstances_resetRegenerates() {
        val dek1 = RulesStoreKeyManager(context).getOrCreateDek()
        assertEquals(32, dek1.size)
        assertTrue(rulesKeys.hasWrappedKey())

        val dek2 = RulesStoreKeyManager(context).getOrCreateDek()
        assertArrayEquals("a fresh manager on the same device must unwrap the same DEK", dek1, dek2)

        val prefs = context.getSharedPreferences("myexodus_rules_db_keys", Context.MODE_PRIVATE)
        val blob = Base64.decode(prefs.getString("dek_blob", null), Base64.NO_WRAP)
        assertEquals("wrapped blob is the ciphertext plus the GCM tag", dek1.size + 16, blob.size)
        assertFalse("the raw DEK must never be stored", blob.contentEquals(dek1))

        rulesKeys.resetKeyMaterial()
        assertFalse(rulesKeys.hasWrappedKey())
        val dek3 = rulesKeys.getOrCreateDek()
        assertEquals(32, dek3.size)
        assertFalse("reset must drop the previous DEK material", dek3.contentEquals(dek1))
    }

    @Test
    fun coldInstall_relaunch_decryptsData() = runTest {
        val (factory, recoveryManager) = encryptedFactory(DatabaseHealthStore())
        val store = factory.open()
        assertNotNull(store)
        store!!.userProfileDao().insert(profile())
        store.close()

        val file = recoveryManager.rulesDbFile
        assertTrue(file.exists())
        assertFalse("store file must not be plaintext SQLite", isPlaintext(file, recoveryManager))
        assertFalse("a fresh encrypted install needs no conversion backup", recoveryManager.hasConversionBackup())

        // Relaunch: new factory + new key-manager instance over the same on-disk state.
        val (factory2, recoveryManager2) = encryptedFactory(DatabaseHealthStore())
        val store2 = factory2.open()
        assertNotNull("relaunch must decrypt with the same wrapped DEK", store2)
        assertEquals("Test", store2!!.userProfileDao().getById(PROFILE_ID)!!.name)
        assertFalse(isPlaintext(recoveryManager2.rulesDbFile, recoveryManager2))
    }

    private fun isPlaintext(file: java.io.File, recoveryManager: RecoveryManager): Boolean =
        PlaintextToEncryptedMigration(RulesStoreKeyManager(context), recoveryManager).isPlaintextDatabase(file)

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

package com.example.myexodus.applock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.applock.crypto.VaultEnvelopeStore
import com.example.myexodus.applock.crypto.VaultKeyManager
import com.example.myexodus.data.db.ActivityLogKeyManager
import com.example.myexodus.data.db.AndroidDatabaseKeyManager
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.DatabaseHealthStore
import com.example.myexodus.data.db.RecoveryManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultRecoveryResetTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var envelopeStore: VaultEnvelopeStore
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var reset: VaultRecoveryReset
    private val storeKeys = FakeStoreKeys()
    private var keyDeletes = 0

    @Before
    fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        envelopeStore = VaultEnvelopeStore(temp.newFolder("vault"))
        recoveryManager = RecoveryManager(
            app,
            AndroidDatabaseKeyManager(app),
            ActivityLogKeyManager(app),
            RulesStoreKeyManager(app),
            DatabaseHealthStore(),
        )
        val keyManager = VaultKeyManager(
            keyLookup = { null },
            keyGenerator = {},
            keyDeleter = { keyDeletes++ },
        )
        reset = VaultRecoveryReset(recoveryManager, envelopeStore, keyManager, storeKeys)
    }

    @Test
    fun `reset erases every vault-owned store file envelope and wrapping material`() {
        val rules = recoveryManager.rulesDbFile
        val activity = recoveryManager.activityDbFile
        val restore = File(rules.path + AppRulesStoreFactory.RESTORE_SUFFIX)
        val previous = File(rules.path + AppRulesStoreFactory.PREVIOUS_SUFFIX)
        val stores = listOf(rules, activity, restore, previous)
        for (file in stores) {
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(32))
            File(file.path + "-wal").writeBytes(ByteArray(8))
        }
        envelopeStore.writeStaging(ByteArray(64))
        envelopeStore.commitStaging()
        envelopeStore.writeStaging(ByteArray(64))

        reset.erase()

        for (file in stores) {
            assertFalse("${file.name} must be gone", file.exists())
            assertFalse("${file.name}-wal must be gone", File(file.path + "-wal").exists())
        }
        assertFalse(envelopeStore.hasActive())
        assertFalse(envelopeStore.hasStaging())
        assertEquals(1, keyDeletes)
        assertEquals(listOf(VaultDatabase.RULES, VaultDatabase.ACTIVITY), storeKeys.deleted)
    }

    @Test
    fun `reset is idempotent when the artifacts are already gone`() {
        envelopeStore.writeStaging(ByteArray(64))
        envelopeStore.commitStaging()

        reset.erase()
        reset.erase()

        assertFalse(recoveryManager.rulesDbFile.exists())
        assertFalse(envelopeStore.hasActive())
        assertTrue(storeKeys.deleted.contains(VaultDatabase.RULES))
        assertTrue(storeKeys.deleted.contains(VaultDatabase.ACTIVITY))
    }

    private class FakeStoreKeys : VaultStoreKeys {
        val deleted = mutableListOf<VaultDatabase>()

        override fun unwrapLegacy(database: VaultDatabase): ByteArray = ByteArray(32)

        override fun adoptLegacy(database: VaultDatabase, dek: ByteArray) = Unit

        override fun deleteLegacyMaterial(database: VaultDatabase) {
            deleted += database
        }

        override fun verify(database: VaultDatabase, dek: ByteArray): Boolean = true
    }
}

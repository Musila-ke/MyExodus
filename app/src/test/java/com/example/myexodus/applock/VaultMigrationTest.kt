package com.example.myexodus.applock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.applock.crypto.VaultEnvelopeStore
import com.example.myexodus.applock.crypto.VaultKeyManager
import com.example.myexodus.applock.crypto.VaultSecretBundle
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class VaultMigrationTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var statusStore: AppLockStatusStore
    private lateinit var envelopeStore: VaultEnvelopeStore
    private lateinit var storeKeys: FakeStoreKeys
    private lateinit var migration: VaultMigration
    private var key: SecretKey? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        statusStore = AppLockStatusStore(app)
        envelopeStore = VaultEnvelopeStore(temp.newFolder("vault"))
        storeKeys = FakeStoreKeys()
        key = freshKey()
        val keyManager = VaultKeyManager(
            keyLookup = { alias -> if (alias == VaultKeyManager.ALIAS_V1) key else null },
            keyGenerator = { key = freshKey() },
            keyDeleter = { key = null },
        )
        migration = VaultMigration(app, statusStore, envelopeStore, keyManager, storeKeys)
    }

    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun enroll(authorizer: VaultCipherAuthorizer = ScriptedAuthorizer(listOf(true, true))) =
        runBlocking { migration.enroll({ true }, authorizer) }

    private fun assertRolledBack() {
        assertFalse(statusStore.isFeatureEnabled())
        assertEquals(AppLockStatusStore.JOURNAL_NONE, statusStore.journal())
        assertFalse(envelopeStore.hasActive())
        assertFalse(envelopeStore.hasStaging())
    }

    @Test
    fun `enrollment commits one envelope and enables the feature`() {
        val authorizer = ScriptedAuthorizer(listOf(true, true))

        assertEquals(VaultMigrationOutcome.Done, enroll(authorizer))

        assertTrue(statusStore.isFeatureEnabled())
        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
        assertTrue(envelopeStore.hasActive())
        assertFalse(envelopeStore.hasStaging())
        assertEquals(2, authorizer.calls)
        assertNotNull(key)
    }

    @Test
    fun `enrolling an already enabled feature is refused`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())

        assertEquals(
            VaultMigrationOutcome.Failed("already_enabled"),
            enroll(ScriptedAuthorizer(listOf(true, true))),
        )
        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
    }

    @Test
    fun `a busy vault aborts before any durable write`() {
        val result = runBlocking {
            migration.enroll({ false }, ScriptedAuthorizer(listOf(true, true)))
        }

        assertEquals(VaultMigrationOutcome.Failed("vault_busy"), result)
        assertNull(statusStore.journal())
        assertFalse(envelopeStore.hasActive())
        assertFalse(envelopeStore.hasStaging())
    }

    @Test
    fun `cancelling the wrapping prompt rolls everything back`() {
        assertEquals(VaultMigrationOutcome.Cancelled, enroll(ScriptedAuthorizer(listOf(false))))

        assertRolledBack()
    }

    @Test
    fun `cancelling the verification prompt rolls everything back`() {
        val authorizer = ScriptedAuthorizer(listOf(true, false))

        assertEquals(VaultMigrationOutcome.Cancelled, enroll(authorizer))

        assertEquals(2, authorizer.calls)
        assertRolledBack()
    }

    @Test
    fun `an independently authenticated decrypt that fails never commits`() {
        val authorizer = object : VaultCipherAuthorizer {
            var calls = 0
            override suspend fun authorize(initialize: () -> Cipher): Cipher? {
                calls++
                if (calls == 2) key = freshKey() // wrong key for the verification decrypt
                return initialize()
            }
        }

        assertEquals(VaultMigrationOutcome.Failed("crypto_error"), enroll(authorizer))
        assertEquals(2, authorizer.calls)
        assertRolledBack()
    }

    @Test
    fun `a store the recovered key cannot open blocks the commit`() {
        storeKeys.verifyResult = false

        assertEquals(VaultMigrationOutcome.Failed("db_verify_failed"), enroll())

        // The first failing verify short-circuits; the commit is blocked either way.
        assertEquals(1, storeKeys.verifyCalls)
        assertRolledBack()
    }

    @Test
    fun `a crash after staging is swept at startup`() {
        statusStore.beginEnrollmentStaging()
        envelopeStore.writeStaging(ByteArray(64))

        assertEquals(AppLockState.DISABLED, migration.recoverInterrupted())

        assertFalse(envelopeStore.hasStaging())
        assertFalse(envelopeStore.hasActive())
        assertEquals(AppLockStatusStore.JOURNAL_NONE, statusStore.journal())
    }

    @Test
    fun `an enabled vault without its envelope is recovery, never a fresh install`() {
        statusStore.commitEnrollmentActive()

        assertEquals(AppLockState.RECOVERY_REQUIRED, migration.recoverInterrupted())

        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
    }

    @Test
    fun `an unknown durable state is preserved untouched`() {
        app.getSharedPreferences(AppLockStatusStore.PREFS_NAME, 0).edit()
            .putString(AppLockStatusStore.KEY_FEATURE_STATUS, "future-status")
            .commit()
        envelopeStore.writeStaging(ByteArray(64))

        assertEquals(AppLockState.RECOVERY_REQUIRED, migration.recoverInterrupted())
        assertTrue(envelopeStore.hasStaging())
    }

    @Test
    fun `an interrupted disable returns to the pending journal and stays locked`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())
        assertTrue(envelopeStore.hasActive())
        statusStore.beginDisabling()

        assertEquals(AppLockState.LOCKED, migration.recoverInterrupted())

        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
        assertTrue(envelopeStore.hasActive())
    }

    @Test
    fun `an interrupted first-unlock cleanup finishes at startup`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())
        assertTrue(envelopeStore.hasActive())
        statusStore.markFirstUnlockVerified()

        assertEquals(AppLockState.LOCKED, migration.recoverInterrupted())

        assertTrue(storeKeys.deleted.contains(VaultDatabase.RULES))
        assertTrue(storeKeys.deleted.contains(VaultDatabase.ACTIVITY))
        assertEquals(AppLockStatusStore.JOURNAL_FIRST_UNLOCK_VERIFIED, statusStore.journal())
    }

    @Test
    fun `the first real unlock removes the old wrap once and stays idempotent`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())

        migration.completeFirstUnlock()
        assertEquals(AppLockStatusStore.JOURNAL_FIRST_UNLOCK_VERIFIED, statusStore.journal())
        assertEquals(2, storeKeys.deleted.size)

        migration.completeFirstUnlock()
        assertFalse(statusStore.isFeatureEnabled().not())
        assertEquals(AppLockStatusStore.JOURNAL_FIRST_UNLOCK_VERIFIED, statusStore.journal())
    }

    @Test
    fun `first-unlock cleanup does nothing while the feature is off`() {
        migration.completeFirstUnlock()

        assertNull(statusStore.journal())
        assertTrue(storeKeys.deleted.isEmpty())
    }

    @Test
    fun `disable re-wraps under the pre-feature scheme and clears the vault`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())
        val secrets = VaultSecretBundle(storeKeys.rulesDek.copyOf(), storeKeys.activityDek.copyOf())

        assertEquals(VaultMigrationOutcome.Done, migration.disable(secrets, { true }))

        assertFalse(statusStore.isFeatureEnabled())
        assertEquals(AppLockStatusStore.JOURNAL_NONE, statusStore.journal())
        assertFalse(envelopeStore.hasActive())
        assertNull(key)
        assertTrue(storeKeys.adopted.containsKey(VaultDatabase.RULES))
        assertTrue(storeKeys.adopted.containsKey(VaultDatabase.ACTIVITY))
    }

    @Test
    fun `a failed disabled-mode verify rolls the journal back while keeping the vault`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())
        storeKeys.verifyResult = false
        val secrets = VaultSecretBundle(storeKeys.rulesDek.copyOf(), storeKeys.activityDek.copyOf())

        assertEquals(
            VaultMigrationOutcome.Failed("rules_verify_failed"),
            migration.disable(secrets, { true }),
        )

        assertTrue(statusStore.isFeatureEnabled())
        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
        assertTrue(envelopeStore.hasActive())
        assertNotNull(key)
    }

    @Test
    fun `disable without an active envelope is refused`() {
        val secrets = VaultSecretBundle(ByteArray(32), ByteArray(32))

        assertEquals(
            VaultMigrationOutcome.Failed("not_enabled"),
            migration.disable(secrets, { true }),
        )
    }

    @Test
    fun `disable refuses a busy vault and keeps the journal`() {
        assertEquals(VaultMigrationOutcome.Done, enroll())
        val secrets = VaultSecretBundle(storeKeys.rulesDek.copyOf(), storeKeys.activityDek.copyOf())

        assertEquals(
            VaultMigrationOutcome.Failed("vault_busy"),
            migration.disable(secrets, { false }),
        )

        assertTrue(statusStore.isFeatureEnabled())
        assertEquals(
            AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK,
            statusStore.journal(),
        )
    }

    private class ScriptedAuthorizer(private val approvals: List<Boolean>) : VaultCipherAuthorizer {
        var calls = 0
            private set

        override suspend fun authorize(initialize: () -> Cipher): Cipher? {
            val approved = approvals.getOrElse(calls) { true }
            calls++
            return if (approved) initialize() else null
        }
    }

    private class FakeStoreKeys : VaultStoreKeys {
        val rulesDek = ByteArray(32) { 1 }
        val activityDek = ByteArray(32) { 2 }
        val adopted = mutableMapOf<VaultDatabase, ByteArray>()
        val deleted = mutableListOf<VaultDatabase>()
        var verifyResult = true
        var verifyCalls = 0

        override fun unwrapLegacy(database: VaultDatabase): ByteArray =
            adopted[database]?.copyOf() ?: when (database) {
                VaultDatabase.RULES -> rulesDek.copyOf()
                VaultDatabase.ACTIVITY -> activityDek.copyOf()
            }

        override fun adoptLegacy(database: VaultDatabase, dek: ByteArray) {
            adopted[database] = dek.copyOf()
        }

        override fun deleteLegacyMaterial(database: VaultDatabase) {
            deleted += database
        }

        override fun verify(database: VaultDatabase, dek: ByteArray): Boolean {
            verifyCalls++
            val expected = when (database) {
                VaultDatabase.RULES -> rulesDek
                VaultDatabase.ACTIVITY -> activityDek
            }
            return verifyResult && dek.contentEquals(expected)
        }
    }
}

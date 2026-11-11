package com.example.myexodus.applock

import android.content.Context
import com.example.myexodus.applock.crypto.VaultEnvelope
import com.example.myexodus.applock.crypto.VaultEnvelopeException
import com.example.myexodus.applock.crypto.VaultEnvelopeStore
import com.example.myexodus.applock.crypto.VaultKeyManager
import com.example.myexodus.applock.crypto.VaultSecretBundle
import com.example.myexodus.applock.crypto.VaultSecretBundleCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

sealed interface VaultMigrationOutcome {
    data object Done : VaultMigrationOutcome

    /** The user dismissed an OS authentication prompt; nothing was committed. */
    data object Cancelled : VaultMigrationOutcome

    /** [reason] is a fixed telemetry-safe category, never an exception message (§14, INV-07). */
    data class Failed(val reason: String) : VaultMigrationOutcome
}

/**
 * OS-authentication seam (§8.5/§8.6). [initialize] builds the raw Cipher; on API 30+ the
 * returned cipher is the prompt-authorized `CryptoObject`, on API 26-29 it is initialized only
 * after Keyguard's RESULT_OK. Null means the user cancelled or authentication failed.
 */
interface VaultCipherAuthorizer {
    suspend fun authorize(initialize: () -> Cipher): Cipher?
}

/**
 * Journaled wrap-only migration (plan §6.1, §8.7 route 1, §8.11). Enrollment re-wraps each
 * store's existing DEK under the auth-bound vault key; disable re-wraps them under the
 * pre-feature automatic Keystore scheme. SQLCipher files are never rekeyed or copied, so a
 * failure at any point restores the previous verified key path without touching user data.
 *
 * Crash ordering: enroll renames the envelope to active *before* the prefs commit that enables
 * the feature (a crash never leaves "enabled" without its key); disable commits "disabled"
 * *before* deleting the auth-bound envelope and alias (a crash never deletes the only key while
 * enabled). Every leftover artifact is swept by [recoverInterrupted] — never treated as a fresh
 * installation (INV-06, §8.12).
 *
 * The caller owns [VaultSecretBundle] passed to [disable] and must zero it once the disabled
 * store can be reopened. The caller must also only call [completeFirstUnlock] after a real,
 * independently initiated unlock opened and verified the vault with these bytes.
 */
@Singleton
class VaultMigration @Inject constructor(
    @ApplicationContext context: Context,
    private val statusStore: AppLockStatusStore,
    private val envelopeStore: VaultEnvelopeStore,
    private val keyManager: VaultKeyManager,
    private val storeKeys: VaultStoreKeys,
) {

    private val applicationId = context.packageName

    /**
     * §6.1 steps 3-7. [quiesce] must close the vault stores and deny new leases; false aborts
     * before any durable write. [authorize] is invoked twice: the wrapping encryption and the
     * independent verification decrypt (§8.4).
     */
    suspend fun enroll(
        quiesce: () -> Boolean,
        authorize: VaultCipherAuthorizer,
    ): VaultMigrationOutcome {
        if (statusStore.isFeatureEnabled()) return VaultMigrationOutcome.Failed("already_enabled")
        if (!quiesce()) return VaultMigrationOutcome.Failed("vault_busy")

        envelopeStore.sweepIncompleteWrites()
        envelopeStore.discardAll()
        statusStore.beginEnrollmentStaging()

        var rulesDek: ByteArray? = null
        var activityDek: ByteArray? = null
        var bundlePayload: ByteArray? = null
        var recoveredPayload: ByteArray? = null
        try {
            val rules = storeKeys.unwrapLegacy(VaultDatabase.RULES).also { rulesDek = it }
            val activity = storeKeys.unwrapLegacy(VaultDatabase.ACTIVITY).also { activityDek = it }
            val payload = VaultSecretBundleCodec.encode(VaultSecretBundle(rules, activity))
                .also { bundlePayload = it }

            keyManager.createKey()
            val encryptCipher = authorize.authorize { keyManager.encryptionCipher() }
                ?: return rollbackEnrollment(VaultMigrationOutcome.Cancelled)
            val sealed = keyManager.seal(
                encryptCipher,
                payload,
                applicationId,
                VAULT_DATABASE_ID,
                kind = VaultEnvelope.KIND_VAULT_DEKS,
            )
            envelopeStore.writeStaging(sealed)

            val staged = envelopeStore.readStaging()
                ?: return rollbackEnrollment(VaultMigrationOutcome.Failed("staging_write_failed"))
            val envelope = VaultEnvelope.parse(staged)
            val decryptCipher =
                authorize.authorize { keyManager.decryptionCipher(envelope.iv, envelope.keyVersion) }
                    ?: return rollbackEnrollment(VaultMigrationOutcome.Cancelled)
            val recovered = keyManager.open(staged, applicationId, VAULT_DATABASE_ID, decryptCipher)
                .also { recoveredPayload = it }
            val recoveredBundle = VaultSecretBundleCodec.decode(recovered)
            val matches = try {
                MessageDigest.isEqual(recoveredBundle.rulesDek, rules) &&
                    MessageDigest.isEqual(recoveredBundle.activityDek, activity)
            } finally {
                Arrays.fill(recoveredBundle.rulesDek, 0.toByte())
                Arrays.fill(recoveredBundle.activityDek, 0.toByte())
            }
            if (!matches) return rollbackEnrollment(VaultMigrationOutcome.Failed("verify_mismatch"))

            val opensExistingData =
                storeKeys.verify(VaultDatabase.RULES, rules) &&
                    storeKeys.verify(VaultDatabase.ACTIVITY, activity)
            if (!opensExistingData) return rollbackEnrollment(VaultMigrationOutcome.Failed("db_verify_failed"))

            if (!envelopeStore.commitStaging()) {
                return rollbackEnrollment(VaultMigrationOutcome.Failed("commit_failed"))
            }
            statusStore.commitEnrollmentActive()
            return VaultMigrationOutcome.Done
        } catch (e: CancellationException) {
            rollbackEnrollment()
            throw e
        } catch (e: VaultEnvelopeException) {
            return rollbackEnrollment(VaultMigrationOutcome.Failed("envelope_error"))
        } catch (e: Exception) {
            return rollbackEnrollment(VaultMigrationOutcome.Failed("crypto_error"))
        } finally {
            rulesDek?.let { Arrays.fill(it, 0.toByte()) }
            activityDek?.let { Arrays.fill(it, 0.toByte()) }
            bundlePayload?.let { Arrays.fill(it, 0.toByte()) }
            recoveredPayload?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * §8.11 inverse migration. The caller has already performed a fresh OS-authenticated
     * unlock and holds the DEKs. Each store is re-wrapped under the pre-feature non-auth-bound
     * KEK, read back through that wrap and opened before the disabled commit; only then are the
     * auth-bound envelope and alias removed.
     */
    fun disable(secrets: VaultSecretBundle, quiesce: () -> Boolean): VaultMigrationOutcome {
        if (!statusStore.isFeatureEnabled() || !envelopeStore.hasActive()) {
            return VaultMigrationOutcome.Failed("not_enabled")
        }
        if (!quiesce()) return VaultMigrationOutcome.Failed("vault_busy")

        val previousJournal = statusStore.journal()
        statusStore.beginDisabling()
        try {
            if (!adoptAndVerify(VaultDatabase.RULES, secrets.rulesDek)) {
                return rollbackDisabling(previousJournal, "rules_verify_failed")
            }
            if (!adoptAndVerify(VaultDatabase.ACTIVITY, secrets.activityDek)) {
                return rollbackDisabling(previousJournal, "activity_verify_failed")
            }
            statusStore.commitDisabled()
        } catch (e: Exception) {
            return rollbackDisabling(previousJournal, "adopt_failed")
        }
        // Committed: the cleanup is idempotent and recoverInterrupted() finishes it after a crash.
        runCatching { envelopeStore.discardAll() }
        runCatching { keyManager.deleteKey() }
        return VaultMigrationOutcome.Done
    }

    /**
     * §6.1 step 8. Call only after a real independent unlock verified the vault. Marks the
     * cleanup state once, then removes the old wrapping material idempotently; an interrupted
     * cleanup leaves the verified journal for the next unlock or app start.
     */
    fun completeFirstUnlock() {
        if (!statusStore.isFeatureEnabled()) return
        if (statusStore.journal() == AppLockStatusStore.JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK) {
            statusStore.markFirstUnlockVerified()
        }
        if (statusStore.journal() != AppLockStatusStore.JOURNAL_FIRST_UNLOCK_VERIFIED) return
        runCatching { storeKeys.deleteLegacyMaterial(VaultDatabase.RULES) }
        runCatching { storeKeys.deleteLegacyMaterial(VaultDatabase.ACTIVITY) }
        envelopeStore.discardStaging()
        envelopeStore.sweepIncompleteWrites()
    }

    /**
     * §4.2 rule 3 / §8.12: journal-first startup reconciliation. DISABLED sweeps pre-commit
     * leftovers (the pre-feature wrap is untouched); LOCKED requires the active envelope and
     * finishes any interrupted journal step; every recovery state is preserved untouched.
     */
    fun recoverInterrupted(): AppLockState {
        envelopeStore.sweepIncompleteWrites()
        return when (val state = statusStore.startupState()) {
            AppLockState.DISABLED -> {
                if (statusStore.journal() == AppLockStatusStore.JOURNAL_STAGING ||
                    envelopeStore.hasActive() ||
                    envelopeStore.hasStaging()
                ) {
                    envelopeStore.discardAll()
                    runCatching { keyManager.deleteKey() }
                    statusStore.rollbackEnrollment()
                }
                AppLockState.DISABLED
            }
            AppLockState.LOCKED -> {
                if (!envelopeStore.hasActive()) {
                    AppLockState.RECOVERY_REQUIRED
                } else {
                    envelopeStore.discardStaging()
                    when (statusStore.journal()) {
                        AppLockStatusStore.JOURNAL_DISABLING ->
                            statusStore.restoreActivePendingJournal()
                        AppLockStatusStore.JOURNAL_FIRST_UNLOCK_VERIFIED ->
                            completeFirstUnlock()
                    }
                    AppLockState.LOCKED
                }
            }
            else -> state
        }
    }

    private fun adoptAndVerify(database: VaultDatabase, dek: ByteArray): Boolean {
        storeKeys.adoptLegacy(database, dek)
        val check = storeKeys.unwrapLegacy(database)
        return try {
            MessageDigest.isEqual(check, dek) && storeKeys.verify(database, check)
        } finally {
            Arrays.fill(check, 0.toByte())
        }
    }

    private fun rollbackEnrollment(outcome: VaultMigrationOutcome): VaultMigrationOutcome {
        rollbackEnrollment()
        return outcome
    }

    private fun rollbackEnrollment() {
        runCatching { envelopeStore.discardAll() }
        statusStore.rollbackEnrollment()
    }

    private fun rollbackDisabling(previousJournal: String?, reason: String): VaultMigrationOutcome {
        statusStore.rollbackDisabling(previousJournal)
        return VaultMigrationOutcome.Failed(reason)
    }
}

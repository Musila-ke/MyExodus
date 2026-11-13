package com.example.myexodus.applock

import android.content.Context
import android.util.Log
import com.example.myexodus.applock.crypto.VaultEnvelope
import com.example.myexodus.applock.crypto.VaultEnvelopeException
import com.example.myexodus.applock.crypto.VaultEnvelopeStore
import com.example.myexodus.applock.crypto.VaultKeyManager
import com.example.myexodus.applock.crypto.VaultSecretBundle
import com.example.myexodus.applock.crypto.VaultSecretBundleCodec
import com.example.myexodus.data.db.ActivityLogStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.VaultLockedException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** §9 user-visible failure categories; the UI owns the copy. */
enum class AppLockFailure {
    NoScreenLock,
    NoSupportedAuthenticator,
    VaultBusy,
    Unavailable,
}

sealed interface AppLockResult {
    data object Done : AppLockResult
    data object Cancelled : AppLockResult

    /** Non-destructive: the artifact state needs a user-led recovery, never a fresh DB (§9). */
    data object RecoveryRequired : AppLockResult
    data class Failed(val failure: AppLockFailure) : AppLockResult
}

/**
 * Owns the three exclusive operations of the state machine (§4.2): enable (enroll), unlock and
 * disable. One mutex serializes them app-wide; the Activity owns only the prompt.
 *
 * Unlock is the only path that publishes UNLOCKED, and only after the forced SQLCipher open and
 * a known schema read succeeded (§8.3 item 6). Enrollment ends LOCKED: the first real unlock is
 * the independent proof that retires the old wrapping material (§6.1 step 8).
 */
@Singleton
class AppLockController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AppLockCoordinator,
    private val statusStore: AppLockStatusStore,
    private val migration: VaultMigration,
    private val dekSource: VaultDekSource,
    private val envelopeStore: VaultEnvelopeStore,
    private val keyManager: VaultKeyManager,
    private val rulesStoreFactory: AppRulesStoreFactory,
    private val activityLogStore: ActivityLogStore,
    private val reset: VaultRecoveryReset,
) {

    private val mutex = Mutex()

    fun preflight(): AppLockPreflight = AppLockAuthenticator.preflight(context)

    /** §5 behavior 3: Lock now ends the session immediately and ignores every exemption. */
    fun lockNow() = coordinator.lockManually()

    /** §8.8: armed by the authorizer immediately before the API 26-29 Keyguard sheet. */
    fun beginCredentialChallenge() = coordinator.beginCredentialChallenge()

    /** §4.2: the enable flow is the only way into SETUP_REQUIRED from DISABLED. */
    fun beginSetup(): Boolean {
        if (coordinator.state.value != AppLockState.DISABLED) return false
        coordinator.publish(AppLockState.SETUP_REQUIRED)
        return true
    }

    /** §4.2: setup abandoned before an envelope exists returns to the prior valid state. */
    fun cancelSetup(): Boolean {
        if (coordinator.state.value != AppLockState.SETUP_REQUIRED) return false
        coordinator.publish(AppLockState.DISABLED)
        return true
    }

    /** §6.1 steps 1-7. [authorize] is invoked for the wrapping encrypt and the verify decrypt. */
    suspend fun enroll(authorize: VaultCipherAuthorizer): AppLockResult = mutex.withLock {
        if (coordinator.state.value != AppLockState.SETUP_REQUIRED) {
            return AppLockResult.Failed(AppLockFailure.Unavailable)
        }
        preflightFailure()?.let { return it }
        coordinator.publish(AppLockState.ENROLLING)
        coordinator.beginAttempt()
        // §6.1 step 3: a device that never logged activity has no activity file (device-caught
        // 2026-11-11). Create it under the legacy DEK before wrapping, or the first unlock's
        // verified open refuses to recreate it under the vault and lands in recovery.
        if (!withContext(Dispatchers.IO) { activityLogStore.open() }) {
            Log.w(TAG, "App Lock enrollment refused: activity log unavailable")
            coordinator.concludeAttempt(AppLockState.DISABLED)
            return AppLockResult.Failed(AppLockFailure.Unavailable)
        }
        // §4.2 rule 2: a journaled migration is application-owned; cancellation must not tear
        // it down between the staged envelope and the commit.
        // The journal commit is application-owned, so the state conclusion after it must not be
        // lost to a caller cancellation either (the gate swaps composition mid-operation).
        withContext(Dispatchers.IO + NonCancellable) {
            when (val outcome = migration.enroll(quiesce = ::quiesce, authorize = authorize)) {
                VaultMigrationOutcome.Done -> {
                    // §6.1 step 7: close and show LOCKED; step 8 verifies on the next unlock.
                    coordinator.concludeAttempt(AppLockState.LOCKED)
                    AppLockResult.Done
                }
                VaultMigrationOutcome.Cancelled -> {
                    coordinator.concludeAttempt(AppLockState.DISABLED)
                    AppLockResult.Cancelled
                }
                is VaultMigrationOutcome.Failed -> {
                    Log.w(TAG, "App Lock enrollment failed: ${outcome.reason}")
                    val next =
                        if (statusStore.isFeatureEnabled()) AppLockState.LOCKED else AppLockState.DISABLED
                    coordinator.concludeAttempt(next)
                    AppLockResult.Failed(
                        if (outcome.reason == "vault_busy") AppLockFailure.VaultBusy
                        else AppLockFailure.Unavailable,
                    )
                }
            }
        }
    }

    /** §6.2: LOCKED → PREFLIGHT → AUTHENTICATING → OPENING → verified open → UNLOCKED. */
    suspend fun unlock(authorize: VaultCipherAuthorizer): AppLockResult = mutex.withLock {
        val initial = coordinator.state.value
        if (initial == AppLockState.RECOVERY_REQUIRED) return AppLockResult.RecoveryRequired
        if (initial != AppLockState.LOCKED) return AppLockResult.Failed(AppLockFailure.Unavailable)
        preflightFailure()?.let { return it }
        coordinator.publish(AppLockState.PREFLIGHT)
        val attempt = coordinator.beginAttempt()
        try {
            val encoded = withContext(Dispatchers.IO) { envelopeStore.readActive() }
                ?: return failRecovery(attempt, "vault envelope missing")
            val envelope = try {
                VaultEnvelope.parse(encoded)
            } catch (e: VaultEnvelopeException) {
                Log.e(TAG, "Vault envelope unreadable", e)
                return failRecovery(attempt, "vault envelope unreadable")
            }
            coordinator.publish(AppLockState.AUTHENTICATING)
            val cipher = authorize.authorize {
                keyManager.decryptionCipher(envelope.iv, envelope.keyVersion)
            }
            // §8.8: a late callback from a cancelled, locked or replaced attempt grants nothing.
            if (cipher == null || !coordinator.isAttemptCurrent(attempt)) return abandon()
            coordinator.publish(AppLockState.OPENING)
            when (withContext(Dispatchers.IO) { verifyOpen(encoded, cipher, attempt) }) {
                VerifyResult.Ok -> Unit
                VerifyResult.Stale -> return abandon()
                VerifyResult.Failed -> return failRecovery(attempt, "vault open failed")
            }
            // §4.2 rule 6: screen-off during OPENING cancels the attempt, even mid-verify.
            if (!coordinator.isAttemptCurrent(attempt) ||
                coordinator.state.value != AppLockState.OPENING
            ) {
                return abandon()
            }
            withContext(NonCancellable + Dispatchers.IO) { migration.completeFirstUnlock() }
            if (!coordinator.isAttemptCurrent(attempt)) return abandon()
            coordinator.publishUnlocked()
            AppLockResult.Done
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                coordinator.concludeAttempt(AppLockState.LOCKED)
                wipeSession()
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "App Lock unlock failed", e)
            failRecovery(attempt, "unexpected failure")
        }
    }

    /**
     * §8.11: authenticate OS, verify the envelope and both stores under the recovered bytes,
     * then run the journaled inverse migration. The session keeps its UNLOCKED state through
     * the prompt so the DEKs the disable needs are still held; DISABLING starts only once the
     * user authorized and the snapshot is taken.
     */
    suspend fun disable(authorize: VaultCipherAuthorizer): AppLockResult = mutex.withLock {
        if (coordinator.state.value != AppLockState.UNLOCKED || !statusStore.isFeatureEnabled()) {
            return AppLockResult.Failed(AppLockFailure.Unavailable)
        }
        preflightFailure()?.let { return it }
        val attempt = coordinator.beginAttempt()
        val encoded = withContext(Dispatchers.IO) { envelopeStore.readActive() }
            ?: return AppLockResult.RecoveryRequired
        val envelope = try {
            VaultEnvelope.parse(encoded)
        } catch (e: VaultEnvelopeException) {
            Log.e(TAG, "Disable: vault envelope unreadable", e)
            return AppLockResult.RecoveryRequired
        }
        val cipher = try {
            authorize.authorize { keyManager.decryptionCipher(envelope.iv, envelope.keyVersion) }
        } catch (e: CancellationException) {
            coordinator.abandonAttempt()
            throw e
        }
        // A cancelled or superseded confirmation leaves the live session untouched.
        if (cipher == null || !coordinator.isAttemptCurrent(attempt)) {
            coordinator.abandonAttempt()
            return AppLockResult.Cancelled
        }
        val snapshot = dekSource.copyBundle()
            ?: return AppLockResult.Failed(AppLockFailure.Unavailable)
        if (!matchesEnvelope(encoded, cipher, snapshot)) {
            zero(snapshot)
            coordinator.concludeAttempt(AppLockState.LOCKED)
            wipeSession()
            Log.e(TAG, "Disable: envelope plaintext does not match the live session")
            return AppLockResult.Failed(AppLockFailure.Unavailable)
        }
        coordinator.publish(AppLockState.DISABLING)
        return withContext(Dispatchers.IO + NonCancellable) {
            val outcome = migration.disable(snapshot, quiesce = ::quiesce)
            zero(snapshot)
            when (outcome) {
                VaultMigrationOutcome.Done -> {
                    coordinator.publishDisabled()
                    reopenUnderLegacyKeys()
                    AppLockResult.Done
                }
                is VaultMigrationOutcome.Failed -> {
                    Log.w(TAG, "App Lock disable rolled back: ${outcome.reason}")
                    coordinator.concludeAttempt(AppLockState.LOCKED)
                    wipeSession()
                    AppLockResult.Failed(AppLockFailure.Unavailable)
                }
                VaultMigrationOutcome.Cancelled -> {
                    coordinator.concludeAttempt(AppLockState.LOCKED)
                    wipeSession()
                    AppLockResult.Cancelled
                }
            }
        }
    }

    /**
     * §4.2/§9: a user-led retry re-arms the lock screen. RECOVERY_REQUIRED is never cleared
     * silently; the following unlock re-runs the forced, verified open and falls back here.
     */
    fun retryRecovery(): Boolean {
        if (coordinator.state.value != AppLockState.RECOVERY_REQUIRED) return false
        coordinator.publish(AppLockState.LOCKED)
        return true
    }

    /**
     * §8.11 explicit reset, reachable only from RECOVERY_REQUIRED after the UI collected the
     * user's explicit confirmation. The durable commit is last: until every vault-keyed file is
     * gone the lock stays in recovery, so an interrupted reset can only be retried, never
     * mistaken for a successful recovery.
     */
    suspend fun resetRecovery(): AppLockResult = mutex.withLock {
        if (coordinator.state.value != AppLockState.RECOVERY_REQUIRED) {
            return AppLockResult.Failed(AppLockFailure.Unavailable)
        }
        return withContext(Dispatchers.IO + NonCancellable) {
            quiesce()
            try {
                reset.erase()
            } catch (e: Exception) {
                Log.e(TAG, "App Lock reset failed; recovery evidence kept", e)
                return@withContext AppLockResult.Failed(AppLockFailure.Unavailable)
            }
            statusStore.commitDisabled()
            coordinator.publishDisabled()
            reopenUnderLegacyKeys()
            AppLockResult.Done
        }
    }

    private fun preflightFailure(): AppLockResult.Failed? = when (val result = preflight()) {
        AppLockPreflight.Ready -> null
        AppLockPreflight.NoScreenLock -> AppLockResult.Failed(AppLockFailure.NoScreenLock)
        AppLockPreflight.NoSupportedAuthenticator ->
            AppLockResult.Failed(AppLockFailure.NoSupportedAuthenticator)
    }

    /**
     * §8.3 item 6: decrypt the envelope, adopt the DEKs, then force the real store opens and a
     * known schema read before any caller may publish UNLOCKED.
     */
    private suspend fun verifyOpen(
        encoded: ByteArray,
        cipher: Cipher,
        attempt: Long,
    ): VerifyResult {
        return try {
            val payload = keyManager.open(encoded, context.packageName, VAULT_DATABASE_ID, cipher)
            val bundle = try {
                VaultSecretBundleCodec.decode(payload)
            } finally {
                Arrays.fill(payload, 0.toByte())
            }
            try {
                dekSource.adopt(bundle)
            } finally {
                Arrays.fill(bundle.rulesDek, 0.toByte())
                Arrays.fill(bundle.activityDek, 0.toByte())
            }
            // Forces Room's actual open and reads one known table (§8.3 item 6).
            val sessions = rulesStoreFactory.withVaultSuspend { store ->
                store.recoverySessionDao().count()
            } ?: return VerifyResult.Failed
            if (!activityLogStore.open()) return VerifyResult.Failed
            val logged = activityLogStore.count()
            Log.i(TAG, "Vault verified: sessions=$sessions log_rows=$logged")
            if (!coordinator.isAttemptCurrent(attempt)) {
                dekSource.clear()
                return VerifyResult.Stale
            }
            VerifyResult.Ok
        } catch (e: VaultLockedException) {
            dekSource.clear()
            VerifyResult.Stale
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dekSource.clear()
            Log.e(TAG, "Vault open verification failed", e)
            VerifyResult.Failed
        }
    }

    private fun matchesEnvelope(
        encoded: ByteArray,
        cipher: Cipher,
        snapshot: VaultSecretBundle,
    ): Boolean = try {
        val payload = keyManager.open(encoded, context.packageName, VAULT_DATABASE_ID, cipher)
        val bundle = try {
            VaultSecretBundleCodec.decode(payload)
        } finally {
            Arrays.fill(payload, 0.toByte())
        }
        try {
            MessageDigest.isEqual(bundle.rulesDek, snapshot.rulesDek) &&
                MessageDigest.isEqual(bundle.activityDek, snapshot.activityDek)
        } finally {
            Arrays.fill(bundle.rulesDek, 0.toByte())
            Arrays.fill(bundle.activityDek, 0.toByte())
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Disable: envelope decrypt failed", e)
        false
    }

    /** Closes every store and drops the session bytes; the snapshot is the caller's copy. */
    private fun quiesce(): Boolean {
        dekSource.clear()
        activityLogStore.close()
        return runCatching { rulesStoreFactory.lockVault() }
            .onFailure { Log.w(TAG, "vault close failed", it) }
            .getOrDefault(false)
    }

    private suspend fun abandon(): AppLockResult {
        withContext(NonCancellable) {
            coordinator.concludeAttempt(AppLockState.LOCKED)
            wipeSession()
        }
        return AppLockResult.Cancelled
    }

    private suspend fun failRecovery(attempt: Long, reason: String): AppLockResult {
        if (!coordinator.isAttemptCurrent(attempt)) return abandon()
        Log.e(TAG, "App Lock recovery required: $reason")
        withContext(NonCancellable) {
            wipeSession()
            coordinator.concludeAttempt(AppLockState.RECOVERY_REQUIRED)
        }
        return AppLockResult.RecoveryRequired
    }

    private fun wipeSession() {
        dekSource.clear()
        activityLogStore.close()
        runCatching { rulesStoreFactory.lockVault() }
            .onFailure { Log.w(TAG, "vault close failed", it) }
    }

    /** §8.11: the disable commit promised a working disabled-policy store; open it now. */
    private fun reopenUnderLegacyKeys() {
        runCatching { rulesStoreFactory.open() }
            .onFailure { Log.w(TAG, "rules store reopen after disable failed", it) }
        runCatching { activityLogStore.open() }
            .onFailure { Log.w(TAG, "activity log reopen after disable failed", it) }
    }

    private fun zero(bundle: VaultSecretBundle) {
        Arrays.fill(bundle.rulesDek, 0.toByte())
        Arrays.fill(bundle.activityDek, 0.toByte())
    }

    private enum class VerifyResult { Ok, Stale, Failed }

    private companion object {
        const val TAG = "MyExodusAppLock"
    }
}

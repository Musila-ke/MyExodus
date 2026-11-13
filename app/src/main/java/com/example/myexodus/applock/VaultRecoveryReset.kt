package com.example.myexodus.applock

import android.util.Log
import com.example.myexodus.applock.crypto.VaultEnvelopeStore
import com.example.myexodus.applock.crypto.VaultKeyManager
import com.example.myexodus.data.db.RecoveryManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan §8.11 explicit reset: the only destructive escape from RECOVERY_REQUIRED, and only
 * after the user's unambiguous confirmation. The caller must have quiesced both vault stores
 * first. Database ciphertext goes before any status commit, so the feature can never read as
 * disabled while a store keyed by the lost vault still exists; every step is idempotent, so a
 * crash anywhere leaves RECOVERY_REQUIRED and the next confirmation repeats from the top.
 */
@Singleton
class VaultRecoveryReset @Inject constructor(
    private val recoveryManager: RecoveryManager,
    private val envelopeStore: VaultEnvelopeStore,
    private val keyManager: VaultKeyManager,
    private val storeKeys: VaultStoreKeys,
) {

    fun erase() {
        recoveryManager.resetAllData()
        envelopeStore.discardAll()
        envelopeStore.sweepIncompleteWrites()
        // Missing aliases are a no-op; a broken Keystore must not strand the already-erased
        // device in RECOVERY_REQUIRED, and neither alias can decrypt anything that remains.
        runCatching { keyManager.deleteKey() }
            .onFailure { Log.w(TAG, "Vault alias deletion failed during reset", it) }
        runCatching { storeKeys.deleteLegacyMaterial(VaultDatabase.RULES) }
            .onFailure { Log.w(TAG, "Rules legacy material deletion failed during reset", it) }
        runCatching { storeKeys.deleteLegacyMaterial(VaultDatabase.ACTIVITY) }
            .onFailure { Log.w(TAG, "Activity legacy material deletion failed during reset", it) }
    }

    private companion object {
        const val TAG = "MyExodusAppLock"
    }
}

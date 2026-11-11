package com.example.myexodus.applock

import com.example.myexodus.applock.crypto.VaultSecretBundle
import com.example.myexodus.data.db.VaultLockedException
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault-enabled DEK source (§8.3): while App Lock is on, both SQLCipher stores obtain
 * their key here instead of the pre-feature Keystore wrap. Populated only by a verified
 * unlock or enrollment and zeroed at every lock. A locked session throws rather than allowing
 * the legacy managers to mint a replacement DEK for a file the envelope still owns.
 *
 * Every [dek] call returns a fresh copy because the SQLCipher driver zeroes what it receives.
 */
@Singleton
class VaultDekSource @Inject constructor(private val statusStore: AppLockStatusStore) {

    private var rules: ByteArray? = null
    private var activity: ByteArray? = null

    @Synchronized
    fun adopt(bundle: VaultSecretBundle) {
        clear()
        rules = bundle.rulesDek.copyOf()
        activity = bundle.activityDek.copyOf()
    }

    /** Null only when the feature is off (legacy key path); throws when it is on but locked. */
    @Synchronized
    fun dek(database: VaultDatabase): ByteArray? {
        if (!statusStore.isFeatureEnabled()) return null
        val dek = when (database) {
            VaultDatabase.RULES -> rules
            VaultDatabase.ACTIVITY -> activity
        } ?: throw VaultLockedException()
        return dek.copyOf()
    }

    /** True while App Lock owns the SQLCipher keys, locked or not (plan §9 recovery rules). */
    fun isEnabled(): Boolean = statusStore.isFeatureEnabled()

    /** Copy for a caller that owns it (disable re-wrap); null when no session is held. */
    @Synchronized
    fun copyBundle(): VaultSecretBundle? {
        val r = rules ?: return null
        val a = activity ?: return null
        return VaultSecretBundle(r.copyOf(), a.copyOf())
    }

    @Synchronized
    fun clear() {
        rules?.let { Arrays.fill(it, 0.toByte()) }
        activity?.let { Arrays.fill(it, 0.toByte()) }
        rules = null
        activity = null
    }
}

package com.example.myexodus.data.db

import android.content.Context

/**
 * Keystore-wrapped DEK for the main rules store (`myexodus_rules.db`), encrypted at rest per
 * the habit-streaks plan (§8 Step 1, Decision 15). Thin wrapper over [KeystoreWrappedDekManager];
 * scheme details live there.
 */
class RulesStoreKeyManager(
    appContext: Context,
) : DatabaseKeyManager {

    private val delegate = KeystoreWrappedDekManager(appContext, KEK_ALIAS, PREFS_NAME)

    override fun getOrCreateDek(): ByteArray = delegate.getOrCreateKey()

    override fun classifyFailure(e: Throwable): KeyFailure = delegate.classifyFailure(e)

    override fun resetKeyMaterial() = delegate.resetKeyMaterial()

    /** True once a wrapped DEK exists — the conversion's second precondition (§8 Step 0). */
    fun hasWrappedKey(): Boolean = delegate.hasWrappedKey()

    private companion object {
        const val KEK_ALIAS = "myexodus_rules_kek"
        const val PREFS_NAME = "myexodus_rules_db_keys"
    }
}

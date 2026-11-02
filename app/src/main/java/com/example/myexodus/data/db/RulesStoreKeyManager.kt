package com.example.myexodus.data.db

import android.content.Context

/**
 * Keystore-wrapped DEK for the main rules store (`myexodus_rules.db`), encrypted at rest per
 * the habit-streaks plan (§8 Step 1, Decision 15). Thin wrapper over [KeystoreWrappedDekManager];
 * scheme details live there. Once [LocalDatabaseMigration] has scoped this install, every call
 * targets the UID-suffixed alias and prefs file; before that the legacy names apply, which are
 * also what the retained pre-scope stores were keyed with.
 */
class RulesStoreKeyManager(
    private val appContext: Context,
) : DatabaseKeyManager {

    // Resolved per call, not cached: the store can be keyed before or after the scope activates
    // (e.g. a pre-unlock process that migrates only on its USER_UNLOCKED pass).
    private fun delegate(keySuffix: String = LocalDatabaseMigration.keySuffix()): KeystoreWrappedDekManager =
        KeystoreWrappedDekManager(
            appContext,
            LEGACY_KEK_ALIAS + keySuffix,
            LEGACY_PREFS + keySuffix,
        )

    override fun getOrCreateDek(): ByteArray = delegate().getOrCreateKey()

    /** DEK for a file whose scope was captured earlier — name and key must come from one scope. */
    fun getOrCreateDek(keySuffix: String): ByteArray = delegate(keySuffix).getOrCreateKey()

    override fun classifyFailure(e: Throwable): KeyFailure = delegate().classifyFailure(e)

    override fun resetKeyMaterial() = delegate().resetKeyMaterial()

    /** True once a wrapped DEK exists — the conversion's second precondition (§8 Step 0). */
    fun hasWrappedKey(): Boolean = delegate().hasWrappedKey()

    companion object {
        internal const val LEGACY_KEK_ALIAS = "myexodus_rules_kek"
        internal const val LEGACY_PREFS = "myexodus_rules_db_keys"
    }
}

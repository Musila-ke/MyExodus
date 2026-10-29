package com.example.myexodus.data.db

import android.content.Context

/**
 * Key material for [ActivityLogStore]: Keystore-wrapped DEK, thin wrapper over
 * [KeystoreWrappedDekManager] (consolidated 2026-08-28 review). Scope handling is identical to
 * [RulesStoreKeyManager]: the alias and prefs file carry the UID suffix once
 * [LocalDatabaseMigration] activates, and stay legacy until then. The returned [ByteArray]
 * must be zeroed by the caller (`Arrays.fill(key, 0)`) immediately after keying completes.
 */
class ActivityLogKeyManager(
    private val appContext: Context,
) {
    private fun delegate(): KeystoreWrappedDekManager = KeystoreWrappedDekManager(
        appContext,
        LEGACY_KEK_ALIAS + LocalDatabaseMigration.keySuffix(),
        LEGACY_PREFS + LocalDatabaseMigration.keySuffix(),
    )

    fun getOrCreateKey(): ByteArray = delegate().getOrCreateKey()

    fun classifyFailure(e: Throwable): KeyFailure = delegate().classifyFailure(e)

    fun resetKeyMaterial() = delegate().resetKeyMaterial()

    companion object {
        internal const val LEGACY_KEK_ALIAS = "myexodus_activity_log_kek"
        internal const val LEGACY_PREFS = "myexodus_activity_log_keys"
    }
}

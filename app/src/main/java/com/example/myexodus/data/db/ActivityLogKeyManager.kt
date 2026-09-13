package com.example.myexodus.data.db

import android.content.Context

/**
 * Key material for [ActivityLogStore]: Keystore-wrapped DEK, thin wrapper over
 * [KeystoreWrappedDekManager] (consolidated 2026-08-28 review). The returned [ByteArray]
 * must be zeroed by the caller (`Arrays.fill(key, 0)`) immediately after keying completes.
 */
class ActivityLogKeyManager(
    appContext: Context,
) {
    private val delegate = KeystoreWrappedDekManager(appContext, KEK_ALIAS, PREFS_NAME)

    fun getOrCreateKey(): ByteArray = delegate.getOrCreateKey()

    fun classifyFailure(e: Throwable): KeyFailure = delegate.classifyFailure(e)

    fun resetKeyMaterial() = delegate.resetKeyMaterial()

    private companion object {
        const val KEK_ALIAS = "myexodus_activity_log_kek"
        const val PREFS_NAME = "myexodus_activity_log_keys"
    }
}

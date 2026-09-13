package com.example.myexodus.data.db

import android.content.Context

/**
 * Keystore-wrapped DEK for the pre-split legacy database (myexodus.db). Thin wrapper over
 * [KeystoreWrappedDekManager]; scheme details live there (consolidated 2026-08-28 review).
 */
class AndroidDatabaseKeyManager(
    appContext: Context,
) : DatabaseKeyManager {

    private val delegate = KeystoreWrappedDekManager(appContext, KEK_ALIAS, PREFS_NAME)

    override fun getOrCreateDek(): ByteArray = delegate.getOrCreateKey()

    override fun classifyFailure(e: Throwable): KeyFailure = delegate.classifyFailure(e)

    override fun resetKeyMaterial() = delegate.resetKeyMaterial()

    private companion object {
        const val KEK_ALIAS = "myexodus_db_kek"
        const val PREFS_NAME = "myexodus_db_keys"
    }
}

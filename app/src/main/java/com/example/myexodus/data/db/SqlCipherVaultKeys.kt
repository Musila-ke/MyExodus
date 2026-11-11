package com.example.myexodus.data.db

import android.content.Context
import com.example.myexodus.applock.VaultDatabase
import com.example.myexodus.applock.VaultStoreKeys
import java.io.File
import java.util.Arrays
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Production [VaultStoreKeys]: the pre-feature Keystore-wrapped-DEK scheme, in whichever scope
 * flavor is live (§8.7 route 1 — the DEK itself never changes, only its wrapper). Verification
 * opens the real store with the DEK and reads once, so enrollment/disable decisions rest on the
 * same SQLCipher engine the app uses; a missing file is not an error because a store that has
 * not been created yet carries no data to protect.
 */
internal class SqlCipherVaultKeys(private val appContext: Context) : VaultStoreKeys {

    override fun unwrapLegacy(database: VaultDatabase): ByteArray = when (database) {
        VaultDatabase.RULES -> RulesStoreKeyManager(appContext).getOrCreateDek()
        VaultDatabase.ACTIVITY -> ActivityLogKeyManager(appContext).getOrCreateKey()
    }

    override fun adoptLegacy(database: VaultDatabase, dek: ByteArray) = delegate(database).adoptKey(dek)

    override fun deleteLegacyMaterial(database: VaultDatabase) = delegate(database).deleteKeyMaterial()

    override fun verify(database: VaultDatabase, dek: ByteArray): Boolean {
        val file = databaseFile(database)
        if (!file.exists()) return true
        // Self-contained like every other SQLCipher consumer in the app: the first touch in a
        // process dies with UnsatisfiedLinkError otherwise (finding #66).
        System.loadLibrary("sqlcipher")
        val raw = SqlCipherRawKey.passphraseFor(dek)
        var handle: SQLiteDatabase? = null
        return try {
            // OPEN_READWRITE, not read-only: a WAL database whose -shm was removed on last
            // close cannot be opened read-only, and this is a brief probe with no writes.
            handle = SQLiteDatabase.openDatabase(file.path, raw, null, SQLiteDatabase.OPEN_READWRITE, null)
            handle.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            true
        } catch (t: Throwable) {
            if (SqlCipherRawKey.isWrongKey(t)) false else throw t
        } finally {
            handle?.let { runCatching { it.close() } }
            Arrays.fill(raw, 0.toByte())
        }
    }

    private fun delegate(database: VaultDatabase): KeystoreWrappedDekManager {
        val suffix = LocalDatabaseMigration.keySuffix()
        return when (database) {
            VaultDatabase.RULES -> KeystoreWrappedDekManager(
                appContext,
                RulesStoreKeyManager.LEGACY_KEK_ALIAS + suffix,
                RulesStoreKeyManager.LEGACY_PREFS + suffix,
            )
            VaultDatabase.ACTIVITY -> KeystoreWrappedDekManager(
                appContext,
                ActivityLogKeyManager.LEGACY_KEK_ALIAS + suffix,
                ActivityLogKeyManager.LEGACY_PREFS + suffix,
            )
        }
    }

    private fun databaseFile(database: VaultDatabase): File = when (database) {
        VaultDatabase.RULES -> {
            // File name and key suffix must come from one scope read (2026-11-01 mismatch).
            val scope = LocalDatabaseMigration.currentRulesScope()
            appContext.getDatabasePath(scope.rulesName)
        }
        VaultDatabase.ACTIVITY ->
            appContext.getDatabasePath(LocalDatabaseMigration.activityDbName(appContext))
    }
}

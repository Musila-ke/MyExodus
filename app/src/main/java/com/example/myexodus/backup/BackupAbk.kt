package com.example.myexodus.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.myexodus.data.db.KeystoreWrappedDekManager

/**
 * ADR #310: the one deliberate exception to §6.4 item 5's "never persist the ABK". The ABK is
 * stored only wrapped by a Keystore-held KEK — never as cleartext, never exportable — so an
 * automatic pass can seal a snapshot without a secret the user has to retype. The passphrase and
 * recovery key remain the restore paths and the only way to re-adopt a key on a new device.
 *
 * A stored key is bound to the UID and the bundle key ID it was adopted from; any mismatch reads
 * as absent, which sends the caller back to a manual unlock.
 */
interface BackupAbk {
    /** Best effort: a Keystore failure must never fail the unlock that produced [abk]. */
    fun save(uid: String, keyId: ByteArray, abk: ByteArray)

    /** Returns a copy the caller must zero after use, or null when absent or bound elsewhere. */
    fun load(uid: String, keyId: ByteArray): ByteArray?

    fun has(uid: String): Boolean

    data object None : BackupAbk {
        override fun save(uid: String, keyId: ByteArray, abk: ByteArray) = Unit
        override fun load(uid: String, keyId: ByteArray): ByteArray? = null
        override fun has(uid: String): Boolean = false
    }
}

/** Production store: the same KEK-wrapped-DEK scheme the SQLCipher stores use. */
class KeystoreBackupAbk(context: Context) : BackupAbk {

    // Lazy for the same reason as KeystoreWrappedDekManager: CE storage is unavailable
    // pre-unlock, and this object is constructed with the graph at process start.
    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val wrapped = KeystoreWrappedDekManager(context, KEK_ALIAS, PREFS_NAME)

    override fun save(uid: String, keyId: ByteArray, abk: ByteArray) {
        try {
            wrapped.adoptKey(abk)
            prefs.edit {
                putString(KEY_UID, uid)
                putString(KEY_KEY_ID, Base64.encodeToString(keyId, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            Log.w(TAG, "backup ABK adoption failed", e)
        }
    }

    override fun load(uid: String, keyId: ByteArray): ByteArray? {
        if (!has(uid)) return null
        return try {
            val stored = prefs.getString(KEY_KEY_ID, null) ?: return null
            if (!Base64.decode(stored, Base64.NO_WRAP).contentEquals(keyId)) return null
            wrapped.getOrCreateKey()
        } catch (e: Exception) {
            Log.w(TAG, "backup ABK unwrap failed", e)
            null
        }
    }

    override fun has(uid: String): Boolean = try {
        wrapped.hasWrappedKey() && prefs.getString(KEY_UID, null) == uid
    } catch (e: Exception) {
        Log.w(TAG, "backup ABK state read failed", e)
        false
    }

    private companion object {
        const val TAG = "MyExodusBackup"
        const val KEK_ALIAS = "myexodus_backup_abk_kek"
        const val PREFS_NAME = "myexodus_backup_abk"
        const val KEY_UID = "abk_uid"
        const val KEY_KEY_ID = "abk_key_id"
    }
}

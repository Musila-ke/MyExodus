package com.example.myexodus.account

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.example.myexodus.data.db.KeystoreWrappedDekManager
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Account plan §8.4: the user's explicit choice, fixed at preparation time. */
enum class TransitionIntent { IMPORT, DISCARD }

/**
 * Account plan §8.4: the encrypted transition journal. Persisted BEFORE the prepare call so a
 * lost response is recovered by replaying the same id/capability (the server treats that pair
 * as idempotent), and retained across the identity switch so finalization can be retried from
 * the target session. The capability is a bearer secret — it lives only here, never in prefs
 * plaintext, logs, or the database.
 */
data class GuestTransitionRecord(
    val transitionId: String,
    val capability: String,
    val intent: TransitionIntent,
    val guestUid: String,
    val prepared: Boolean,
    /** Plan §6.2 step 6: recorded before finalization so a process death resumes the same target. */
    val targetUid: String? = null,
)

/** Missing and unreadable are distinct: unreadable means the journal existed and was lost. */
sealed interface JournalLoad {
    data object Absent : JournalLoad
    data class Present(val record: GuestTransitionRecord) : JournalLoad
    data object Unreadable : JournalLoad
}

interface GuestTransitionJournal {
    fun load(): JournalLoad
    fun save(record: GuestTransitionRecord)
    fun clear()
}

/**
 * Keystore-backed journal: the same wrapped-DEK scheme the SQLCipher stores use
 * (KeystoreWrappedDekManager — Keystore KEK, random DEK, AES-256-GCM), with the record's
 * ciphertext in a private prefs file. No EncryptedSharedPreferences (Decision 12).
 */
class KeystoreGuestTransitionJournal(appContext: Context) : GuestTransitionJournal {

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keys = KeystoreWrappedDekManager(appContext, KEK_ALIAS, PREFS_NAME)

    override fun load(): JournalLoad {
        val iv = prefs.getString(KEY_IV, null) ?: return JournalLoad.Absent
        val blob = prefs.getString(KEY_BLOB, null) ?: return JournalLoad.Absent
        return try {
            val plaintext = decrypt(iv, blob)
            JournalLoad.Present(recordOf(JSONObject(String(plaintext, Charsets.UTF_8))))
        } catch (e: GeneralSecurityException) {
            // Keystore invalidation / tamper: unrecoverable without the capability. The
            // caller clears the journal and reports it; the server record expires in 24h.
            Log.w(TAG, "transition journal unreadable", e)
            JournalLoad.Unreadable
        } catch (e: Exception) {
            Log.w(TAG, "transition journal malformed", e)
            JournalLoad.Unreadable
        }
    }

    override fun save(record: GuestTransitionRecord) {
        val json = JSONObject()
            .put(KEY_TRANSITION_ID, record.transitionId)
            .put(KEY_CAPABILITY, record.capability)
            .put(KEY_INTENT, record.intent.name)
            .put(KEY_GUEST_UID, record.guestUid)
            .put(KEY_PREPARED, record.prepared)
            .put(KEY_TARGET_UID, record.targetUid ?: JSONObject.NULL)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = keys.getOrCreateKey()
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val blob = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            prefs.edit {
                putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            }
        } finally {
            key.fill(0)
        }
    }

    override fun clear() {
        prefs.edit {
            remove(KEY_IV)
            remove(KEY_BLOB)
        }
    }

    private fun decrypt(ivB64: String, blobB64: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = keys.getOrCreateKey()
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            return cipher.doFinal(Base64.decode(blobB64, Base64.NO_WRAP))
        } finally {
            key.fill(0)
        }
    }

    private fun recordOf(json: JSONObject): GuestTransitionRecord {
        val intent = TransitionIntent.valueOf(json.getString(KEY_INTENT))
        return GuestTransitionRecord(
            transitionId = json.getString(KEY_TRANSITION_ID),
            capability = json.getString(KEY_CAPABILITY),
            intent = intent,
            guestUid = json.getString(KEY_GUEST_UID),
            prepared = json.getBoolean(KEY_PREPARED),
            targetUid = if (json.isNull(KEY_TARGET_UID)) null else json.getString(KEY_TARGET_UID),
        )
    }

    private companion object {
        const val TAG = "GuestTransitionJournal"
        const val KEK_ALIAS = "guest_transition_journal_kek"
        const val PREFS_NAME = "guest_transition_journal"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "record_iv"
        const val KEY_BLOB = "record_blob"
        const val KEY_TRANSITION_ID = "transitionId"
        const val KEY_CAPABILITY = "capability"
        const val KEY_INTENT = "intent"
        const val KEY_GUEST_UID = "guestUid"
        const val KEY_PREPARED = "prepared"
        const val KEY_TARGET_UID = "targetUid"
    }
}

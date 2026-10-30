package com.example.myexodus.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import androidx.core.content.edit
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * Shared Keystore-wrapped-DEK scheme for the SQLCipher stores (consolidated 2026-08-28
 * review: AndroidDatabaseKeyManager and ActivityLogKeyManager were ~95 duplicated lines
 * differing only in KEK alias + prefs name).
 *
 * The KEK lives in Android Keystore (AES-256-GCM, StrongBox opportunistically, TEE
 * fallback). The DEK is a random 256-bit key, wrapped by the KEK, stored in private
 * SharedPreferences. Never stored as a String or a static field; the returned [ByteArray]
 * must be zeroed (`Arrays.fill(key, 0)`) by the caller once keying completes.
 *
 * Note: net.zetetic sqlcipher-android 4.18.0 offers String and ByteArray password overloads
 * (no char[] overload — that was the deprecated android-database-sqlcipher API); the
 * ByteArray overload is used to avoid a String.
 */
class KeystoreWrappedDekManager(
    appContext: Context,
    private val kekAlias: String,
    private val prefsName: String,
) {
    // Lazy: this class is constructed with the Hilt graph at process start, which can be
    // pre-unlock (LOCKED_BOOT_COMPLETED, plan §8.5) — CE SharedPreferences are unavailable
    // and throw IllegalStateException until the user unlocks. Deferred to first key use,
    // which only happens on the (post-unlock, CE) Room open path anyway.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun getOrCreateKey(): ByteArray {
        val kek = getOrCreateKek()
        val iv = prefs.getString(KEY_DEK_IV, null)
        val blob = prefs.getString(KEY_DEK_BLOB, null)
        return if (iv != null && blob != null) {
            unwrapDek(kek, iv, blob)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { wrapAndStore(kek, it) }
        }
    }

    private fun wrapAndStore(kek: SecretKey, dek: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val wrapped = cipher.doFinal(dek)
        prefs.edit {
            putString(KEY_DEK_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_DEK_BLOB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        }
    }

    private fun unwrapDek(kek: SecretKey, ivB64: String, blobB64: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Keystore generates a fresh random IV per encryption; this is the IV stored with the blob.
        cipher.init(
            Cipher.DECRYPT_MODE,
            kek,
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(blobB64, Base64.NO_WRAP))
    }

    private fun getOrCreateKek(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(kekAlias, null) as? SecretKey)?.let { return it }
        return try {
            generateKek(strongBox = true)
        } catch (e: ProviderException) {
            // StrongBox unavailable on this device: fall back to TEE-backed Keystore.
            generateKek(strongBox = false)
        }
    }

    private fun generateKek(strongBox: Boolean): SecretKey {
        val specBuilder = KeyGenParameterSpec.Builder(
            kekAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setInvalidatedByBiometricEnrollment(false)
        // No fixed AlgorithmParameterSpec/IV: Keystore generates a fresh random IV per Cipher.init().
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(specBuilder.build())
        return generator.generateKey().also { logSecurityLevel(it) }
    }

    /**
     * Diagnostics only (plan §10: logged, not a gate). [KeyInfo.getSecurityLevel] is API 31+;
     * on older devices the level is unknown here and that is acceptable.
     */
    private fun logSecurityLevel(key: SecretKey) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val level = when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                else -> "Unknown(${info.securityLevel})"
            }
            Log.i(TAG, "KEK $kekAlias generated at security level: $level")
        }.onFailure { Log.w(TAG, "Keystore security-level probe failed", it) }
    }

    fun classifyFailure(e: Throwable): KeyFailure = when (e) {
        is SQLiteConstraintException -> KeyFailure.ApplicationValidation
        is SQLiteDatabaseCorruptException -> KeyFailure.ConfirmedCorruption
        is SQLiteNotADatabaseException -> KeyFailure.DatabaseIssue
        is GeneralSecurityException -> KeyFailure.KeyUnavailable // unwrap/Keystore failure
        is IOException -> KeyFailure.Transient
        is SQLiteException -> KeyFailure.Transient // busy, full, can't-open: retry with backoff
        else -> KeyFailure.DatabaseIssue
    }

    /**
     * Stores [dek] wrapped under this instance's KEK without generating a new DEK — the
     * UID-scope migration's adoption of the existing store key. The caller owns [dek].
     */
    fun adoptKey(dek: ByteArray) {
        wrapAndStore(getOrCreateKek(), dek)
    }

    /** True when a wrapped DEK is already stored (i.e. the key was created on a prior run). */
    fun hasWrappedKey(): Boolean =
        prefs.getString(KEY_DEK_IV, null) != null && prefs.getString(KEY_DEK_BLOB, null) != null

    /** Drops the wrapped DEK; a fresh key is generated on the next [getOrCreateKey]. */
    fun resetKeyMaterial() {
        prefs.edit { remove(KEY_DEK_IV); remove(KEY_DEK_BLOB) }
    }

    /**
     * Account plan §6.9.3: removes this instance's key material entirely — the wrapped DEK and
     * the KEK alias. Only for an alias owned exclusively by the deleted identity; the legacy
     * aliases shared by pre-scope stores use [resetKeyMaterial] instead.
     */
    fun deleteKeyMaterial() {
        resetKeyMaterial()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(kekAlias)
        }.onFailure { Log.w(TAG, "KEK alias deletion failed for $kekAlias", it) }
    }

    private companion object {
        const val TAG = "MyExodusDb"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_DEK_IV = "dek_iv"
        const val KEY_DEK_BLOB = "dek_blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

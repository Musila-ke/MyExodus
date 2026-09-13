package com.example.myexodus.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import androidx.core.content.edit
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
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
        return generator.generateKey()
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

    /** Drops the wrapped DEK; a fresh key is generated on the next [getOrCreateKey]. */
    fun resetKeyMaterial() {
        prefs.edit { remove(KEY_DEK_IV); remove(KEY_DEK_BLOB) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_DEK_IV = "dek_iv"
        const val KEY_DEK_BLOB = "dek_blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

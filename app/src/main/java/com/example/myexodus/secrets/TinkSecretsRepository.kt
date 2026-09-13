package com.example.myexodus.secrets

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Preferences-based [SecretsRepository] over a Tink-encrypted DataStore. Secret values are
 * stored base64-encoded (Preferences has no byte key type).
 *
 * [statusFlow] is shared with the DataStore's [androidx.datastore.core.CorruptionHandler]
 * (wired in DI): any undecryptable read — the Keystore-key-loss case, surfaced as
 * CorruptionException by datastore-tink 1.3.0-alpha10 — marks the vault CORRUPTED, the
 * store replaces the unrecoverable ciphertext with empty, and the user re-pairs /
 * re-codes (§9). A successful write clears the flag.
 */
class TinkSecretsRepository(
    private val dataStore: DataStore<Preferences>,
    private val statusFlow: MutableStateFlow<SecretsStatus>,
) : SecretsRepository {

    override val status: Flow<SecretsStatus> = statusFlow.asStateFlow()

    override val pairingKey: Flow<ByteArray?> =
        dataStore.data.map { decode(it[PAIRING_KEY]) }

    override val escapeCode: Flow<ByteArray?> =
        dataStore.data.map { decode(it[ESCAPE_CODE]) }

    override suspend fun storePairingKey(material: ByteArray) {
        dataStore.edit { it[PAIRING_KEY] = encode(material) }
        statusFlow.value = SecretsStatus.OK
    }

    override suspend fun storeEscapeCode(code: ByteArray) {
        dataStore.edit { it[ESCAPE_CODE] = encode(code) }
        statusFlow.value = SecretsStatus.OK
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray? = value?.let { Base64.decode(it, Base64.NO_WRAP) }

    companion object {
        val PAIRING_KEY = stringPreferencesKey("pairing_key")
        val ESCAPE_CODE = stringPreferencesKey("escape_code")
    }
}

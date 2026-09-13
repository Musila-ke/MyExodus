package com.example.myexodus.secrets

import kotlinx.coroutines.flow.Flow

/**
 * §8.6/§5 Decision 11 secrets vault: the small secrets set — pairing key material and the
 * escape/recovery code — behind one interface. No call site touches DataStore/Tink
 * directly, so the pinned alpha dependency can be swapped without touching business logic.
 *
 * Backed by DataStore + Tink AEAD (Android Keystore master key). When the Keystore key is
 * lost while encrypted data still exists (§9), the store self-heals to empty and
 * [status] reports [SecretsStatus.CORRUPTED] — the recovery UI re-pairs / re-codes
 * instead of the app crashing or locking the user out.
 */
interface SecretsRepository {

    /** OK normally; CORRUPTED after Keystore-key loss / undecryptable data (§9). */
    val status: Flow<SecretsStatus>

    /** Long-term partner credential from §10 pairing. Null when not paired. */
    val pairingKey: Flow<ByteArray?>

    /** The user's escape/recovery code. Null when not set. */
    val escapeCode: Flow<ByteArray?>

    suspend fun storePairingKey(material: ByteArray)

    suspend fun storeEscapeCode(code: ByteArray)
}

enum class SecretsStatus { OK, CORRUPTED }

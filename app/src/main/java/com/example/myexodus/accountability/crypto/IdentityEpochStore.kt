package com.example.myexodus.accountability.crypto

import com.example.myexodus.accountability.state.IdentityEpoch
import com.example.myexodus.accountability.state.IdentityEpochDao

/**
 * §7.6 identity generation counter. The envelope `epoch` field documents which identity
 * generation produced an event ("0 for the first identity key"); [IdentityMigration] bumps
 * it on rotation. Persisted in the chain database so envelopes stay truthful across
 * process death (Decision 12: no EncryptedSharedPreferences).
 */
class IdentityEpochStore(private val dao: IdentityEpochDao) {

    suspend fun current(): Int = dao.current() ?: 0

    /** Advances the generation; returns the new epoch. */
    suspend fun bump(): Int {
        val next = current() + 1
        dao.upsert(IdentityEpoch(epoch = next))
        return next
    }
}

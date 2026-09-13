package com.example.myexodus.accountability.state

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * §7.6 identity-generation counter, stored as protocol state in the chain database
 * (Decision 12: EncryptedSharedPreferences is not used anywhere; protocol state belongs
 * next to the enrollment records it describes). Single fixed row.
 */
@Entity(tableName = "identity_epoch")
data class IdentityEpoch(
    @PrimaryKey val id: Int = SINGLETON_ROW,
    val epoch: Int,
) {
    companion object {
        const val SINGLETON_ROW = 0
    }
}

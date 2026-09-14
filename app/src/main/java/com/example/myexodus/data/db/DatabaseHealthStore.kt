package com.example.myexodus.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing database state. RecoverableDataFound and EncryptionUpgradePending can coexist
 *  with a working database (the latter: still running on the plaintext store, plan §8 Step 0). */
sealed interface DatabaseHealth {
    data object Opening : DatabaseHealth
    data object Ready : DatabaseHealth
    data object RecoverableDataFound : DatabaseHealth
    data object EncryptionUpgradePending : DatabaseHealth
    data object DatabaseIssue : DatabaseHealth
    data object KeyUnavailable : DatabaseHealth
    data object Transient : DatabaseHealth
}

class DatabaseHealthStore {
    private val _health = MutableStateFlow<DatabaseHealth>(DatabaseHealth.Opening)
    val health: StateFlow<DatabaseHealth> = _health.asStateFlow()

    fun set(health: DatabaseHealth) {
        _health.value = health
    }
}

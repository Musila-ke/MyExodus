package com.example.myexodus.data.db

/**
 * Plan §8.3: protected-store access was refused because the app-lock gate is closed. Callers
 * with background work defer and retry (WorkManager backoff, §8.9); UI paths never see this
 * because the protected tree is not composed outside the gate.
 */
class VaultLockedException : IllegalStateException("Vault is locked")

package com.example.myexodus.applock

/**
 * The SQLCipher stores inside the v1 vault (inventory D1/D2). The plain accountability store
 * is operational and deliberately absent. File names are resolved in the data layer from the
 * live scope, never from these enum names.
 */
enum class VaultDatabase {
    RULES,
    ACTIVITY,
}

/** Canonical AAD database binding for the single vault envelope (§8.4). */
internal const val VAULT_DATABASE_ID = "myexodus_vault"

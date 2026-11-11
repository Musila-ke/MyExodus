package com.example.myexodus.applock

/**
 * Device seams for the wrap-only migration (plan §8.7 route 1, inventory D1). The legacy side
 * is the pre-feature Keystore-wrapped-DEK scheme in both its scope flavors; the vault side is
 * the authenticated envelope. Implemented in `data/db` so this package stays free of
 * SQLCipher/native details. Arrays returned by [unwrapLegacy] are owned by the caller and
 * must be zeroed once keying completes.
 */
interface VaultStoreKeys {

    /** Current DEK for [database] from the pre-feature Keystore wrap; creates one if absent. */
    fun unwrapLegacy(database: VaultDatabase): ByteArray

    /** Re-wraps [dek] under the pre-feature Keystore wrap (disable / scope adoption). */
    fun adoptLegacy(database: VaultDatabase, dek: ByteArray)

    /** Removes the pre-feature KEK and wrapped DEK; idempotent, rollback-only material. */
    fun deleteLegacyMaterial(database: VaultDatabase)

    /** Opens [database] with [dek] and reads a value; an absent file is not an error and no
     *  file is ever created. Wrong key → false; unreadable/other failures throw. */
    fun verify(database: VaultDatabase, dek: ByteArray): Boolean
}

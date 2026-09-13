package com.example.myexodus.data.db

/** Classification of a database/key failure, per the plan's RecoveryManager table. */
sealed class KeyFailure {
    /** SQLiteConstraintException (Rule 4 / uniqueness): caller's error, files untouched. */
    data object ApplicationValidation : KeyFailure()

    /** Not-a-database or version mismatch: preserve files, offer manual reset. */
    data object DatabaseIssue : KeyFailure()

    /** DEK cannot be unwrapped (Keystore key lost/invalidated, wrapped DEK missing/corrupt). */
    data object KeyUnavailable : KeyFailure()

    /** Confirmed corruption: rename to .db.corrupt, create a fresh database. */
    data object ConfirmedCorruption : KeyFailure()

    /** I/O, storage-full, or busy: retry with backoff. */
    data object Transient : KeyFailure()
}

interface DatabaseKeyManager {
    fun getOrCreateDek(): ByteArray

    fun classifyFailure(e: Throwable): KeyFailure

    /** Used only by RecoveryManager after user confirmation. */
    fun resetKeyMaterial()
}

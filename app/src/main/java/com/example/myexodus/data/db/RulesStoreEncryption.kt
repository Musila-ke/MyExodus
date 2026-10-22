package com.example.myexodus.data.db

import android.util.Log
import androidx.sqlite.SQLiteDriver
import java.io.File

/** Result of the pre-open encryption preparation. */
enum class ConversionOutcome {
    /** The encrypted store should be opened (fresh install, already converted, or just converted). */
    Encrypted,

    /** Conversion failed: keep running on the plaintext store and retry on the next launch. */
    Pending,
}

/**
 * Encryption seam for [AppRulesStoreFactory] (habit-streaks plan §8 Steps 0/1). Owns the
 * cross-launch state machine around the one-time conversion — when a `.bak` may be confirmed
 * and deleted, when the plaintext store must be restored — plus the cipher driver factory.
 * A `null` instance of this class in the factory means "run plaintext" (unit tests).
 */
class RulesStoreEncryption(
    private val keyManager: RulesStoreKeyManager,
    private val recoveryManager: RecoveryManager,
) {
    private val migration = PlaintextToEncryptedMigration(keyManager, recoveryManager)
    private var backupPresentAtStart = false

    fun newDriver(): SQLiteDriver = KeyFetchingSQLCipherDriver { keyManager.getOrCreateDek() }

    fun classifyFailure(e: Throwable): KeyFailure = keyManager.classifyFailure(e)

    /**
     * Decides and, when needed, runs the plaintext→encrypted conversion. [migrateSource]
     * brings a plaintext file older than the current schema up to date before it is copied
     * (the conversion's raw copy cannot run superseded Room migrations).
     */
    fun prepareForOpen(
        migrateSource: () -> Unit = {},
        buildDestination: (File) -> Unit,
    ): ConversionOutcome {
        backupPresentAtStart = recoveryManager.hasConversionBackup()
        val rules = recoveryManager.rulesDbFile

        // Crash window of the delete+rename fallback in RecoveryManager.swapInEncryptedRules:
        // the main file is gone but the plaintext backup exists — it is the only copy.
        if (!rules.exists() && backupPresentAtStart) {
            Log.w(TAG, "Rules database missing with a conversion backup present; restoring")
            recoveryManager.restorePlaintextFromBackup()
            backupPresentAtStart = false
        }

        return when {
            // Fresh install: the encrypted store is created directly (plan §8 Step 0).
            !rules.exists() -> ConversionOutcome.Encrypted
            migration.isPlaintextDatabase(rules) ->
                if (migration.run(buildDestination, migrateSource)) ConversionOutcome.Encrypted
                else ConversionOutcome.Pending
            else -> ConversionOutcome.Encrypted // already encrypted
        }
    }

    /** Called once the encrypted store has opened and answered a query. */
    fun onOpenedSuccessfully() {
        // Two-launch confirmation (plan §8 Step 0): only a launch that started with the backup
        // already on disk may delete it — on the conversion launch it was created mid-launch.
        if (backupPresentAtStart) recoveryManager.confirmConversionBackup()
    }

    /** Encrypted open failed: put the untouched plaintext store back, if a backup remains. */
    fun restorePlaintextFallback(): Boolean = recoveryManager.restorePlaintextFromBackup()

    private companion object {
        const val TAG = "MyExodusMigration"
    }
}

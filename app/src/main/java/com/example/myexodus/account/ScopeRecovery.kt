package com.example.myexodus.account

import android.content.Context
import android.util.Log
import androidx.room3.withWriteTransaction
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.blocking.RulesSnapshot
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.KeystoreWrappedDekManager
import com.example.myexodus.data.db.RulesStoreKeyManager
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.domainusage.RuleUsageDaily
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why a recovery import could not be staged; each maps to one user-facing line. */
enum class RecoveryFailure { UNREADABLE, STORE_UNAVAILABLE }

sealed interface RecoveryStage {
    data object Staged : RecoveryStage
    data object NotOffered : RecoveryStage
    data class Failed(val failure: RecoveryFailure) : RecoveryStage
}

/** ADR #240: what a same-device restore carried (null from [ScopeRecoveryImporter.restoreLocalData] = failed). */
data class LocalRestore(
    val rulesInserted: Int,
    val rulesDeduplicated: Int,
    val rulesCapped: Int,
    val usageRows: Int,
)

/**
 * ADR #240: everything a same-device recovery carries besides the imported logical rows.
 * These never enter the portable quarantine (owner ruling 2026-10-29, identity doc G5) — they
 * only move between two scopes of the same encrypted store on the same phone.
 */
data class RetainedLocalData(
    val rules: List<RestrictionRule>,
    val trackers: List<DailyUsageTracker>,
    val ledger: List<UsageLedger>,
    val ruleUsage: List<RuleUsageDaily>,
)

/** Reads a retained scope's logical data; faked in JVM tests, Keystore-backed in production. */
interface ScopeSnapshotReader {
    suspend fun snapshotOf(scopeHash: String): GuestSnapshot?

    /** ADR #240: the retained block rules and usage — the owner's data, restored on the same device. */
    suspend fun localDataOf(scopeHash: String): RetainedLocalData?
}

/**
 * ADR #239: reads a retained scope without touching it. The rules file (plus its WAL) is byte
 * copied next to it, opened with that scope's own Keystore DEK, read, and the copy deleted.
 * The original is never opened, rekeyed or checkpointed — it stays the rollback copy.
 */
@Singleton
class KeystoreScopeSnapshotReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storeFactory: AppRulesStoreFactory,
) : ScopeSnapshotReader {

    override suspend fun snapshotOf(scopeHash: String): GuestSnapshot? =
        readCopy(scopeHash) { db -> readGuestSnapshot(db) }

    override suspend fun localDataOf(scopeHash: String): RetainedLocalData? =
        readCopy(scopeHash) { db ->
            RetainedLocalData(
                rules = db.restrictionRuleDao().getActiveRules(),
                trackers = db.dailyUsageTrackerDao().all(),
                ledger = db.usageLedgerDao().all(),
                ruleUsage = db.ruleUsageDao().all(),
            )
        }

    private suspend fun <T> readCopy(scopeHash: String, read: suspend (AppRulesStore) -> T): T? {
        // A prefs value must never become a path component unchecked.
        if (!SCOPE_HASH.matches(scopeHash)) return null
        val dir = context.getDatabasePath("x").parentFile ?: return null
        val source = File(dir, "myexodus_rules_$scopeHash.db")
        if (!source.exists()) return null
        val keys = KeystoreWrappedDekManager(
            context,
            RulesStoreKeyManager.LEGACY_KEK_ALIAS + "_$scopeHash",
            RulesStoreKeyManager.LEGACY_PREFS + "_$scopeHash",
        )
        // Never mint a key for a scope whose key is gone: the open would only fail, and the
        // retained file must stay exactly as it is for a future device-level recovery.
        if (!keys.hasWrappedKey()) return null
        val staging = File(dir, "myexodus_rules_recovery_$scopeHash.db")
        deleteWithSidecars(staging)
        return try {
            copy(source, staging)
            File(source.path + "-wal").takeIf { it.exists() && it.length() > 0 }?.let { wal ->
                copy(wal, File(staging.path + "-wal"))
            }
            val db = storeFactory.openRecoveryCopy(staging.name) { keys.getOrCreateKey() }
            try {
                read(db)
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            // Class name only: no data, no keys in logs.
            Log.w(TAG, "retained scope read failed (${e.javaClass.simpleName})")
            null
        } finally {
            deleteWithSidecars(staging)
        }
    }

    private fun copy(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun deleteWithSidecars(file: File) {
        // Room's process lock is `<name>.lck` (dot), not `-lck` — device-proven 2026-10-30.
        listOf("", "-wal", "-shm", "-journal", ".lck").forEach { File(file.path + it).delete() }
    }

    private companion object {
        const val TAG = "ScopeRecovery"
        val SCOPE_HASH = Regex("[0-9a-f]{32}")
    }
}

/**
 * ADR #239: the one-time import of a retained scope into the signed-in account. It stages the
 * snapshot in the same encrypted quarantine the guest transition uses, so the dry run, the
 * additive natural-key plan, the atomic apply and the seven-day undo are all the existing
 * [GuestImportCoordinator] path — this class only finds the source and binds the target.
 */
class ScopeRecoveryImporter(
    private val auth: AuthCoordinator,
    private val reader: ScopeSnapshotReader,
    private val quarantine: GuestQuarantine,
    private val recoveryOffer: () -> String?,
    private val storeFactory: AppRulesStoreFactory,
    private val rules: RulesRepository,
    /** ADR #240: only an account-bound offer may import without the user staging it. */
    private val boundOffer: () -> String? = recoveryOffer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True while a staged but unimported recovery copy exists for the current account. */
    suspend fun pendingStaged(): Boolean {
        val target = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return false
        return quarantine.forTarget(target).records.any { isRecoveryRecord(it) && it.importedAtMs == null }
    }

    /**
     * ADR #240: stages a bound offer at process start so the coordinator can import it with no
     * tap. A guest sign-out's unbound copy keeps the explicit card — no account owned it.
     */
    suspend fun stageIfOffered(): Boolean {
        if (boundOffer() == null) return false
        if (pendingStaged()) return false
        return stageForImport() == RecoveryStage.Staged
    }

    suspend fun stageForImport(): RecoveryStage {
        val target = (auth.durableState as? AuthState.PersistentActive)?.uid
            ?: return RecoveryStage.Failed(RecoveryFailure.STORE_UNAVAILABLE)
        val hash = recoveryOffer() ?: return RecoveryStage.NotOffered
        val snapshot = reader.snapshotOf(hash) ?: return RecoveryStage.Failed(RecoveryFailure.UNREADABLE)
        val saved = quarantine.save(
            GuestQuarantineRecord(
                transitionId = "recovery-$hash",
                guestUid = RECOVERY_UID_PREFIX + hash,
                importOperationId = "recovery-$hash",
                intent = TransitionIntent.IMPORT,
                stagedAtMs = clock(),
                targetUid = target,
                snapshot = snapshot,
            ),
        )
        return if (saved) RecoveryStage.Staged else RecoveryStage.Failed(RecoveryFailure.STORE_UNAVAILABLE)
    }

    /**
     * ADR #240 (owner report 2026-10-30: "where are my blocks? … everything"): the owner's
     * block rules and usage counters are their data too, and a same-device recovery restores
     * them into the account. The 2026-10-29 portability exclusion still governs backups and
     * the portable quarantine — none of this crosses either; it only moves between scopes of
     * the same encrypted store on the same phone. Partner state is deliberately absent: the
     * transition terminates every pairing server-side (guest-transition finalize), so there is
     * no live relationship to restore into the new identity.
     */
    suspend fun restoreLocalData(scopeHash: String): LocalRestore? {
        if ((auth.durableState as? AuthState.PersistentActive)?.uid == null) return null
        val retained = reader.localDataOf(scopeHash) ?: return null
        val db = storeFactory.open() ?: return null
        val profileId = rules.ensureActiveProfile().userId
        return try {
            db.withWriteTransaction { restoreInto(db, profileId, retained) }
        } catch (e: Exception) {
            Log.w(TAG, "retained local-data restore failed (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun restoreInto(
        db: AppRulesStore,
        profileId: String,
        retained: RetainedLocalData,
    ): LocalRestore {
        val dao = db.restrictionRuleDao()
        val existing = dao.getActiveRules()
        val takenPackages = existing.map { it.blockedPackage }.toMutableSet()
        val takenIds = existing.map { it.ruleId }.toMutableSet()
        val counts = existing.groupingBy { it.targetType }.eachCount().toMutableMap()
        var inserted = 0
        var deduplicated = 0
        var capped = 0
        for (rule in retained.rules.sortedBy { it.ruleId }) {
            if (!takenPackages.add(rule.blockedPackage)) {
                deduplicated++
                continue
            }
            val cap = if (rule.targetType == "APP") {
                RulesSnapshot.MAX_APP_RULES
            } else {
                RulesSnapshot.MAX_URL_RULES
            }
            if ((counts[rule.targetType] ?: 0) >= cap) {
                capped++
                continue
            }
            val id = if (rule.ruleId !in takenIds) rule.ruleId else newRuleId()
            takenIds += id
            counts[rule.targetType] = (counts[rule.targetType] ?: 0) + 1
            dao.insert(
                rule.copy(
                    ruleId = id,
                    profileId = profileId,
                    // A guest's pairs were terminated by the same transition.
                    assignedPairId = null,
                    backupPairId = null,
                    streakPairId = null,
                ),
            )
            inserted++
        }

        val trackers = db.dailyUsageTrackerDao()
        val takenTrackerKeys = trackers.getForProfile(profileId)
            .map { it.packageName to it.localDateAnchor }
            .toMutableSet()
        var usageRows = 0
        for (tracker in retained.trackers) {
            if (!takenTrackerKeys.add(tracker.packageName to tracker.localDateAnchor)) continue
            trackers.insert(
                tracker.copy(trackerId = newRuleId(), profileId = profileId),
            )
            usageRows++
        }
        for (row in retained.ledger) {
            // IGNORE on the unique event id: a replay of this restore adds nothing.
            if (db.usageLedgerDao().insert(row.copy(id = 0)) != -1L) usageRows++
        }
        // MAX semantics: a replay re-sends the same totals and never lowers one.
        db.ruleUsageDao().restoreMax(retained.ruleUsage)
        return LocalRestore(inserted, deduplicated, capped, usageRows)
    }

    private fun newRuleId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "ScopeRecovery"

        /** Not a real UID (Firebase UIDs are alphanumeric), so a staged recovery can never
         *  collide with a guest transition's record. */
        const val RECOVERY_UID_PREFIX = "scope-"

        fun isRecoveryRecord(record: GuestQuarantineRecord): Boolean =
            record.guestUid.startsWith(RECOVERY_UID_PREFIX)
    }
}

package com.example.myexodus.account

import android.util.Log
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.example.myexodus.backup.ImportCloudGuard
import com.example.myexodus.backup.ImportCloudVerdict
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Why the import could not run; each maps to one user-facing recovery line (§8.4, §9). */
enum class ImportFailure {
    /** A staged copy exists but cannot be opened — preserved, never deleted (§9). */
    UNREADABLE,
    STORE_UNAVAILABLE,
    IMPORT_FAILED,

    /** §6.4 item 8: rollback/equivocation against the target's high-water; merge is not offered. */
    CLOUD_CONFLICT,

    /** The target's cloud pointer could not be read; nothing is imported on an unverified base. */
    CLOUD_UNAVAILABLE,
}

/** The Account pane's view of the post-switch import (plan §8.4). */
sealed interface GuestImportState {
    data object Idle : GuestImportState

    /** Dry run complete: exact counts before anything is written; the user confirms. */
    data class Confirming(
        val summary: ImportSummary,
        val conflicts: List<ImportConflict>,
        val purgeAtMs: Long,
        val portablePreferences: Boolean,
        /** §6.3.5: the opt-in only exists when the two recovery policies actually differ. */
        val portablePreferencesAvailable: Boolean,
    ) : GuestImportState

    data object Importing : GuestImportState
    data class Imported(val summary: ImportSummary, val purgeAtMs: Long) : GuestImportState
    data class Failed(val failure: ImportFailure, val summary: ImportSummary?) : GuestImportState

    /** §9/§12.4: IMPORT_PENDING — the target's cloud snapshot must be restored before importing. */
    data class PendingRestore(val purgeAtMs: Long, val remoteRevision: Long) : GuestImportState

    /** §6.4 item 8: this phone holds data the newer cloud snapshot does not; the user chooses. */
    data class BaseConflict(val purgeAtMs: Long, val remoteRevision: Long) : GuestImportState
}

/**
 * Account plan §8.4/§6.3: imports the quarantined guest data after the target account is
 * signed in and the transition is finalized. The preview the user approves is a dry run; the
 * plan actually applied is recomputed inside the target database's write transaction, so a
 * changed target can only re-surface conflicts — never a partial or duplicated import. On any
 * failure the transaction rolls back, the quarantine stays, and `Retry import` replays the
 * same operation idempotently (every natural key already carrying the row deduplicates).
 *
 * Runs only in the target session: the quarantine is bound to a target UID by finalization.
 */
class GuestImportCoordinator(
    private val auth: AuthCoordinator,
    private val transition: GuestTransitionCoordinator,
    private val storeFactory: AppRulesStoreFactory,
    private val quarantine: GuestQuarantine,
    private val rules: RulesRepository,
    private val planner: GuestImportPlanner,
    private val cloudGuard: ImportCloudGuard,
    private val clock: () -> Long = System::currentTimeMillis,
    /** ADR #239: lets a one-time recovery import retire its offer exactly when the rows commit. */
    private val onImported: suspend (GuestQuarantineRecord) -> Unit = {},
) {
    private val _state = MutableStateFlow<GuestImportState>(GuestImportState.Idle)
    val state: StateFlow<GuestImportState> = _state.asStateFlow()

    private val gate = Mutex()
    private var pending: GuestQuarantineRecord? = null
    private var portablePreferences = false
    private var portablePreferencesAvailable = false

    /** §6.4 item 8(b): the user chose to merge here while the cloud snapshot stays untouched. */
    private var mergeHereConfirmed = false

    /**
     * Wires the app-level triggers: a target session may become active before finalization
     * tags the quarantine, and finalization may complete while the session already exists —
     * both orders end in one refresh after the tag is durable.
     */
    fun attach(scope: CoroutineScope) {
        scope.launch {
            auth.state.collect { current ->
                if (current is AuthState.PersistentActive) refresh()
            }
        }
        scope.launch {
            transition.state.collect { current ->
                if (current is GuestTransitionState.Completed &&
                    current.intent == TransitionIntent.IMPORT
                ) {
                    refresh()
                }
            }
        }
    }

    /** Sweeps expired copies and re-runs the dry run against the current target (§8.4). */
    suspend fun refresh() = gate.withLock { refreshLocked() }

    /** The portable-preferences checkbox: recomputes the counts for the chosen opt-in. */
    suspend fun preview(includePortablePreferences: Boolean) = gate.withLock {
        val record = pending
        if (record != null) {
            dryRun(record, includePortablePreferences)?.let { showConfirming(record, it) }
        } else {
            refreshLocked()
        }
    }

    /** The user confirmed a plan the dry run could not apply on its own: apply atomically. */
    suspend fun confirm() = gate.withLock {
        pending?.let { applyLocked(it) }
    }

    private suspend fun applyLocked(record: GuestQuarantineRecord) {
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return
        if (record.targetUid != targetUid) return
        val db = storeFactory.open()
        if (db == null) {
            _state.value = GuestImportState.Failed(ImportFailure.STORE_UNAVAILABLE, null)
            return
        }
        if (!allowImport(targetUid, record, db)) return
        _state.value = GuestImportState.Importing
        val profileId = rules.ensureActiveProfile().userId
        val plan = try {
            db.withWriteTransaction<ImportPlan> {
                val fresh = planner.plan(
                    record.snapshot,
                    readTargetSnapshot(db, profileId),
                    portablePreferences,
                )
                if (!fresh.canApply) throw ImportConflicted(fresh)
                apply(fresh, db)
                fresh
            }
        } catch (e: ImportConflicted) {
            showConfirming(record, e.plan)
            return
        } catch (e: Exception) {
            Log.w(TAG, "guest import failed (${e.javaClass.simpleName})")
            _state.value = GuestImportState.Failed(ImportFailure.IMPORT_FAILED, null)
            return
        }
        val done = record.copy(
            targetUid = targetUid,
            importedAtMs = clock(),
            summary = plan.summary,
        )
        if (!quarantine.save(done)) {
            // Rows committed but the journal did not record it; the replay deduplicates every
            // natural key, so Retry re-runs a zero-insert dry run and records the outcome.
            _state.value = GuestImportState.Failed(ImportFailure.IMPORT_FAILED, plan.summary)
            return
        }
        onImported(done)
        pending = null
        mergeHereConfirmed = false
        // A preserved local snapshot imports last so the card reports the guest rows.
        val next = quarantine.forTarget(targetUid).records
            .filter { it.importedAtMs == null }
            .sortedByDescending { it.guestUid.startsWith(LOCAL_BASE_UID_PREFIX) }
            .firstOrNull()
        if (next != null) {
            presentOrApply(next)
        } else {
            // The card always reports the guest rows, even when a preserved local copy merged last.
            val imported = quarantine.forTarget(targetUid).records
                .filter { it.importedAtMs != null }
                .sortedBy { it.guestUid.startsWith(LOCAL_BASE_UID_PREFIX) }
                .firstOrNull()
            _state.value = GuestImportState.Imported(
                imported?.summary ?: plan.summary,
                imported?.purgeAtMs ?: record.purgeAtMs,
            )
        }
    }

    /**
     * §6.4 item 8(a): keep a consistent copy of this phone's rows before the cloud snapshot
     * replaces them, so the post-restore import can merge both additively.
     */
    suspend fun preserveLocalForRestore() = gate.withLock {
        val record = pending ?: return@withLock
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return@withLock
        if (record.targetUid != targetUid) return@withLock
        val db = storeFactory.open() ?: return@withLock
        val markerUid = LOCAL_BASE_UID_PREFIX + targetUid
        if (quarantine.forTarget(targetUid).records.any { it.guestUid == markerUid }) return@withLock
        val snapshot: GuestSnapshot = try {
            db.withReadTransaction { readGuestSnapshot(db) }
        } catch (e: Exception) {
            Log.w(TAG, "local snapshot capture failed (${e.javaClass.simpleName})")
            return@withLock
        }
        quarantine.save(
            GuestQuarantineRecord(
                transitionId = UUID.randomUUID().toString(),
                guestUid = markerUid,
                importOperationId = UUID.randomUUID().toString(),
                intent = TransitionIntent.IMPORT,
                stagedAtMs = clock(),
                targetUid = targetUid,
                snapshot = snapshot,
            ),
        )
    }

    /** §6.4 item 8(b): merge the guest rows here; the cloud snapshot and its pointer stay as they are. */
    suspend fun mergeIntoThisDeviceOnly() = gate.withLock {
        val record = pending ?: return@withLock
        mergeHereConfirmed = true
        // The cloud snapshot stays as it is, so a copy preserved for option (a) has no purpose.
        val boundUid = record.targetUid ?: return@withLock
        val markerUid = LOCAL_BASE_UID_PREFIX + boundUid
        quarantine.forTarget(boundUid).records
            .filter { it.guestUid == markerUid }
            .forEach { quarantine.deleteFor(it.guestUid, it.transitionId) }
        val plan = dryRun(record, portablePreferences) ?: return@withLock
        if (plan.canApply && !portablePreferencesAvailable) {
            applyLocked(record)
        } else {
            showConfirming(record, plan)
        }
    }

    /**
     * §6.4 item 8: never merge into a target database that is not the current cloud
     * snapshot. A newer snapshot with no local rows means restore first; with local rows the
     * user chooses between restoring and merging here. Rollback/equivocation always stop.
     */
    private suspend fun allowImport(
        targetUid: String,
        record: GuestQuarantineRecord,
        db: AppRulesStore,
    ): Boolean {
        when (val verdict = cloudGuard.check(targetUid)) {
            ImportCloudVerdict.NoCloudSnapshot, ImportCloudVerdict.Materialized -> return true
            is ImportCloudVerdict.RestoreRequired -> {
                pending = record
                if (mergeHereConfirmed) return true
                _state.value = if (hasLocalLogicalData(db)) {
                    GuestImportState.BaseConflict(record.purgeAtMs, verdict.remote.revision)
                } else {
                    GuestImportState.PendingRestore(record.purgeAtMs, verdict.remote.revision)
                }
            }
            is ImportCloudVerdict.HardStop -> {
                pending = record
                mergeHereConfirmed = false
                _state.value = GuestImportState.Failed(ImportFailure.CLOUD_CONFLICT, null)
            }
            ImportCloudVerdict.Unavailable -> {
                pending = record
                _state.value = GuestImportState.Failed(ImportFailure.CLOUD_UNAVAILABLE, null)
            }
        }
        return false
    }

    /** Habits are the anchor table; the rest are checked so an activity-only import still counts. */
    private suspend fun hasLocalLogicalData(db: AppRulesStore): Boolean {
        val profile = db.userProfileDao().getActive() ?: return false
        val target = readTargetSnapshot(db, profile.userId)
        return target.habits.isNotEmpty() || target.streaks.isNotEmpty() ||
            target.milestones.isNotEmpty() || target.attempts.isNotEmpty() ||
            target.sessions.isNotEmpty() || target.coaching.isNotEmpty() ||
            target.riskWindows.isNotEmpty()
    }

    /**
     * ADR #240 (owner ruling 2026-10-30): an import the user already asked for (the transition's
     * keep-my-data choice, or a bound recovery copy) lands without a second confirmation tap.
     * The card remains exactly where a human decision is required: conflicts, and the portable
     * preferences opt-in, whose default must not be chosen silently.
     */
    private suspend fun presentOrApply(record: GuestQuarantineRecord) {
        val plan = dryRun(record, portablePreferences) ?: return
        if (plan.canApply && !portablePreferencesAvailable) applyLocked(record) else showConfirming(record, plan)
    }

    /** §8.4 `Delete quarantine now`: this account's staged copies, permanently. */
    suspend fun deleteQuarantine() = gate.withLock {
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return@withLock
        quarantine.forTarget(targetUid).records.forEach {
            quarantine.deleteFor(it.guestUid, it.transitionId)
        }
        pending = null
        _state.value = GuestImportState.Idle
    }

    /** Hides a terminal card; the quarantine itself is untouched. */
    fun dismiss() {
        if (_state.value !is GuestImportState.Importing && _state.value !is GuestImportState.Confirming) {
            _state.value = GuestImportState.Idle
        }
    }

    private suspend fun refreshLocked() {
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return
        quarantine.sweepExpired(clock())
        val scan = quarantine.forTarget(targetUid)
        // A preserved local snapshot never drives the conflict card; its guest record does.
        val next = scan.records
            .filter { it.importedAtMs == null }
            .sortedBy { it.guestUid.startsWith(LOCAL_BASE_UID_PREFIX) }
            .firstOrNull()
        if (next != null) {
            presentOrApply(next)
            return
        }
        val imported = scan.records
            .filter { it.importedAtMs != null }
            .sortedBy { it.guestUid.startsWith(LOCAL_BASE_UID_PREFIX) }
            .firstOrNull()
        pending = null
        _state.value = when {
            imported != null -> GuestImportState.Imported(
                imported.summary ?: ImportSummary(0, 0, 0),
                imported.purgeAtMs,
            )
            scan.unreadable > 0 -> GuestImportState.Failed(ImportFailure.UNREADABLE, null)
            else -> GuestImportState.Idle
        }
    }

    private suspend fun dryRun(
        record: GuestQuarantineRecord,
        includePortablePreferences: Boolean,
    ): ImportPlan? {
        pending = record
        portablePreferences = includePortablePreferences
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return null
        if (record.targetUid != targetUid) return null
        val db = storeFactory.open()
        if (db == null) {
            _state.value = GuestImportState.Failed(ImportFailure.STORE_UNAVAILABLE, null)
            return null
        }
        if (!allowImport(targetUid, record, db)) return null
        return try {
            val profileId = rules.ensureActiveProfile().userId
            val target = readTargetSnapshot(db, profileId)
            val plan = planner.plan(record.snapshot, target, includePortablePreferences)
            portablePreferencesAvailable =
                record.snapshot.policy != null && record.snapshot.policy != target.policy
            plan
        } catch (e: Exception) {
            Log.w(TAG, "guest import dry run failed (${e.javaClass.simpleName})")
            _state.value = GuestImportState.Failed(ImportFailure.IMPORT_FAILED, null)
            null
        }
    }

    private fun showConfirming(record: GuestQuarantineRecord, plan: ImportPlan) {
        _state.value = GuestImportState.Confirming(
            summary = plan.summary,
            conflicts = plan.conflicts,
            purgeAtMs = record.purgeAtMs,
            portablePreferences = portablePreferences,
            portablePreferencesAvailable = portablePreferencesAvailable,
        )
    }

    private suspend fun apply(plan: ImportPlan, db: AppRulesStore) {
        plan.riskWindowsToInsert.forEach { db.riskWindowDao().insert(it) }
        plan.coachingToInsert.forEach { db.coachingStepDao().insert(it) }
        plan.habitsToInsert.forEach { db.habitProgressDao().insert(it) }
        plan.habitsToUpdate.forEach { db.habitProgressDao().update(it) }
        // Streaks precede milestones: the milestone rows carry a foreign key to them.
        plan.streaksToInsert.forEach { db.recoveryStreakDao().insert(it) }
        if (plan.milestonesToInsert.isNotEmpty()) {
            db.milestoneEventDao().insertAll(plan.milestonesToInsert)
        }
        plan.milestonesToUpdate.forEach { db.milestoneEventDao().update(it) }
        plan.attemptsToInsert.forEach { db.attemptLogDao().insert(it) }
        plan.sessionsToInsert.forEach { db.recoverySessionDao().insert(it) }
        plan.policy?.let { db.recoveryPolicyDao().put(it) }
    }

    private class ImportConflicted(val plan: ImportPlan) : Exception()

    private companion object {
        const val TAG = "GuestImport"

        /** §6.4 item 8(a): the preserved local copy, distinguishable from a guest record. */
        const val LOCAL_BASE_UID_PREFIX = "localbase-"
    }
}

package com.example.myexodus.account

import android.util.Log
import androidx.room3.withWriteTransaction
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

/** Why the import could not run; each maps to one user-facing recovery line (§8.4, §9). */
enum class ImportFailure {
    /** A staged copy exists but cannot be opened — preserved, never deleted (§9). */
    UNREADABLE,
    STORE_UNAVAILABLE,
    IMPORT_FAILED,
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<GuestImportState>(GuestImportState.Idle)
    val state: StateFlow<GuestImportState> = _state.asStateFlow()

    private val gate = Mutex()
    private var pending: GuestQuarantineRecord? = null
    private var portablePreferences = false
    private var portablePreferencesAvailable = false

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
        if (record != null) showDryRun(record, includePortablePreferences) else refreshLocked()
    }

    /** The user confirmed the dry run: apply atomically, then journal the outcome. */
    suspend fun confirm() = gate.withLock {
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return@withLock
        val record = pending ?: return@withLock
        if (record.targetUid != targetUid) return@withLock
        _state.value = GuestImportState.Importing
        val db = storeFactory.open()
        if (db == null) {
            _state.value = GuestImportState.Failed(ImportFailure.STORE_UNAVAILABLE, null)
            return@withLock
        }
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
            _state.value = GuestImportState.Confirming(
                summary = e.plan.summary,
                conflicts = e.plan.conflicts,
                purgeAtMs = record.purgeAtMs,
                portablePreferences = portablePreferences,
                portablePreferencesAvailable = portablePreferencesAvailable,
            )
            return@withLock
        } catch (e: Exception) {
            Log.w(TAG, "guest import failed (${e.javaClass.simpleName})")
            _state.value = GuestImportState.Failed(ImportFailure.IMPORT_FAILED, null)
            return@withLock
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
            return@withLock
        }
        pending = null
        val next = quarantine.forTarget(targetUid).records.firstOrNull { it.importedAtMs == null }
        if (next != null) {
            showDryRun(next, portablePreferences)
        } else {
            _state.value = GuestImportState.Imported(plan.summary, record.purgeAtMs)
        }
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
        if (_state.value is GuestImportState.Imported || _state.value is GuestImportState.Failed) {
            _state.value = GuestImportState.Idle
        }
    }

    private suspend fun refreshLocked() {
        val targetUid = (auth.durableState as? AuthState.PersistentActive)?.uid ?: return
        quarantine.sweepExpired(clock())
        val scan = quarantine.forTarget(targetUid)
        val next = scan.records.firstOrNull { it.importedAtMs == null }
        if (next != null) {
            showDryRun(next, portablePreferences)
            return
        }
        val imported = scan.records.firstOrNull { it.importedAtMs != null }
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

    private suspend fun showDryRun(
        record: GuestQuarantineRecord,
        includePortablePreferences: Boolean,
    ) {
        pending = record
        portablePreferences = includePortablePreferences
        val db = storeFactory.open()
        if (db == null) {
            _state.value = GuestImportState.Failed(ImportFailure.STORE_UNAVAILABLE, null)
            return
        }
        try {
            val profileId = rules.ensureActiveProfile().userId
            val target = readTargetSnapshot(db, profileId)
            val plan = planner.plan(record.snapshot, target, includePortablePreferences)
            portablePreferencesAvailable =
                record.snapshot.policy != null && record.snapshot.policy != target.policy
            _state.value = GuestImportState.Confirming(
                summary = plan.summary,
                conflicts = plan.conflicts,
                purgeAtMs = record.purgeAtMs,
                portablePreferences = includePortablePreferences,
                portablePreferencesAvailable = portablePreferencesAvailable,
            )
        } catch (e: Exception) {
            Log.w(TAG, "guest import dry run failed (${e.javaClass.simpleName})")
            _state.value = GuestImportState.Failed(ImportFailure.IMPORT_FAILED, null)
        }
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
    }
}

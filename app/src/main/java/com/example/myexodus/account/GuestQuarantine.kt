package com.example.myexodus.account

import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow

/** Plan §8.4: the disclosed window after which the encrypted staged copy is swept. */
const val QUARANTINE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Account plan §6.3 / identity doc G5: the guest logical data that may be imported. Blocks,
 * usage, pairing keys and the profile row are deliberately absent — they are not portable
 * (identity doc G5 owner ruling) and must never appear in a quarantine.
 */
data class GuestSnapshot(
    val habits: List<HabitProgress>,
    val streaks: List<RecoveryStreak>,
    val milestones: List<MilestoneEvent>,
    val attempts: List<AttemptLog>,
    val sessions: List<RecoverySession>,
    val coaching: List<CoachingStep>,
    val riskWindows: List<RiskWindow>,
    val policy: RecoveryPolicy?,
)

/** §6.4 item 12: an empty payload is never uploaded and a restore never activates one. */
fun GuestSnapshot.isEmpty(): Boolean =
    habits.isEmpty() && streaks.isEmpty() && milestones.isEmpty() && attempts.isEmpty() &&
        sessions.isEmpty() && coaching.isEmpty() && riskWindows.isEmpty() && policy == null

/** Dry-run and result counts (plan §8.4: inserted, deduplicated and conflicting). */
data class ImportSummary(
    val inserted: Int,
    val updated: Int,
    val deduplicated: Int,
    val conflicts: Int = 0,
)

/**
 * Account plan §8.4/§6.2.3: the encrypted staged copy plus the import journal. One record is
 * the whole crash-safe state of one guest switch: the snapshot, the stable [importOperationId],
 * the user's intent and, once the server finalizes, the target it belongs to.
 */
data class GuestQuarantineRecord(
    val transitionId: String,
    val guestUid: String,
    val importOperationId: String,
    val intent: TransitionIntent,
    val stagedAtMs: Long,
    /** Set only after `finalizeGuestTransition` succeeds — never before (§8.4 step 6). */
    val targetUid: String? = null,
    val importedAtMs: Long? = null,
    val summary: ImportSummary? = null,
    val snapshot: GuestSnapshot,
) {
    val purgeAtMs: Long get() = stagedAtMs + QUARANTINE_RETENTION_MS
}

/** Missing and unreadable stay distinct: unreadable means a copy existed and was lost. */
sealed interface QuarantineLoad {
    data object Absent : QuarantineLoad
    data class Present(val record: GuestQuarantineRecord) : QuarantineLoad
    data object Unreadable : QuarantineLoad
}

/** Every record that belongs to one target, plus how many files could not be read at all. */
data class QuarantineScan(
    val records: List<GuestQuarantineRecord>,
    val unreadable: Int,
)

/**
 * The seam behind the encrypted store. Production is [KeystoreGuestQuarantine]; the records
 * never leave the device and are never readable under a different UID's session (the DEK is
 * device-bound, and a record is only consumed after the server bound it to a target UID).
 */
interface GuestQuarantine {
    /** Stages or replaces the record for its (guestUid, transitionId). Returns false on I/O. */
    suspend fun save(record: GuestQuarantineRecord): Boolean

    /** Newest record for [guestUid] (retry path during the guest session). */
    suspend fun load(guestUid: String): QuarantineLoad

    /** Records bound to [targetUid], pending-import first, then newest staged. */
    suspend fun forTarget(targetUid: String): QuarantineScan

    /** §8.4 step 6: binding the target is what makes the copy importable. */
    suspend fun markFinalized(guestUid: String, transitionId: String, targetUid: String): Boolean

    /** §6.2.7: discard deletes the staged copy only after finalization succeeded. */
    suspend fun deleteFor(guestUid: String, transitionId: String)

    /** `Delete quarantine now`: removes every staged copy from this device, permanently. */
    suspend fun deleteAll()

    /** §8.4: drops copies past the disclosed seven-day window, whatever their state. */
    suspend fun sweepExpired(nowMs: Long): Int
}

/** The seam behind staging, so the transition coordinator stays testable without a database. */
interface GuestDataStager {
    suspend fun stage(guestUid: String, transitionId: String, intent: TransitionIntent): Boolean
}

package com.example.myexodus.accountability.state

import com.example.myexodus.accountability.enrollment.EnrollmentRecord

/**
 * Plan §8 Step 17 (AD-22, NFR7): local-data-wipe detection at app start.
 *
 * The prior-pairing signal is the Keystore **agreement key** — it lives outside the database,
 * so it survives whatever destroyed the DB, and the only flow that legitimately removes it is
 * the unpair shred, which destroys the key *before* deleting the enrollment (BilateralDeletion).
 * Key present + no enrollment therefore cannot be a normal unpair: the enrollment vanished
 * without its shred — the definition of a local wipe.
 *
 * On detection the orphaned pair data is cleared and the wipe is reported; recovery is
 * deliberately NOT attempted — the CEK is not re-derived and no chain state is rebuilt
 * (AD-22: "never silently recover a lost session"). The only route forward is SAS/QR
 * re-enrollment, which the Partner surface states.
 *
 * Function seams keep this JVM-testable without Android or Room.
 */
class LocalWipeDetector(
    private val hasPriorPairingSignal: () -> Boolean,
    private val enrollments: suspend () -> List<EnrollmentRecord>,
    private val wipeOrphanPairData: suspend () -> Unit,
) {

    /**
     * True when this start is a wipe start. Idempotent and re-derived every start: it stops
     * reporting the moment a re-pair lands an enrollment, and an unopenable database is
     * itself the wiped-or-worse case (the plan's "fails to open/decrypt" branch) — reported
     * without clearing, since nothing can be read to clear.
     */
    suspend fun detect(): Boolean {
        if (!runCatching { hasPriorPairingSignal() }.getOrDefault(false)) return false
        val rows = runCatching { enrollments() }.getOrElse { return true }
        if (rows.isNotEmpty()) return false
        // Best effort — a failed clear re-detects on the next start anyway, and detection
        // itself must never crash app startup.
        runCatching { wipeOrphanPairData() }
        return true
    }
}

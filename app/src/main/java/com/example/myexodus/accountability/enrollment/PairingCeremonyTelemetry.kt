package com.example.myexodus.accountability.enrollment

/**
 * Pairing plan §14 bullet 1 on the shipped surface: **one event per ceremony terminal**, so a
 * rising REJECTED rate is visible as the security signal the plan calls it (not just a UX dip).
 *
 * The plan's reason vocabulary maps as follows (recorded in ADR #101):
 * - `user_cancelled` — the only shipped cancel reason; leaving mid-ceremony and tapping
 *   "Cancel invitation" are both explicit user actions against a live token.
 * - `superseded` — no shipped counterpart: the old model's single-active-session lock was
 *   superseded by the token-doc design, where every invitation is independent.
 * - `process_restart_required` — no shipped counterpart: the ceremony holds no keys across a
 *   process death (the pending payload is in-memory), so a restart drops the attempt cleanly
 *   and the token dies with its TTL. Nothing to require.
 *
 * Metadata only (Decision 12's data contract): an outcome token, never a pairId, label, uid,
 * SAS, or any protocol material.
 */
class PairingCeremonyTelemetry(private val report: (String) -> Unit) {

    fun outcome(outcome: Outcome) {
        report("$EVENT_OUTCOME outcome=${outcome.wire}")
    }

    /** The shipped terminal set — every value is a terminal a human actually reaches. */
    enum class Outcome(val wire: String) {
        /** Both devices signed; the pairing activated. */
        PAIRED("paired"),

        /** The human said the SAS digits differ — the token is killed; neither side proceeds. */
        SAS_MISMATCH("sas_mismatch"),

        /** The 10-minute token TTL ran out (invite wait timeout, or expired at join/submit). */
        EXPIRED("expired"),

        /** A join found the code already consumed (someone else joined first). */
        TOKEN_USED("token_used"),

        /** The user abandoned the ceremony (back row with a live token, or an explicit cancel). */
        CANCELLED("cancelled"),

        /** Anything else terminal: transport/offline, verification rejection, registration failure. */
        FAILED("failed"),
    }

    companion object {
        const val EVENT_OUTCOME = "pairing_ceremony_outcome"
    }
}

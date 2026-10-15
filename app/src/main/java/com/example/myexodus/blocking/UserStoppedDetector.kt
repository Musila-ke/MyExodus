package com.example.myexodus.blocking

import android.annotation.SuppressLint
import android.app.ApplicationExitInfo

/** A process-exit observation, mapped from ApplicationExitInfo in the service. [description]
 *  is what the platform stored; it is empty for many exit reasons and its format is documented
 *  as unstable, so it is only ever used as a discriminator between same-reason exits. */
data class ProcessExit(
    val reason: Int,
    val timestampEpochMillis: Long,
    val description: String = "",
)

/**
 * Pure filter for the §8.2 user-stop check: an exit after the last known-good watermark that a
 * person caused — a first-class enforcement-loss event, not a routine restart.
 *
 * REASON_USER_REQUESTED alone is too broad. The X6532 (2026-09-28) showed `am instrument`
 * force-stopping the app as reason=10 with description "…due to start instr" / "…due to
 * finished inst": every instrumented test run was being read as the user stopping the app,
 * leaving the device pinned in APP_STOPPED until an explicit re-enable. AOSP 14's
 * ActivityManagerService is the authority for the strings — instrumentation force-stops are
 * `forceStopPackageLocked(…, "start instr" / "finished inst")`, a Settings force-stop is
 * `"stop <pkg> due to from pid <pid>"`, Task-Manager Stop is subreason STOP_APP with "fully
 * stop <pkg>/<uid> by user request", recents swipe-away is "remove task", and adb/dev-options
 * kills are "kill background" / "kill all background" — so the description is the only signal
 * that separates them. (`getSubReason()` and the SUBREASON_* constants would be the natural
 * discriminator, but both are absent from the compileSdk 37 stubs, javap-verified — ADR #17's
 * adaptation pattern.)
 *
 * The policy is to count everything except the two evidence-backed non-user classes — with one
 * correction the X6532 falsified on 2026-10-15 (ADR #156). A false positive was assumed to
 * cost only a re-enable prompt; in the app's own ANR/restart storm it cost ~2.4 h of silently
 * voided enforcement: the ANR-era kills (`reason=10` "…due to from pid …", 8–48 s after
 * `reason=6` ANR rows) re-pinned the flag 58 s after a re-enable and held it — while the gate
 * voided accrual and the fresh heartbeat baseline suppressed gap repair (platform foreground
 * 143.3 min vs ledger 19.5 min for Instagram). So a would-be count that is error-correlated —
 * an ANR or CRASH shortly before it, or any kill amid a restart storm — is NOT a pause: the
 * framework itself labels ANR escapes "user request after error" (AppErrors
 * killAppAtUserRequestLocked → reason REASON_ANR). An unrecognized OEM description that is
 * neither error-adjacent nor in a storm stays counted, fail-safe as before.
 */
@SuppressLint("InlinedApi") // REASON_USER_REQUESTED is a compile-time int; callers gate on SDK 30+
object UserStoppedDetector {
    /** Substrings of the platform descriptions written for force-stops that are not user acts.
     *  AOSP 14 AMS verbatim: "start instr", "finished inst" (instrumentation), "kill
     *  background", "kill all background" (killBackgroundProcesses / killAllBackgroundProcesses). */
    private val NON_USER_MARKERS = listOf(
        "start instr",
        "finished inst",
        "kill background",
        "kill all background",
    )

    /** An ANR/crash this close before a candidate kill makes the kill an escape from the app's
     *  own failure, not a deliberate pause. X6532 2026-10-15: 8 s and 48 s. */
    private const val ERROR_ADJACENCY_MS = 5 * 60_000L

    /** A restart storm — at least this many exits (any reason, candidate included) within
     *  [STORM_WINDOW_MS] either side. Deliberate pauses are isolated rows; the storm era
     *  produced 30+ process starts in under 2 h. */
    private const val STORM_WINDOW_MS = 10 * 60_000L
    private const val STORM_MIN_EXITS = 3

    /** Rows that would count as a deliberate stop on reason, watermark, and marker grounds —
     *  the class the error-correlation below then adjudicates. Public so callers can log
     *  exempted candidates for forensics. */
    fun userStopCandidates(exitReasons: List<ProcessExit>, watermarkEpochMillis: Long): List<ProcessExit> =
        exitReasons.filter {
            it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                it.timestampEpochMillis > watermarkEpochMillis &&
                NON_USER_MARKERS.none { marker -> it.description.contains(marker) }
        }

    fun wasUserStopped(
        exitReasons: List<ProcessExit>,
        watermarkEpochMillis: Long,
    ): Boolean = latestUserStop(exitReasons, watermarkEpochMillis) != null

    /** The latest exit a person actually caused after [watermarkEpochMillis] — the boundary
     *  after which time belongs to the pause, not to the budget (ADR #160's credit clip).
     *  Same adjudication as [wasUserStopped]: a candidate is only a stop when it is not
     *  error-correlated. */
    fun latestUserStop(
        exitReasons: List<ProcessExit>,
        watermarkEpochMillis: Long,
    ): ProcessExit? =
        userStopCandidates(exitReasons, watermarkEpochMillis)
            .filter { !isErrorCorrelated(it, exitReasons) }
            .maxByOrNull { it.timestampEpochMillis }

    /** True when [candidate] is a kill by (or amid) the app's own failure. The candidate's
     *  reason is REASON_USER_REQUESTED, so an error row can never be the candidate itself. */
    private fun isErrorCorrelated(candidate: ProcessExit, exitReasons: List<ProcessExit>): Boolean {
        val errorAdjacent = exitReasons.any {
            (it.reason == ApplicationExitInfo.REASON_ANR ||
                it.reason == ApplicationExitInfo.REASON_CRASH ||
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) &&
                it.timestampEpochMillis in
                (candidate.timestampEpochMillis - ERROR_ADJACENCY_MS) until candidate.timestampEpochMillis
        }
        if (errorAdjacent) return true
        return exitReasons.count {
            it.timestampEpochMillis in
                (candidate.timestampEpochMillis - STORM_WINDOW_MS)..(candidate.timestampEpochMillis + STORM_WINDOW_MS)
        } >= STORM_MIN_EXITS
    }
}

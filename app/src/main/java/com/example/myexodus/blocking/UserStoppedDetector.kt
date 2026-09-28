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
 * The policy is to count everything except the two evidence-backed non-user classes: a false
 * positive costs the user an explicit re-enable prompt, while a false negative silently drops
 * the report that protection was stopped by hand — so an unrecognized OEM description stays
 * counted, and only instrumentation and background-kill shapes are named out.
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

    fun wasUserStopped(
        exitReasons: List<ProcessExit>,
        watermarkEpochMillis: Long,
    ): Boolean = exitReasons.any {
        it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            it.timestampEpochMillis > watermarkEpochMillis &&
            NON_USER_MARKERS.none { marker -> it.description.contains(marker) }
    }
}

package com.example.myexodus.widget

import kotlin.math.abs

/**
 * How the app's enforcement signal is carried into the widget. A tri-state, not a boolean
 * (spec FR3a): "we could not determine it" and "it is confirmed off" are different claims, and
 * only one of them may render as UNPROTECTED.
 */
enum class EnforcementStatus { ACTIVE, INACTIVE, UNKNOWN }

/** What the widget may show. Nothing else — in particular no fourth "degraded" state. */
enum class DisplayedStatus { PROTECTED, UNPROTECTED, UNVERIFIED }

/**
 * 30 minutes (spec FR1/§7 Decision 7′). A render-time policy this app enforces on itself, not a
 * guarantee about what a launcher keeps displaying — a launcher may hold a previously submitted
 * view past this window, which is not observable from here (spec §1/§9).
 */
private const val MAX_STALE_MILLIS = 30 * 60 * 1000L

/** Widened from 2 minutes for real-world NTP sync drift (spec §5 Decision 7′/§8 Step 3). */
private const val CLOCK_SKEW_TOLERANCE_MILLIS = 5 * 60 * 1000L

/**
 * The one place that decides what the widget may claim, from a materialized [snapshot] and the
 * current clock. Pure — the caller supplies every time source, so skew and reboot cases are
 * unit-testable without touching the system clock.
 *
 * Ordering matters: every check that fails resolves to UNVERIFIED, never to PROTECTED and never
 * to UNPROTECTED. The widget may understate the app's knowledge; it may never overstate it
 * (V1 Req 36 staleness edge case).
 */
fun displayedStatusFor(
    snapshot: ProtectionSnapshot?,
    nowUtc: Long,
    nowElapsed: Long,
    nowBootCount: Int,
): DisplayedStatus {
    // Nothing written yet, or the record could not be decoded: we know nothing (spec §9).
    if (snapshot == null) return DisplayedStatus.UNVERIFIED

    // Reboot since the sample was taken invalidates it — even a "fresh" timestamp from before
    // the reboot proves nothing about the current boot (spec §5 Decision 6′). Reached on API 26+
    // always, so the plan's elapsed-regression-only API 23 fallback is dead code and is dropped.
    if (nowBootCount != snapshot.evalBootCount) return DisplayedStatus.UNVERIFIED

    val deltaUtc = nowUtc - snapshot.evalUtc
    val deltaElapsed = nowElapsed - snapshot.evalElapsed
    // A wall-clock delta that disagrees with the monotonic delta means one of them moved
    // independently: clock roll-forward (a user skipping a schedule) or roll-back, or a
    // regression in elapsed time that the boot count did not catch.
    val clockAnomaly = abs(deltaUtc - deltaElapsed) > CLOCK_SKEW_TOLERANCE_MILLIS
    if (clockAnomaly || deltaUtc > MAX_STALE_MILLIS) return DisplayedStatus.UNVERIFIED

    return when (snapshot.enforcement) {
        EnforcementStatus.ACTIVE -> DisplayedStatus.PROTECTED
        EnforcementStatus.INACTIVE -> DisplayedStatus.UNPROTECTED
        EnforcementStatus.UNKNOWN -> DisplayedStatus.UNVERIFIED
    }
}

package com.example.myexodus.streaks

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Pure per-streak time engine (habit-streaks plan §8 Step 2 — the plan calls this the
 * "RecoveryTimer"; that name is taken by the friction-session math in `blocking/recovery`,
 * so the streak engine is `StreakTimer`). Wall-clock-first with reboot-aware anomaly
 * detection: within a boot session the monotonic elapsed-realtime delta is authoritative
 * (immune to user clock changes); across a reboot-detected transition the wall-clock delta
 * is used, and a negative delta is rejected outright rather than persisting a poisoned
 * anchor (Decision 6/§9 — forward manipulation across a reboot is an accepted, logged risk).
 *
 * Evaluation runs against persisted anchor state and never stores state itself: callers
 * (the repository, Step 3) hold the per-habit mutex, persist [StreakEvaluation.Advance]
 * under the §8 Step 2.7 compare-and-update, and log the returned [StreakAnomaly] via
 * [StreakAnomalyLog]. Nothing here reads `all_time_max_streak_elapsed_ms` — the live
 * accumulator derives only from `current_streak_elapsed_ms` (Decision 13).
 */
object StreakTimer {

    /** Day unit for all streak math: 24 real hours, never a calendar boundary (Decision 17). */
    const val DAY_MS = 86_400_000L

    /**
     * First-commit build date of this binary, compiled in so it cannot be forged with the
     * device clock. A first anchor earlier than this is a device clock set before the app
     * could exist; a first anchor absurdly later is gross forward manipulation. Anything
     * subtler is offline-undetectable and accepted per Decision 6 — these bounds only catch
     * the unambiguous cases, and they are deliberately generous (tunable constants).
     */
    const val APP_RELEASE_EPOCH_MS = 1_789_307_402_000L
    private const val ANCHOR_FLOOR_SKEW_SLACK_MS = DAY_MS
    private const val ANCHOR_FUTURE_WINDOW_MS = 10L * 365 * DAY_MS

    /** §8 Step 2.5: a relapse timestamp earlier than the streak's start would create a
     *  negative-duration streak — rejected as invalid input at write time, not patched later. */
    fun isRelapseTimestampValid(recoveryStartAt: Long, tWallAtLogging: Long): Boolean =
        tWallAtLogging >= recoveryStartAt

    /** §8 Step 2 first-anchor sanity: defer the anchor (retry next foreground) if implausible. */
    fun firstAnchor(readings: TimeReadings): FirstAnchor = when {
        readings.tWallMillis < APP_RELEASE_EPOCH_MS - ANCHOR_FLOOR_SKEW_SLACK_MS ||
            readings.tWallMillis > APP_RELEASE_EPOCH_MS + ANCHOR_FUTURE_WINDOW_MS ->
            FirstAnchor.Implausible(
                StreakAnomaly(
                    type = StreakAnomalyType.FIRST_ANCHOR_IMPLAUSIBLE,
                    tWallMillis = readings.tWallMillis,
                    rBootMillis = readings.rBootMillis,
                    bootCount = readings.bootCount,
                    deltaMs = null,
                    resolution = AnomalyResolution.DEFERRED_NO_ANCHOR_WRITE,
                ),
            )

        else -> FirstAnchor.Anchored(readings.tWallMillis, readings.rBootMillis, readings.bootCount)
    }

    fun evaluate(anchor: StreakAnchor, readings: TimeReadings): StreakEvaluation {
        // §8 Step 2.4: the streak was superseded by a later relapse — discard, re-fetch.
        if (anchor.relapsedAt != null) return StreakEvaluation.Superseded

        // Same boot session requires the OS boot count to match, be non-zero (a missing/zero
        // value is treated as a reboot-detected transition, §8 Step 2), and elapsed-realtime to
        // not have gone backward. The latter guards devices that report a stuck boot count:
        // without it, a reboot would be read as a same-session monotonic delta and SUBTRACT
        // time from the accumulator.
        val sameBootSession = readings.bootCount == anchor.bootCountLastObserved &&
            readings.bootCount != 0L &&
            readings.rBootMillis >= anchor.rBootLastObserved

        return if (sameBootSession) {
            // Monotonic path: elapsed-realtime cannot be set by the user within a session.
            StreakEvaluation.Advance(
                currentStreakElapsedMs = anchor.currentStreakElapsedMs +
                    (readings.rBootMillis - anchor.rBootLastObserved),
                tWallLastObserved = readings.tWallMillis,
                rBootLastObserved = readings.rBootMillis,
                bootCountLastObserved = readings.bootCount,
                anomaly = if (readings.tWallMillis < anchor.tWallLastObserved) {
                    // Informational only: the math above never touched the wall clock.
                    StreakAnomaly(
                        type = StreakAnomalyType.BACKWARD_CLOCK_IN_SESSION,
                        tWallMillis = readings.tWallMillis,
                        rBootMillis = readings.rBootMillis,
                        bootCount = readings.bootCount,
                        deltaMs = readings.tWallMillis - anchor.tWallLastObserved,
                        resolution = AnomalyResolution.ACCEPTED_MONOTONIC,
                    )
                } else {
                    null
                },
            )
        } else {
            // Reboot-detected transition: wall-clock delta is the only remaining signal.
            val wallDelta = readings.tWallMillis - anchor.tWallLastObserved
            if (wallDelta < 0) {
                // Backward manipulation: do not advance, do not persist the anchor — retry on
                // the next evaluation instead of anchoring a state we know is poisoned (§9).
                StreakEvaluation.RejectedBackwardClock(
                    StreakAnomaly(
                        type = StreakAnomalyType.BACKWARD_CLOCK_AFTER_REBOOT,
                        tWallMillis = readings.tWallMillis,
                        rBootMillis = readings.rBootMillis,
                        bootCount = readings.bootCount,
                        deltaMs = wallDelta,
                        resolution = AnomalyResolution.REJECTED_NO_ANCHOR_UPDATE,
                    ),
                )
            } else {
                // Accepted (Decision 6). A legitimate reboot and a forward jump are
                // indistinguishable offline, so every positive transition is logged
                // informatively with its raw delta for manual review (§14).
                StreakEvaluation.Advance(
                    currentStreakElapsedMs = anchor.currentStreakElapsedMs + wallDelta,
                    tWallLastObserved = readings.tWallMillis,
                    rBootLastObserved = readings.rBootMillis,
                    bootCountLastObserved = readings.bootCount,
                    anomaly = StreakAnomaly(
                        type = StreakAnomalyType.REBOOT_WALL_DELTA,
                        tWallMillis = readings.tWallMillis,
                        rBootMillis = readings.rBootMillis,
                        bootCount = readings.bootCount,
                        deltaMs = wallDelta,
                        resolution = AnomalyResolution.ACCEPTED_WALL_DELTA,
                    ),
                )
            }
        }
    }
}

/** Persisted anchor of one streak (§8 Step 2 field set; `relapsed_at` marks it superseded). */
data class StreakAnchor(
    val tWallLastObserved: Long,
    val rBootLastObserved: Long,
    val bootCountLastObserved: Long,
    val currentStreakElapsedMs: Long,
    val rowVersion: Long,
    val relapsedAt: Long? = null,
)

/** One live sample of every clock the engine uses. */
data class TimeReadings(
    val tWallMillis: Long,
    val rBootMillis: Long,
    val bootCount: Long,
)

sealed interface StreakEvaluation {
    /** Persist via compare-and-update on [StreakAnchor.rowVersion]; log [anomaly] if present. */
    data class Advance(
        val currentStreakElapsedMs: Long,
        val tWallLastObserved: Long,
        val rBootLastObserved: Long,
        val bootCountLastObserved: Long,
        val anomaly: StreakAnomaly? = null,
    ) : StreakEvaluation

    /** Negative wall delta across a transition: nothing persisted, retry next evaluation. */
    data class RejectedBackwardClock(val anomaly: StreakAnomaly) : StreakEvaluation

    /** Already-relapsed streak evaluated from stale state: discard and re-fetch (§8 Step 2.4). */
    data object Superseded : StreakEvaluation
}

sealed interface FirstAnchor {
    data class Anchored(val tWallMillis: Long, val rBootMillis: Long, val bootCount: Long) : FirstAnchor
    data class Implausible(val anomaly: StreakAnomaly) : FirstAnchor
}

/** One §14 anomaly event: timestamp comes from [tWallMillis] (no trusted clock offline). */
data class StreakAnomaly(
    val type: StreakAnomalyType,
    val tWallMillis: Long,
    val rBootMillis: Long,
    val bootCount: Long,
    val deltaMs: Long?,
    val resolution: AnomalyResolution,
)

enum class StreakAnomalyType {
    BACKWARD_CLOCK_IN_SESSION,
    BACKWARD_CLOCK_AFTER_REBOOT,
    REBOOT_WALL_DELTA,
    FIRST_ANCHOR_IMPLAUSIBLE,

    /** §8 Step 2.5 / §14 `relapse-rollback-input-rejected`: a relapse stamped earlier than
     *  the active streak's start was refused as invalid input instead of rolling over. */
    RELAPSE_ROLLBACK_REJECTED,

    /** §8 Step 2.7 defense in depth: the compare-and-update lost to another writer that
     *  bypassed the per-habit mutex; the evaluation re-fetches and retries rather than
     *  persisting over the newer state. */
    VERSION_CONFLICT,
}

enum class AnomalyResolution {
    ACCEPTED_MONOTONIC,
    ACCEPTED_WALL_DELTA,
    REJECTED_NO_ANCHOR_UPDATE,
    DEFERRED_NO_ANCHOR_WRITE,
    REJECTED_INVALID_INPUT,
    RETRIED_AFTER_REFETCH,
}

/** Live readings source (house style: `SystemRecoveryClock` in `blocking/recovery`); kept
 *  trivial so the engine above stays a pure function that JVM tests drive with literals. */
object SystemStreakClock {
    fun read(context: Context): TimeReadings = TimeReadings(
        tWallMillis = System.currentTimeMillis(),
        rBootMillis = SystemClock.elapsedRealtime(),
        bootCount = Settings.Global
            .getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            .toLong(),
    )
}

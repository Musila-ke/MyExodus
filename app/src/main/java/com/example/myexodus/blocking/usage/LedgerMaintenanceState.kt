package com.example.myexodus.blocking.usage

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** §8.8: what one maintenance run may do to the ledger. */
enum class MaintenanceOutcome { PRUNE, SUSPENDED }

/**
 * §8.8's one piece of cross-run state: the two clocks as the last maintenance run saw them
 * (the input to [LedgerRetention.clockJumpDetected]) and the pruning cool-down a detected jump
 * leaves behind. Plain preferences are the right lifetime — this is one scheduled job's memory
 * of itself, with no accounting meaning, so it stays out of the ledger.
 */
@Singleton
class LedgerMaintenanceState @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val lastWallClockMs: Long get() = prefs.getLong(KEY_LAST_WALL, 0L)
    val lastElapsedRealtimeMs: Long get() = prefs.getLong(KEY_LAST_ELAPSED, 0L)

    /**
     * Frequency guard for the *frequent* trigger (the service's cycle — XOS never dispatches
     * WorkManager, ADR #27, so the scheduled job is not enough there): true when the last run is
     * old enough that running again is a daily pass and not a churn. Anchored on
     * [lastWallClockMs], so both triggers share one notion of "last run".
     */
    fun maintenanceDue(nowWallClockMs: Long, intervalMs: Long = MIN_INTERVAL_MS): Boolean =
        nowWallClockMs - lastWallClockMs >= intervalMs

    /**
     * The whole §8.8 decision for a run starting at these clocks: compare against the previous
     * run's frame, record this one either way (so the next run measures disparity against the
     * latest possible frame), and answer whether this run may prune. A detected jump restarts
     * the cool-down from now; a run already inside one is suspended again.
     */
    fun beginRun(
        nowWallClockMs: Long,
        nowElapsedRealtimeMs: Long,
        cooldownMs: Long = LedgerRetention.CLOCK_JUMP_COOLDOWN_MS,
    ): MaintenanceOutcome {
        val jumped = LedgerRetention.clockJumpDetected(
            lastWallClockMs, lastElapsedRealtimeMs, nowWallClockMs, nowElapsedRealtimeMs,
        )
        val suspendedUntil = prefs.getLong(KEY_SUSPENDED_UNTIL, 0L)
        prefs.edit {
            putLong(KEY_LAST_WALL, nowWallClockMs)
            putLong(KEY_LAST_ELAPSED, nowElapsedRealtimeMs)
            if (jumped) putLong(KEY_SUSPENDED_UNTIL, nowWallClockMs + cooldownMs)
        }
        // The cool-down is exclusive at its boundary: the first run at or past it prunes again.
        return if (jumped || suspendedUntil > nowWallClockMs) MaintenanceOutcome.SUSPENDED
        else MaintenanceOutcome.PRUNE
    }

    /**
     * Device-verification seam (§12's jump row): plant a frame so the jump path can be
     * exercised on a real device without moving its clock. Clears any cool-down so the run
     * under test starts from the frame alone. No production caller.
     */
    fun seedFrame(wallClockMs: Long, elapsedRealtimeMs: Long) {
        prefs.edit {
            putLong(KEY_LAST_WALL, wallClockMs)
            putLong(KEY_LAST_ELAPSED, elapsedRealtimeMs)
            remove(KEY_SUSPENDED_UNTIL)
        }
    }

    /** Device-verification seam: drop all §8.8 state back to first-run conditions. */
    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        /**
         * 20 h, not 24: the two triggers are independent (WorkManager's daily job and the
         * service's cycle), and a run by either must not push the other's next turn past a day.
         */
        const val MIN_INTERVAL_MS = 20 * 60 * 60 * 1000L

        private const val PREFS_NAME = "ledger_maintenance"
        private const val KEY_LAST_WALL = "last_wall_clock_ms"
        private const val KEY_LAST_ELAPSED = "last_elapsed_realtime_ms"
        private const val KEY_SUSPENDED_UNTIL = "prune_suspended_until_ms"
    }
}

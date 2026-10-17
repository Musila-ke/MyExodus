package com.example.myexodus.domainusage

import java.util.concurrent.atomic.AtomicLong

/**
 * §8 S5.1 / §4.7: one rule's live accounting identity, keyed by `(rule_id, revision)`
 * (D-02, D-32). [touch] is the only relay-thread mutator and allocates nothing (INV-12); the
 * accrual fields belong to `domainusage-tick`, which is also the only writer of [totalMs] and
 * [generation]. The db thread only reads [generation] and [changedSincePersist] to close the
 * §4.9 dirty-flag race.
 */
class RuleCounter(
    val ruleId: String,
    val revision: Long,
    initialTotalMs: Long = 0L,
    @Volatile var dailyLimitMs: Long,
    @Volatile var enforcedToday: Boolean,
) {

    /** `"$ruleId:$revision"` — the ledger's map key and the §6.5 sweep key. */
    val key: String = "$ruleId:$revision"

    /** Tick-thread written, volatile read (S5.1). */
    @Volatile
    var totalMs: Long = initialTotalMs
        private set

    @Volatile
    var exhausted: Boolean = false

    @Volatile
    var changedSincePersist: Boolean = false

    /** Threshold warnings already surfaced for this day ([UsageLedger.takeLimitWarnings]).
     *  Tick thread only; seeded from the persisted total on attach so a restart never re-fires
     *  a threshold the user has already been warned about. */
    var warning1Fired: Boolean = false
    var warning2Fired: Boolean = false

    /**
     * Elapsed instant the current activity streak began. Written by relay threads (through
     * [touch]) as well as the tick thread, so it is atomic rather than the plain `Long` of the
     * S5.1 field list — same field, safer type (see the implementation log).
     */
    val sessionStart = AtomicLong(NEVER)

    val lastActivityElapsed = AtomicLong(NEVER)

    /** §4.7: accrual has been booked up to this instant. Tick thread only. */
    var lastAccountedElapsed: Long = NEVER
        private set

    /** §4.9: bumped on every accrual; the persistence guard compares it against its snapshot. */
    @Volatile
    var generation: Long = 0
        private set

    /**
     * §4.7: records a qualifying chunk (the caller only calls this for relayed chunks
     * ≥ `MIN_ACTIVITY_BYTES`, either direction). An idle gap over `T_IDLE_MS` starts a new
     * streak, whose start bounds what the next recompute may credit. Allocation-free (INV-12).
     */
    fun touch(now: Long) {
        val last = lastActivityElapsed.get()
        if (last == NEVER || now - last > MeteringConfig.T_IDLE_MS) sessionStart.set(now)
        lastActivityElapsed.set(now)
    }

    /**
     * §4.7: books `[intervalStart, intervalEnd)` — correct for any gap since the last call, so
     * a delayed or doubled tick invocation still yields the exact total (INV-16).
     */
    fun recompute(now: Long) {
        if (!intervalOf(now)) return
        if (ivEnd > ivStart) {
            totalMs += ivEnd - ivStart
            generation++
            changedSincePersist = true
        }
        book(ivEnd)
    }

    /**
     * §6.5 step 2 / §6.7: splits the in-progress interval at [boundary], keeps the post-boundary
     * remainder as the new day's opening total, and returns the outgoing day's final total for
     * the rollover snapshot.
     */
    fun splitDayAt(boundary: Long, now: Long): Long {
        if (intervalOf(now) && ivEnd > ivStart) {
            val split = boundary.coerceIn(ivStart, ivEnd)
            totalMs += split - ivStart
            generation++
            changedSincePersist = true
            val oldTotal = totalMs
            totalMs = ivEnd - split
            book(ivEnd)
            sessionStart.set(boundary)
            warning1Fired = false
            warning2Fired = false
            return oldTotal
        }
        return resetForNewDay(boundary)
    }

    /** §6.7 for a counter that was not accruing today: drop the total without crediting time. */
    fun resetForNewDay(boundary: Long): Long {
        val oldTotal = totalMs
        totalMs = 0L
        book(boundary)
        sessionStart.set(boundary)
        warning1Fired = false
        warning2Fired = false
        return oldTotal
    }

    /** §4.7's interval, into [ivStart]/[ivEnd] to keep the tick path allocation-free. */
    private fun intervalOf(now: Long): Boolean {
        val last = lastActivityElapsed.get()
        if (last == NEVER) return false
        ivStart = maxOf(lastAccountedElapsed, sessionStart.get())
        ivEnd = if (now - last <= MeteringConfig.T_IDLE_MS) now
        else minOf(last + MeteringConfig.T_IDLE_MS, now)
        return true
    }

    /** Accounting only ever moves forward: a clamped interval must not reopen booked time. */
    private fun book(instant: Long) {
        if (instant > lastAccountedElapsed) lastAccountedElapsed = instant
    }

    private var ivStart = 0L
    private var ivEnd = 0L

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

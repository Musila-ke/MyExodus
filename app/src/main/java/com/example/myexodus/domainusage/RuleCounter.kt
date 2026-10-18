package com.example.myexodus.domainusage

import java.util.concurrent.ConcurrentLinkedQueue
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
    /** The rule's normalized pattern, for the FR3 warning copy (ADR #179). */
    val pattern: String,
    initialTotalMs: Long = 0L,
    @Volatile var dailyLimitMs: Long,
    @Volatile var enforcedToday: Boolean,
    @Volatile var expiresAtWallMs: Long = 0L,
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
     * Closed activity streaks the tick thread has not booked yet. A recompute can be delayed
     * arbitrarily (process scheduling, GC); if it outlives an idle gap, `touch` resets
     * [sessionStart] for the new streak and the old streak's unbooked tail — or, if no tick ran
     * during it, the whole streak — would otherwise be lost. Relay threads append here, the tick
     * thread drains in [recompute]/[splitDayAt]. One small record per > `T_IDLE_MS` gap, so the
     * per-chunk path stays allocation-free (INV-12).
     */
    private val pendingStreaks = ConcurrentLinkedQueue<LongArray>()

    /**
     * Monotonic instant accrual must stop at, set by [markExpiry] once the wall clock reports
     * the rule's `blockExpiresAt` passed (audit A-02). `Long.MAX_VALUE` until then. Written by
     * the tick thread, read by relay threads in [touch], so it is volatile — a lapsed rule's
     * flows stop accruing even before the next rule snapshot is published. The clamp, not the
     * `touch` short-circuit, is what makes the booked total exact: a delayed tick still books
     * every millisecond up to expiry and nothing after.
     */
    @Volatile
    private var accountingEndElapsed: Long = Long.MAX_VALUE

    /**
     * §4.7: records a qualifying chunk (the caller only calls this for relayed chunks
     * ≥ `MIN_ACTIVITY_BYTES`, either direction). An idle gap over `T_IDLE_MS` starts a new
     * streak, whose start bounds what the next recompute may credit; the streak that just ended
     * is queued for the tick thread so a stalled recompute cannot lose it. Allocation-free on
     * the per-chunk path: a record is created only when a gap closes a streak.
     */
    fun touch(now: Long) {
        // A lapsed rule's flows keep forwarding (the tap is already bound) but stop accruing.
        if (now >= accountingEndElapsed) return
        // Read the old session first: a concurrent relay thread that has already advanced
        // `lastActivity` must never pair a fresh session start with a stale last-activity.
        val start = sessionStart.get()
        val last = lastActivityElapsed.get()
        if (last == NEVER || now - last > MeteringConfig.T_IDLE_MS) {
            if (last != NEVER) pendingStreaks.add(longArrayOf(start, last + MeteringConfig.T_IDLE_MS))
            sessionStart.set(now)
        }
        lastActivityElapsed.set(now)
    }

    /**
     * §4.7: books `[intervalStart, intervalEnd)` — correct for any gap since the last call, so
     * a delayed or doubled tick invocation still yields the exact total (INV-16).
     */
    fun recompute(now: Long) {
        drainPendingStreaks(Long.MAX_VALUE)
        if (!intervalOf(now)) return
        if (ivEnd > ivStart) {
            totalMs += ivEnd - ivStart
            generation++
            changedSincePersist = true
        }
        book(ivEnd)
    }

    /**
     * Books every streak a relay thread closed while this counter's tick was stalled. Each record
     * is clamped to [lastAccountedElapsed], so a duplicate produced by two relay threads racing a
     * session boundary books nothing extra, and a record already covered by a tick is a no-op.
     * Returns the portion after [boundary] for a day split; [boundary] is `Long.MAX_VALUE` for a
     * plain recompute.
     */
    private fun drainPendingStreaks(boundary: Long): Long {
        var afterBoundary = 0L
        while (true) {
            val streak = pendingStreaks.poll() ?: return afterBoundary
            val start = maxOf(lastAccountedElapsed, streak[0])
            val end = minOf(streak[1], accountingEndElapsed)
            if (end <= start) continue
            val preEnd = minOf(end, boundary)
            if (preEnd > start) {
                totalMs += preEnd - start
                generation++
                changedSincePersist = true
            }
            if (end > boundary) {
                val post = end - maxOf(start, boundary)
                if (post > 0) {
                    afterBoundary += post
                    generation++
                    changedSincePersist = true
                }
            }
            book(end)
        }
    }

    /**
     * §6.5 step 2 / §6.7: splits the in-progress interval at [boundary], keeps the post-boundary
     * remainder as the new day's opening total, and returns the outgoing day's final total for
     * the rollover snapshot.
     */
    fun splitDayAt(boundary: Long, now: Long): Long {
        // A stall can leave closed streaks unbooked across midnight; each record is split at the
        // boundary so the post-boundary part carries into the new day.
        val carried = drainPendingStreaks(boundary)
        if (intervalOf(now) && ivEnd > ivStart) {
            val split = boundary.coerceIn(ivStart, ivEnd)
            totalMs += split - ivStart
            generation++
            changedSincePersist = true
            val oldTotal = totalMs
            totalMs = carried + (ivEnd - split)
            book(ivEnd)
            // book(ivEnd) already floors future accrual at the split; pulling a streak that
            // started after the boundary back to it would credit idle pre-activity time.
            sessionStart.set(maxOf(boundary, sessionStart.get()))
            warning1Fired = false
            warning2Fired = false
            return oldTotal
        }
        val oldTotal = totalMs
        totalMs = carried
        book(boundary)
        sessionStart.set(maxOf(boundary, sessionStart.get()))
        warning1Fired = false
        warning2Fired = false
        return oldTotal
    }

    /** §6.7 for a counter that was not accruing today: drop the total without crediting time. */
    fun resetForNewDay(boundary: Long): Long {
        val oldTotal = totalMs
        totalMs = 0L
        pendingStreaks.clear()
        book(boundary)
        lastActivityElapsed.set(NEVER)
        sessionStart.set(boundary)
        warning1Fired = false
        warning2Fired = false
        return oldTotal
    }

    /** Drops unbooked streaks for a counter that is not enforced today (their time never counts). */
    fun discardPendingStreaks() {
        pendingStreaks.clear()
    }

    /** §4.7's interval, into [ivStart]/[ivEnd] to keep the tick path allocation-free. */
    private fun intervalOf(now: Long): Boolean {
        val last = lastActivityElapsed.get()
        if (last == NEVER) return false
        ivStart = maxOf(lastAccountedElapsed, sessionStart.get())
        val naturalEnd = if (now - last <= MeteringConfig.T_IDLE_MS) now
        else minOf(last + MeteringConfig.T_IDLE_MS, now)
        ivEnd = minOf(naturalEnd, accountingEndElapsed)
        return true
    }

    /** Audit A-02: freezes accrual at [instantElapsed] (monotonic); only ever moves earlier. */
    fun markExpiry(instantElapsed: Long) {
        if (instantElapsed < accountingEndElapsed) accountingEndElapsed = instantElapsed
    }

    /**
     * A new snapshot may extend a rule that lapsed under an older expiry (the editor writes
     * `blockExpiresAt`, the publisher rebuilds, and the same `(id, revision)` keeps its counter).
     * Re-arm so the next tick re-derives the deadline from the new wall-clock expiry.
     */
    fun retargetExpiry(wallMillis: Long) {
        if (expiresAtWallMs == wallMillis) return
        expiresAtWallMs = wallMillis
        accountingEndElapsed = Long.MAX_VALUE
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

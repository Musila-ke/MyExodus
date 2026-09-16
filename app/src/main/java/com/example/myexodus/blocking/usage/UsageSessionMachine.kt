package com.example.myexodus.blocking.usage

/**
 * Path A's session state machine (enforcement plan v2 §8 Step 4, §6 steps 2–4). Pure JVM: the
 * caller supplies the elapsed-realtime clock and boot count, so the reboot-mid-session discard
 * (§12) is unit-testable without Android. One session at a time; the machine never talks to the
 * engine — it only produces the incremental deltas the service feeds to
 * [UsageAccountingEngine.processUsageObservation]. Duration math is elapsedRealtime-only (NFR3).
 *
 * Thread-safety: touched from the main thread (accessibility dispatch) and the ticker coroutine,
 * so every method synchronizes on the instance monitor.
 */
class UsageSessionMachine {

    /** One incremental credit the accounting engine must be told about (§6 step 3). */
    data class Tick(val packageName: String, val deltaMs: Long, val displayId: Int)

    /** Result of a confirmed foreground observation: the closed session's final tick, if the
     *  transition ended one, and whether a session was opened. */
    data class Observation(val closedTick: Tick?, val opened: Boolean)

    private class Session(
        val packageName: String,
        val displayId: Int,
        var anchorElapsedMs: Long,
        val bootCountAtStart: Int,
    )

    private var session: Session? = null

    val activePackage: String?
        @Synchronized get() = session?.packageName

    /**
     * A confirmed foreground transition (§6 steps 2/4). [tracked] = the package has an enabled
     * rule with a real limit (the service reads this off the rules snapshot — mirroring the
     * engine's "disabled rule is untracked"). A tracked package with no session opens one; a
     * different confirmed foreground closes the open session with its final tick first
     * (crediting usage up to the transition — never clamped, this is the v1 clamp fix).
     */
    @Synchronized
    fun observeForeground(
        packageName: String,
        tracked: Boolean,
        displayId: Int,
        nowElapsedMs: Long,
        bootCount: Int,
    ): Observation {
        val current = session
        if (current != null && current.packageName == packageName) {
            // Re-foregrounding the same app (dialogs, in-app activity hops) keeps the session
            // and its anchor: restarting would drop the un-ticked delta (Decision 4 — the
            // ticker, not the next event, drives accounting).
            if (tracked) return Observation(closedTick = null, opened = false)
            // Rule paused or limit removed mid-session: close without reopening.
            session = null
            return Observation(closedTick = finalTick(current, nowElapsedMs), opened = false)
        }
        val closed = current?.let { finalTick(it, nowElapsedMs) }
        session = if (tracked) Session(packageName, displayId, nowElapsedMs, bootCount) else null
        return Observation(closedTick = closed, opened = session != null)
    }

    /** One ticker tick for the open session (§6 step 3), or null while idle. [bootCount]
     *  mismatch discards the session without crediting any delta and reopens it anchored now
     *  (Decision 4 / §9 reboot-mid-session; in-memory state cannot actually survive a reboot,
     *  so this is the defensive contract the §12 unit test pins down). */
    @Synchronized
    fun tick(nowElapsedMs: Long, bootCount: Int): Tick? {
        val current = session ?: return null
        if (bootCount != current.bootCountAtStart) {
            session = Session(current.packageName, current.displayId, nowElapsedMs, bootCount)
            return null
        }
        val delta = (nowElapsedMs - current.anchorElapsedMs).coerceAtLeast(0)
        current.anchorElapsedMs = nowElapsedMs
        return Tick(current.packageName, delta, current.displayId)
    }

    /** Ends the open session, returning its final tick (§6 step 4). */
    @Synchronized
    fun close(nowElapsedMs: Long): Tick? {
        val current = session ?: return null
        session = null
        return finalTick(current, nowElapsedMs)
    }

    /** Drops the open session without crediting (master switch off / teardown) — a deliberate
     *  under-count, never an over-count. */
    @Synchronized
    fun reset() {
        session = null
    }

    private fun finalTick(s: Session, nowElapsedMs: Long): Tick =
        Tick(s.packageName, (nowElapsedMs - s.anchorElapsedMs).coerceAtLeast(0), s.displayId)
}

package com.example.myexodus.blocking.usage

import com.example.myexodus.blocking.ProcessExit

/** One foreground mark, reduced from `UsageEvents` to what the gap repair needs. */
data class UsageMark(val timestampMs: Long, val resumed: Boolean)

/**
 * Bounded repair of accounting lost to a process death (enforcement plan v3 §5 D1/D3).
 *
 * The ticker is the only accountant, so a dead process is a window in which nothing was
 * credited *and* nothing could block — the one failure mode that leaves the counter below
 * Android's own record while the limit silently fails to hold. `ApplicationExitInfo`
 * supplies the death timestamp the platform already recorded, so the gap is known exactly
 * rather than inferred from a heartbeat; `queryEvents` then fills it from the platform's
 * own foreground record.
 *
 * Amendment scope: NFR3 admits this path only, and only within a single process lifetime.
 * It is a corrective top-up that runs once, over a span the process demonstrably did not
 * observe — never a primary accounting input, so Decision 4's rejection of "elapsed time
 * since the last event" as a duration source is untouched.
 *
 * [uncoveredMs] is the one piece here the heartbeat-driven gap repair also uses: both
 * recovery paths must check the same overlap rule before crediting (§8.9).
 */
object UsageGapRepair {
    /** Below this, a restart is ordinary churn (a killed-and-restarted ticker loses at most
     *  one tick) and repairing risks double-crediting a normal close-tick tail. */
    const val MIN_GAP_MS = 20_000L

    /** Most recent process exit after [watermarkEpochMillis], any reason. Callers pass the
     *  previous known-good startup watermark so old records are never replayed. */
    fun latestExitAfter(exits: List<ProcessExit>, watermarkEpochMillis: Long): ProcessExit? =
        exits.filter { it.timestampEpochMillis > watermarkEpochMillis }
            .maxByOrNull { it.timestampEpochMillis }

    /**
     * The span nothing observed: from the last observation — or the death, when the journal
     * has no newer observation to point at — never earlier than today's local midnight (the
     * credit lands on today's row), up to the process start. End-exclusive. Null when the span
     * is too short to matter.
     *
     * [observedUntilEpochMillis] is the wall of the newest journal row the process wrote:
     * v3 §5 D3's span is `(last_credited_wall_ms, now]`, and a death-anchored window is its
     * narrow reading. OEM freezers SIGSTOP the process minutes before the watchdog kills it —
     * measured on the X6532 (2026-09-28): last row 06:00:17, ANR kill 06:02:41 — so the
     * death-anchored window leaves that prefix permanently uncounted (no live path can credit
     * a frozen process, and the next pass measures from a baseline the repair already moved).
     * The overlap check is what keeps the wider window safe: it subtracts every interval the
     * journal already credited, so the prefix only fills where nothing else has.
     *
     * No upper bound (enforcement plan v3 §5 D3, verbatim: "No cap. A cap would re-introduce
     * under-counting, which is the defect class this delta exists to remove. The floor and the
     * clip are the only bounds."). The shipped 6 h cap did exactly that on the X6532: OEM
     * freezers keep the process dead for most of a day, so the one window that needed repair
     * was the one the cap refused — and a refusal is permanent (§8.7 advances the baseline).
     * The clip is what keeps it honest: credit can only ever land on today, and §8.9's overlap
     * check against the journal keeps a long window from double-crediting live-path rows.
     */
    fun repairWindow(
        exitEpochMillis: Long,
        processStartEpochMillis: Long,
        localMidnightEpochMillis: Long,
        minGapMs: Long = MIN_GAP_MS,
        observedUntilEpochMillis: Long? = null,
    ): LongRange? {
        val lastObserved = minOf(exitEpochMillis, observedUntilEpochMillis ?: exitEpochMillis)
        val start = maxOf(lastObserved, localMidnightEpochMillis)
        if (processStartEpochMillis - start < minGapMs) return null
        return start until processStartEpochMillis
    }

    /**
     * The foreground intervals themselves, clipped to [clipStartMs, clipEndMs). A RESUMED with
     * no following PAUSED runs to the clip end — at the moment of the repair that package is
     * still foreground, and the process start bounds how much of it we can claim. A second
     * RESUMED while already open is ignored: same-package activity switches deliver
     * `B_RESUMED, A_PAUSED` in that order, and re-anchoring on the second would drop the
     * switch interval. Zero-length clipped intervals are dropped.
     */
    fun foregroundIntervalsWithin(
        marks: List<UsageMark>,
        clipStartMs: Long,
        clipEndMs: Long,
    ): List<LongRange> {
        val intervals = mutableListOf<LongRange>()
        var openAt: Long? = null
        for (mark in marks.sortedBy { it.timestampMs }) {
            if (mark.resumed) {
                if (openAt == null) openAt = mark.timestampMs
            } else {
                val start = openAt ?: continue
                clippedOverlap(start, mark.timestampMs, clipStartMs, clipEndMs)?.let(intervals::add)
                openAt = null
            }
        }
        openAt?.let { clippedOverlap(it, clipEndMs, clipStartMs, clipEndMs)?.let(intervals::add) }
        return intervals
    }

    /** Foreground time for one package — the sum of [foregroundIntervalsWithin]; one reduction,
     *  so a repair and the audit that reads the same marks can never disagree. */
    fun foregroundMsWithin(marks: List<UsageMark>, clipStartMs: Long, clipEndMs: Long): Long =
        foregroundIntervalsWithin(marks, clipStartMs, clipEndMs).sumOf { it.last + 1 - it.first }

    private fun clippedOverlap(
        startMs: Long,
        endMs: Long,
        clipStartMs: Long,
        clipEndMs: Long,
    ): LongRange? {
        val from = maxOf(startMs, clipStartMs)
        val to = minOf(endMs, clipEndMs)
        return if (to > from) from until to else null
    }

    /**
     * §8.9 overlap avoidance: how much of [windowFromMs, windowToMs) no interval in [covered]
     * already accounts for. Ranges are start-inclusive/end-exclusive, may be given in any
     * order, and may overlap each other; the union is measured. A reconciliation credit is
     * scaled to this remainder, so the same logical recovery re-expressed with different
     * bounds still adds nothing.
     */
    fun uncoveredMs(covered: List<LongRange>, windowFromMs: Long, windowToMs: Long): Long {
        if (windowToMs <= windowFromMs) return 0L
        val clipped = covered.mapNotNull {
            val from = maxOf(it.first, windowFromMs)
            val to = minOf(it.last + 1, windowToMs)
            if (to > from) from until to else null
        }.sortedBy { it.first }
        var coveredMs = 0L
        var cursor = windowFromMs
        for (range in clipped) {
            val from = maxOf(range.first, cursor)
            val to = range.last + 1
            if (to <= from) continue
            coveredMs += to - from
            cursor = to
        }
        return ((windowToMs - windowFromMs) - coveredMs).coerceAtLeast(0L)
    }
}

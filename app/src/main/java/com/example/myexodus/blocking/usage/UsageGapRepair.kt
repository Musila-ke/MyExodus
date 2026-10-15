package com.example.myexodus.blocking.usage

/** One foreground mark, reduced from `UsageEvents` to what the gap repair needs. */
data class UsageMark(val timestampMs: Long, val resumed: Boolean)

/**
 * Bounded repair of accounting lost to an interruption (enforcement plan v3 §5 D1/D3; the
 * credit-edge anchor is ADR #160's amendment).
 *
 * The ticker is the only accountant, so any era it did not credit leaves the counter below
 * Android's own record while the limit silently fails to hold. `queryEvents` fills the era
 * from the platform's own foreground record. The service-start window is anchored at the
 * ledger's own last credit per package ([uncreditedWindow]) — every interruption ends at a
 * process/service start, and everything after the last credit is unaccounted time no matter
 * which interruption produced it. The earlier death-anchored reading (ADR #147) and its
 * observation clamp both deferred to journal evidence rows, and a heartbeat row proves
 * liveness, never a credit: the 2026-10-15 flagged era had fresh heartbeats through it and
 * 143.3 min of platform foreground against 19.5 min credited (ADR #156).
 *
 * Amendment scope: NFR3 admits this path only, and only within a single process lifetime.
 * It is a corrective top-up that runs once, over a span no credit covers — never a primary
 * accounting input, so Decision 4's rejection of "elapsed time since the last event" as a
 * duration source is untouched.
 *
 * [uncoveredMs] is the one piece here the heartbeat-driven gap repair also uses: both
 * recovery paths must check the same overlap rule before crediting (§8.9).
 */
object UsageGapRepair {
    /** Below this, a restart is ordinary churn (a killed-and-restarted ticker loses at most
     *  one tick) and repairing risks double-crediting a normal close-tick tail. */
    const val MIN_GAP_MS = 20_000L

    /**
     * The span nothing has credited: from [lastCreditWallMs] (or the process's first moment,
     * when the journal holds no credit today), never earlier than today's local midnight (the
     * credit lands on today's row) or this boot's wall origin (§8.5 — an elapsed-frame window
     * cannot start before the monotonic clock did, so a boot-crossing window starts at zero),
     * up to [resumeWallMs] — the moment live accounting resumes (this process's start at a
     * service-start audit; the re-enable transition at a pause release) — clipped at
     * [stopWallMs] when the latest genuine user stop falls inside the span. End-exclusive.
     * Null when the span is too short to matter.
     *
     * The stop clip: a genuine pause's era ([stopWallMs] onward, until the user re-enables)
     * must never be claimed — "time limits stay paused until you re-enable". A stop at or
     * before the span's start is outside it (credits resumed after a later re-enable), so it
     * does not shorten the span; while [paused] the stop always bounds, because a paused
     * pipeline cannot have advanced its credit edge past it.
     *
     * No upper bound (enforcement plan v3 §5 D3, verbatim: "No cap. A cap would re-introduce
     * under-counting, which is the defect class this delta exists to remove. The floor and the
     * clip are the only bounds."). The shipped 6 h cap did exactly that on the X6532: OEM
     * freezers keep the process dead for most of a day, so the one window that needed repair
     * was the one the cap refused — and a refusal is permanent (§8.7 advances the baseline).
     * The clip is what keeps it honest: credit can only ever land on today, and §8.9's overlap
     * check against the journal keeps a long window from double-crediting live-path rows.
     */
    fun uncreditedWindow(
        lastCreditWallMs: Long?,
        localMidnightEpochMillis: Long,
        bootOriginWallMs: Long,
        resumeWallMs: Long,
        stopWallMs: Long? = null,
        paused: Boolean = false,
        minGapMs: Long = MIN_GAP_MS,
    ): LongRange? {
        val start = maxOf(lastCreditWallMs ?: Long.MIN_VALUE, localMidnightEpochMillis, bootOriginWallMs)
        val end = when {
            stopWallMs == null -> resumeWallMs
            paused || stopWallMs >= start -> minOf(resumeWallMs, stopWallMs)
            else -> resumeWallMs
        }
        if (end - start < minGapMs) return null
        return start until end
    }

    /**
     * The span a diagnostics-detected silence leaves recoverable: from the silence's start —
     * judged against today's local midnight, which the credit may not cross — up to the pass
     * moment. End-exclusive. Null when the span is too short to matter.
     */
    fun repairWindow(
        silenceStartEpochMillis: Long,
        observedUntilEpochMillis: Long,
        localMidnightEpochMillis: Long,
        minGapMs: Long = MIN_GAP_MS,
    ): LongRange? {
        val start = maxOf(silenceStartEpochMillis, localMidnightEpochMillis)
        if (observedUntilEpochMillis - start < minGapMs) return null
        return start until observedUntilEpochMillis
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

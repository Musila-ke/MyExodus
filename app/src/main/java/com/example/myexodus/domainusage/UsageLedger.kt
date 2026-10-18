package com.example.myexodus.domainusage

import com.example.myexodus.blocking.usage.UsageAccountingEngine
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * §8 S5.2 / §4.7–4.9: the in-memory ledger — the enforcement source of truth (D-05, INV-4).
 * Holds one [RuleCounter] per `(rule_id, revision)`; relay threads only look a counter up at
 * flow open and then [RuleCounter.touch] it, while [tick] (the `domainusage-tick` thread) is the
 * single writer of totals, generations and exhausted flags.
 *
 * The day key is the §4.8 effective day key: the local date under [anchoredZone] — the zone the
 * caller captured at `start()`, never the current system default — raised to be
 * `max(localDate, persistedLastDayKey)` so it can never move backward within a run (INV-3).
 */
class UsageLedger(
    private val clock: MonotonicClock,
    private val wallClock: WallClock,
    private val counters: EngineCounters,
    private val flows: FlowRegistry,
    private val mode: EngineMode,
    private val anchoredZone: ZoneId,
    initialDayKey: String = "",
) {

    private val byKey = ConcurrentHashMap<String, RuleCounter>()
    private val currentRevisionByRuleId = ConcurrentHashMap<String, Long>()

    /** The keys of the current rule snapshot; tick thread only. */
    private val activeKeys = HashSet<String>()

    /**
     * The startup load's persisted totals, kept for the day. A `(id, revision)` pair that first
     * appears mid-session — the rule was disabled when the engine loaded — must still start from
     * what it already used, or a restart plus re-enable would mint a fresh quota (ADR #188).
     * Cleared at the rollover: the new day's counters all start at zero.
     */
    private var persistedTotals: Map<String, Long> = emptyMap()

    @Volatile
    var dayKey: String = initialDayKey
        private set

    /** The attribution seam (S6): the counter an attributed flow binds to for its lifetime. */
    fun counterFor(ruleId: String, revision: Long): RuleCounter? = byKey["$ruleId:$revision"]

    /** §8 S5.6: today's usage for the rule's *current* revision. */
    fun usageTodayMs(ruleId: String): Long {
        val revision = currentRevisionByRuleId[ruleId] ?: return 0L
        return byKey["$ruleId:$revision"]?.totalMs ?: 0L
    }

    /**
     * §14 debug surface: the exact accrued milliseconds for every live rule's current revision.
     * The UI displays whole minutes; a verification run reads this to compare the meter's own
     * arithmetic against ground truth without the display floor in the way.
     */
    fun debugTotals(): List<Pair<String, Long>> =
        currentRevisionByRuleId.entries
            .mapNotNull { (ruleId, revision) -> byKey["$ruleId:$revision"]?.let { it.pattern to it.totalMs } }
            .sortedBy { it.first }

    /**
     * §8 S5.2: applies a rule snapshot on the tick thread. New `(id, revision)` pairs start from
     * their persisted total or 0; a pattern edit (D-32) leaves the old revision's counter to the
     * flows still open on it; removed rules' counters are dropped by [tick] once nothing
     * references them.
     */
    fun applyRules(rules: List<DomainRule>, seededTotals: Map<String, Long> = emptyMap()) {
        if (seededTotals.isNotEmpty()) persistedTotals = seededTotals
        activeKeys.clear()
        for (rule in rules) {
            val key = "${rule.id}:${rule.revision}"
            activeKeys.add(key)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = RuleCounter(
                    ruleId = rule.id,
                    revision = rule.revision,
                    pattern = rule.pattern,
                    initialTotalMs = seededTotals[key] ?: persistedTotals[key] ?: 0L,
                    dailyLimitMs = rule.dailyLimitMs,
                    enforcedToday = rule.enforcedToday,
                    expiresAtWallMs = rule.expiresAt,
                ).also { seedWarnings(it) }
            } else {
                if (existing.dailyLimitMs != rule.dailyLimitMs) {
                    existing.dailyLimitMs = rule.dailyLimitMs
                    // A raised limit must re-arm thresholds above the current total; a lowered
                    // one must not fire retroactively for time already spent.
                    seedWarnings(existing)
                }
                existing.enforcedToday = rule.enforcedToday
                existing.retargetExpiry(rule.expiresAt)
            }
            currentRevisionByRuleId[rule.id] = rule.revision
        }
        currentRevisionByRuleId.keys.retainAll(rules.mapTo(HashSet()) { it.id })
    }

    /**
     * §4.9/§6.6: the rows a persistence cycle should write. Taken on the tick thread, so the
     * day key it stamps cannot race a rollover.
     */
    fun dirtySnapshot(): List<UsageSnapshot> = byKey.values
        .filter { it.changedSincePersist }
        .map { UsageSnapshot(it.ruleId, it.revision, dayKey, it.totalMs, it.generation) }

    /**
     * §4.9: acknowledges a successful commit. The dirty flag is cleared only if the counter's
     * generation still equals the committed one — an accrual that landed in between leaves it
     * set, so the next cycle writes the newer value.
     */
    fun acknowledge(ruleId: String, revision: Long, generation: Long) {
        val counter = byKey["$ruleId:$revision"] ?: return
        if (counter.generation == generation) counter.changedSincePersist = false
    }

    /** One §6.5 pass. Returns the rollover to persist when the effective day key moved. */
    fun tick(): DayRollover? {
        val now = clock.elapsedMs()
        val wallNow = wallClock.now(anchoredZone)
        val wallNowMs = wallClock.nowMillis()
        val candidate = wallNow.toLocalDate().toString()
        val rollover = when {
            dayKey.isEmpty() -> {
                dayKey = candidate
                null
            }
            candidate > dayKey -> rolloverTo(candidate, wallNow, now)
            else -> null
        }
        diagnoseClockJump(wallNow, now)
        for (counter in byKey.values) {
            // Audit A-02: freeze at the exact instant the wall-clock expiry passed, mapped onto
            // the monotonic timeline, before this tick recomputes. An expired rule's counter is
            // still recomputed below — that call books the final pre-expiry milliseconds.
            if (counter.expiresAtWallMs > 0L && wallNowMs > counter.expiresAtWallMs) {
                counter.markExpiry(now - (wallNowMs - counter.expiresAtWallMs))
            }
            if (counter.enforcedToday) counter.recompute(now)
            else counter.discardPendingStreaks()
        }
        evaluateExhaustion(wallNowMs)
        dropStaleCounters()
        return rollover
    }

    /**
     * §6.5 step 2 / §6.7: split every enforced counter's in-progress interval at the boundary
     * instant, hand the outgoing day's finals to the caller, and open the new day.
     */
    private fun rolloverTo(newKey: String, wallNow: LocalDateTime, now: Long): DayRollover {
        val oldKey = dayKey
        val sinceMidnight = wallNow.toLocalTime().toNanoOfDay() / 1_000_000L
        val boundary = now - sinceMidnight
        val finals = ArrayList<UsageSnapshot>(byKey.size)
        for (counter in byKey.values) {
            val generationBefore = counter.generation
            val oldTotal = if (counter.enforcedToday) {
                counter.splitDayAt(boundary, now)
            } else {
                counter.resetForNewDay(boundary)
            }
            if (oldTotal > 0L || counter.changedSincePersist) {
                finals += UsageSnapshot(counter.ruleId, counter.revision, oldKey, oldTotal, generationBefore)
            }
        }
        dayKey = newKey
        // A counter absent from the new day's snapshot has no quota to enforce and no total to
        // protect; the keep-until-rollover rule in dropStaleCounters ends here. The persisted
        // day totals are yesterday's — a mid-day re-enable must not seed them into today.
        persistedTotals = emptyMap()
        byKey.keys.retainAll(activeKeys)
        return DayRollover(oldKey, newKey, finals)
    }

    /** §6.5 step 3: diagnostic only — a wall jump never affects accrual (INV-2). */
    private fun diagnoseClockJump(wallNow: LocalDateTime, now: Long) {
        val wallMs = wallNow.atZone(anchoredZone).toInstant().toEpochMilli()
        if (lastTickMono != Long.MIN_VALUE &&
            abs((wallMs - lastTickWallMs) - (now - lastTickMono)) > MeteringConfig.CLOCK_JUMP_THRESHOLD_MS
        ) {
            counters.inc(Counter.clock_jump)
        }
        lastTickMono = now
        lastTickWallMs = wallMs
    }

    /**
     * FR3 warning thresholds (the same policy the app-usage engine uses, one definition): the
     * crossings this tick should surface, each consumed exactly once per counter/day. Only a
     * rule's current revision can warn — a superseded counter kept alive by its still-open
     * flows must not produce a notification for a rule the user already edited away from.
     */
    fun takeLimitWarnings(): List<LimitWarning> {
        var out: ArrayList<LimitWarning>? = null
        val wallNowMs = wallClock.nowMillis()
        for (counter in byKey.values) {
            if (!counter.enforcedToday || counter.dailyLimitMs <= 0L) continue
            if (counter.expiresAtWallMs > 0L && wallNowMs > counter.expiresAtWallMs) continue
            if (currentRevisionByRuleId[counter.ruleId] != counter.revision) continue
            val (threshold1, threshold2) = UsageAccountingEngine.calculateWarningThresholdsMs(counter.dailyLimitMs)
            val remaining = (counter.dailyLimitMs - counter.totalMs).coerceAtLeast(0L)
            when {
                !counter.warning2Fired && counter.totalMs >= threshold2 -> {
                    counter.warning1Fired = true
                    counter.warning2Fired = true
                    (out ?: ArrayList<LimitWarning>(2).also { out = it }).add(
                        LimitWarning(2, remaining, counter.pattern),
                    )
                }
                !counter.warning1Fired && counter.totalMs >= threshold1 -> {
                    counter.warning1Fired = true
                    (out ?: ArrayList<LimitWarning>(2).also { out = it }).add(
                        LimitWarning(1, remaining, counter.pattern),
                    )
                }
            }
        }
        return out ?: emptyList()
    }

    /** Fired flags start from what the persisted total already crossed (no duplicate on restart). */
    private fun seedWarnings(counter: RuleCounter) {
        val (threshold1, threshold2) = UsageAccountingEngine.calculateWarningThresholdsMs(counter.dailyLimitMs)
        counter.warning1Fired = counter.dailyLimitMs > 0L && counter.totalMs >= threshold1
        counter.warning2Fired = counter.dailyLimitMs > 0L && counter.totalMs >= threshold2
    }

    /** §6.5 step 5: exhaustion follows the ledger total; in `ENFORCE` a new exhaustion sweeps. */
    private fun evaluateExhaustion(wallNowMs: Long) {
        for (counter in byKey.values) {
            // A lapsed rule has no quota left to exhaust: it must not sweep flows, and any
            // residual exhausted flag is cleared so nothing else consults it (audit A-02).
            if (counter.expiresAtWallMs > 0L && wallNowMs > counter.expiresAtWallMs) {
                counter.exhausted = false
                continue
            }
            val over = counter.totalMs >= counter.dailyLimitMs
            if (over && !counter.exhausted) {
                counter.exhausted = true
                if (mode == EngineMode.ENFORCE) flows.resetAll(counter.key)
            } else if (!over && counter.exhausted) {
                counter.exhausted = false
            }
        }
    }

    /**
     * §8 S5.2: a superseded revision's counter is dropped once no flow references it. A dirty
     * counter waits for its pending write — dropping it would lose up to `PERSIST_INTERVAL_MS`
     * of already-accrued usage.
     *
     * A rule that merely left the snapshot keeps its counter for the rest of the day. The
     * snapshot drops disabled rules, so dropping on absence would hand a disabled-then-
     * re-enabled rule (same id, same revision) a fresh quota — a one-toggle bypass. Absent
     * ids are pruned at the day rollover instead; only a revision change (the pattern edit
     * the drop exists for) drops here.
     */
    private fun dropStaleCounters() {
        val iterator = byKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in activeKeys) continue
            if (entry.value.changedSincePersist) continue
            val current = currentRevisionByRuleId[entry.value.ruleId] ?: continue
            if (current != entry.value.revision && flows.isEmpty(entry.key)) iterator.remove()
        }
    }

    private var lastTickMono = Long.MIN_VALUE
    private var lastTickWallMs = Long.MIN_VALUE
}

/** §4.9: one row of the generation-tagged persistence snapshot. */
class UsageSnapshot(
    val ruleId: String,
    val revision: Long,
    val dayKey: String,
    val totalMs: Long,
    val generation: Long,
)

/**
 * FR3: one threshold crossing to surface; [remainingMs] is the budget left at the crossing and
 * [pattern] is the rule's own site (ADR #179 — the owner overrode INV-7's notification clause;
 * logs, exceptions and storage still never carry hostnames).
 */
data class LimitWarning(val level: Int, val remainingMs: Long, val pattern: String)

/** §6.7: the day-key change, carrying the outgoing day's final rows to persist. */
class DayRollover(
    val oldDayKey: String,
    val newDayKey: String,
    val finals: List<UsageSnapshot>,
)

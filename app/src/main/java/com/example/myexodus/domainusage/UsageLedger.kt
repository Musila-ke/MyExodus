package com.example.myexodus.domainusage

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
     * §8 S5.2: applies a rule snapshot on the tick thread. New `(id, revision)` pairs start from
     * their persisted total or 0; a pattern edit (D-32) leaves the old revision's counter to the
     * flows still open on it; removed rules' counters are dropped by [tick] once nothing
     * references them.
     */
    fun applyRules(rules: List<DomainRule>, seededTotals: Map<String, Long> = emptyMap()) {
        activeKeys.clear()
        for (rule in rules) {
            val key = "${rule.id}:${rule.revision}"
            activeKeys.add(key)
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = RuleCounter(
                    ruleId = rule.id,
                    revision = rule.revision,
                    initialTotalMs = seededTotals[key] ?: 0L,
                    dailyLimitMs = rule.dailyLimitMs,
                    enforcedToday = rule.enforcedToday,
                )
            } else {
                existing.dailyLimitMs = rule.dailyLimitMs
                existing.enforcedToday = rule.enforcedToday
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
            if (counter.enforcedToday) counter.recompute(now)
        }
        evaluateExhaustion()
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

    /** §6.5 step 5: exhaustion follows the ledger total; in `ENFORCE` a new exhaustion sweeps. */
    private fun evaluateExhaustion() {
        for (counter in byKey.values) {
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
     * §8 S5.2: a counter whose rule is gone is dropped once no flow references it. A dirty
     * counter waits for its pending write — dropping it would lose up to `PERSIST_INTERVAL_MS`
     * of already-accrued usage.
     */
    private fun dropStaleCounters() {
        val iterator = byKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in activeKeys) continue
            if (entry.value.changedSincePersist) continue
            if (flows.isEmpty(entry.key)) iterator.remove()
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

/** §6.7: the day-key change, carrying the outgoing day's final rows to persist. */
class DayRollover(
    val oldDayKey: String,
    val newDayKey: String,
    val finals: List<UsageSnapshot>,
)

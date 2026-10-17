package com.example.myexodus.domainusage

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * §8 S7.2 / §6.6, §6.8: every Room touch of this feature, on `domainusage-db` (§4.3, INV-1).
 * Owns the db-failure policy: the startup load degrades immediately and retries every
 * [RETRY_MS]; a flush tolerates [MAX_CONSECUTIVE_FAILURES] before degrading; any success clears
 * it (§9). The DAO arrives as a provider because opening the rules store can fail into a
 * recovery state, and the retry loop must re-attempt the open, not just the query.
 */
class UsagePersistence(
    private val dao: () -> RuleUsageDao?,
    private val counters: EngineCounters,
    private val onDbDegraded: (Boolean) -> Unit,
) {

    private var consecutiveFlushFailures = 0

    /**
     * §6.8: today's totals, the D-32 revision seeds, and the retention delete — retried until it
     * succeeds or the caller's coroutine is cancelled (stop).
     */
    suspend fun loadStartupState(effectiveDayKey: String): StartupLoad {
        while (true) {
            try {
                val store = requireNotNull(dao()) { "rules store unavailable" }
                val rows = store.loadDay(effectiveDayKey)
                val revisions = store.maxRevisions()
                // §6.8's retention run happens in the same pass, before anything is written back.
                store.deleteOlderThan(retentionCutoff(effectiveDayKey))
                onDbDegraded(false)
                return StartupLoad(
                    totalsByKey = rows.associate { "${it.ruleId}:${it.revision}" to it.activeMs },
                    maxRevisionByRuleId = revisions.associate { it.ruleId to it.revision },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                counters.inc(Counter.db_flush_fail)
                onDbDegraded(true)
                delay(RETRY_MS)
            }
        }
    }

    /**
     * §6.6/§4.9: one flush cycle. [rows] was taken on the tick thread, so the day key it carries
     * cannot race a rollover; the dirty flag is cleared only through the generation guard, so an
     * accrual that landed while the commit was in flight is written by the next cycle.
     *
     * @return true when the rows were written (or there was nothing to write), false when this
     * attempt failed. Mid-day callers may ignore it — the still-set dirty flag re-emits the row
     * next cycle — but §6.7's outgoing-day finals exist nowhere else after the split, so the
     * rollover caller must retry until true.
     */
    suspend fun flush(ledger: UsageLedger, rows: List<UsageSnapshot>): Boolean {
        if (rows.isEmpty()) return true
        return try {
            val store = requireNotNull(dao()) { "rules store unavailable" }
            store.upsertMax(rows)
            for (row in rows) ledger.acknowledge(row.ruleId, row.revision, row.generation)
            counters.inc(Counter.db_flush_ok)
            consecutiveFlushFailures = 0
            onDbDegraded(false)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            counters.inc(Counter.db_flush_fail)
            if (++consecutiveFlushFailures >= MAX_CONSECUTIVE_FAILURES) onDbDegraded(true)
            false
        }
    }

    /** §6.7: the retention delete that also runs on a day rollover. */
    suspend fun deleteOlderThan(cutoffDayKey: String) {
        try {
            dao()?.deleteOlderThan(cutoffDayKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            counters.inc(Counter.db_flush_fail)
        }
    }

    companion object {
        /** §6.8's retry cadence for a failed startup load. */
        const val RETRY_MS = 10_000L

        /** §8 S7.2/§9: a flush is only "failing" once it has failed this many times in a row. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** §6.8/§6.7: rows strictly older than [MeteringConfig.RETENTION_DAYS] before [dayKey]. */
        fun retentionCutoff(dayKey: String): String =
            LocalDate.parse(dayKey).minusDays(MeteringConfig.RETENTION_DAYS.toLong()).toString()
    }
}

/** §6.8's load result: today's totals keyed `"$ruleId:$revision"`, plus the D-32 revision seeds. */
class StartupLoad(
    val totalsByKey: Map<String, Long>,
    val maxRevisionByRuleId: Map<String, Long>,
)

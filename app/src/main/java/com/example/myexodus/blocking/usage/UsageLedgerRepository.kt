package com.example.myexodus.blocking.usage

import android.util.Log
import androidx.room3.withWriteTransaction
import com.example.myexodus.BuildConfig
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.UsageLedgerDao
import com.example.myexodus.data.db.entity.UsageLedger
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.telemetry.CrashReporting
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What happened to one attempted journal write. */
sealed interface AppendOutcome {
    /**
     * The row(s) landed. [closingDayCreditedMs] is the share attributed to [closingDayKey] —
     * the amount the caller adds to that day's projection, so the projection can never drift
     * from the journal by attributing a midnight-crossing interval to the wrong day.
     */
    data class Written(val closingDayKey: String, val closingDayCreditedMs: Long) : AppendOutcome

    /** The same observation arriving again (§8.6). Nothing was written and nothing must be
     *  credited again — the original insert already advanced the projection. */
    data object Duplicate : AppendOutcome

    /** A different payload reusing an existing id (§8.6). A bug, never a retry: rejected and
     *  reported rather than silently dropped. */
    data class Collision(val eventId: String) : AppendOutcome

    /** §8.2 validation refused the draft, or the store is unavailable. */
    data class Rejected(val reason: String) : AppendOutcome
}

/** §8.8's retention settings, in one place (R9: a single configuration constant pair). */
object LedgerRetention {
    const val RETENTION_DAYS = 90L
    const val PRUNE_BATCH_SIZE = 500
    /** Per maintenance run, not per transaction. A run that hits the cap simply leaves the rest
     *  for the next one (the plan's partial-prune contract) — maintenance must never become an
     *  unbounded job on the accounting dispatcher. */
    const val MAX_BATCHES_PER_RUN = 20

    /** A forward wall-clock jump past this suspends pruning for [CLOCK_JUMP_COOLDOWN_MS]: under
     *  a false "everything is old now" read, the sweep would delete exactly the recent evidence
     *  an investigation needs. */
    const val CLOCK_JUMP_THRESHOLD_MS = 6 * 60 * 60 * 1000L
    const val CLOCK_JUMP_COOLDOWN_MS = 24 * 60 * 60 * 1000L

    /**
     * §8.8's clock-anomaly test, evaluated between two maintenance runs: how far the wall clock
     * moved beyond what the monotonic clock can account for. The disparity is one-sided — a
     * backwards wall-clock move never reports, because it cannot make old rows look new.
     *
     * A missing previous frame ([previousElapsedRealtimeMs] == 0, the first run) or a monotonic
     * reset ([nowElapsedRealtimeMs] < previous) means the two clocks are not comparable across
     * these samples: a reboot restarts elapsed time, so no disparity can be asserted. A long
     * power-off is *not* distinguishable from a jump by construction (elapsed time stops while
     * the wall clock advances) and takes the same conservative path — which is the intended
     * direction of error: one skipped sweep, never a deleted-recently mistake.
     */
    fun clockJumpDetected(
        previousWallClockMs: Long,
        previousElapsedRealtimeMs: Long,
        nowWallClockMs: Long,
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        if (previousWallClockMs == 0L || previousElapsedRealtimeMs == 0L) return false
        if (nowElapsedRealtimeMs < previousElapsedRealtimeMs) return false
        return nowWallClockMs - previousWallClockMs - (nowElapsedRealtimeMs - previousElapsedRealtimeMs) >
            CLOCK_JUMP_THRESHOLD_MS
    }
}

/**
 * The only application-level entry point for the usage journal (usage-ledger plan §4.3):
 * idempotent appends, correction insertion, day totals, the projection rebuild, and retention.
 * No other layer touches `usage_ledger`.
 *
 * **Transaction contract.** [append] and [correct] do *not* open a transaction — they are
 * designed to be called from inside the caller's write transaction so the journal row and the
 * projection update commit or roll back together (§8.3). [appendStandalone] is the same write
 * for callers with nothing else to commit. Every write runs on the shared accounting dispatcher
 * so ledger order matches accounting order.
 */
class UsageLedgerRepository(
    private val storeFactory: AppRulesStoreFactory,
    private val dispatcher: CoroutineDispatcher,
    private val crashReporting: CrashReporting,
    private val sourceVersion: String = BuildConfig.VERSION_NAME,
) {
    /** In-transaction append. See the class contract. */
    suspend fun append(draft: LedgerEventDraft): AppendOutcome {
        val db = storeFactory.open() ?: return AppendOutcome.Rejected("store unavailable")
        return appendWith(db.usageLedgerDao(), draft)
    }

    /** Self-contained append for writers with no companion projection update (heartbeats, gap
     *  rows, reconciliation). */
    suspend fun appendStandalone(draft: LedgerEventDraft): AppendOutcome = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext AppendOutcome.Rejected("store unavailable")
        db.withWriteTransaction { appendWith(db.usageLedgerDao(), draft) }
    }

    /**
     * §8.12: a compensating row against an existing event. The referenced row is never touched.
     * A correction whose target does not exist is rejected — an unresolvable reference is a
     * programming error, not a correction.
     */
    suspend fun correct(
        targetEventId: String,
        deltaMs: Long,
        nowWallClockMs: Long,
        bootCount: Int,
        elapsedNowMs: Long,
        source: String,
    ): AppendOutcome = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext AppendOutcome.Rejected("store unavailable")
        db.withWriteTransaction {
            val dao = db.usageLedgerDao()
            val target = dao.findByEventId(targetEventId)
            val draft = LedgerEventDraft(
                kind = UsageLedgerKind.CORRECTION,
                packageName = target?.packageName ?: return@withWriteTransaction AppendOutcome.Rejected(
                    "unresolvable references_event_id",
                ),
                creditedMs = deltaMs,
                elapsedStartMs = elapsedNowMs,
                elapsedEndMs = elapsedNowMs,
                bootCount = bootCount,
                source = source,
                wallClockTsMs = nowWallClockMs,
                referencesEventId = targetEventId,
                eventIdOverride = UsageLedgerMapper.correctionEventIdFor(targetEventId, nowWallClockMs),
            )
            appendWith(dao, draft)
        }
    }

    /** §8.13 for one scope — the audit read that answers "what was that day's real total". */
    suspend fun dayTotal(packageName: String, dayKey: String): Long {
        val db = storeFactory.open() ?: return 0L
        return db.usageLedgerDao().sumCredited(packageName, dayKey, UsageLedgerKind.CREDITING)
    }

    /**
     * §8.3's authoritative recovery path: recompute every package's *current* projection value
     * from the journal alone. The tracker is a live counter, not a history table (Decision 20
     * rolls it in place at the first write after midnight), so the rebuild restores the day each
     * tracker row is anchored to — the journal is the only surviving record of every other day.
     * `blockedToday` is re-derived against the rule's own limit so a drifted counter cannot
     * leave a stale block behind. Returns the rows rewritten.
     */
    suspend fun rebuildProjection(): Int = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext 0
        db.withWriteTransaction {
            val profile = db.userProfileDao().getActive() ?: return@withWriteTransaction 0
            val trackerDao = db.dailyUsageTrackerDao()
            val ruleDao = db.restrictionRuleDao()
            val totals = db.usageLedgerDao().dayTotals(UsageLedgerKind.CREDITING)
                .associate { (it.packageName to it.dayKey) to it.totalMs }
            var rewritten = 0
            trackerDao.getForProfile(profile.userId).forEach { tracker ->
                val rebuilt = totals[tracker.packageName to tracker.localDateAnchor] ?: 0L
                if (rebuilt == tracker.msUsedToday) return@forEach
                val limit = ruleDao.getByProfileAndPackage(profile.userId, tracker.packageName)
                    ?.dailyLimitMs
                Log.w(TAG, "projection rebuild pkg=${tracker.packageName} ${tracker.msUsedToday}->$rebuilt")
                trackerDao.update(
                    tracker.copy(
                        msUsedToday = rebuilt,
                        blockedToday = limit != null && rebuilt >= limit,
                    ),
                )
                rewritten++
            }
            rewritten
        }
    }

    /** §8.4/AD-15 baseline: the latest row the given source wrote. */
    suspend fun latestForSource(source: String): UsageLedger? {
        val db = storeFactory.open() ?: return null
        return db.usageLedgerDao().latestForSource(source)
    }

    /** Observation-outage evidence rows (§8.7) since a wall-clock cutoff. */
    suspend fun gapsSince(sinceWallClockMs: Long): List<UsageLedger> {
        val db = storeFactory.open() ?: return emptyList()
        return db.usageLedgerDao().rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, sinceWallClockMs)
    }

    /**
     * D1's measured-outage rows (§8.7) since a wall-clock cutoff. Reboot boundaries are
     * excluded by [AccountingDiagnostics.isMeasuredOutage]: the boundary row records that the
     * monotonic frame restarted, which is not an interruption to report to anyone. The rows
     * themselves are returned whole — #13's dismissal/recurrence decision needs their wall
     * clocks, which the span lengths alone do not carry.
     */
    suspend fun measuredOutagesSince(sinceWallClockMs: Long): List<UsageLedger> =
        gapsSince(sinceWallClockMs).filter(AccountingDiagnostics::isMeasuredOutage)

    /** The span lengths of [measuredOutagesSince] — the report's count/largest inputs. */
    suspend fun outageSpansSince(sinceWallClockMs: Long): List<Long> =
        measuredOutagesSince(sinceWallClockMs).map { it.elapsedEndMs - it.elapsedStartMs }

    /**
     * §8.8 retention: delete journal rows older than [LedgerRetention.RETENTION_DAYS] in bounded
     * batches, so no single transaction holds a write lock over the whole backlog. A partial run
     * leaves the journal consistent and simply continues on the next scheduled maintenance pass.
     */
    suspend fun prune(
        nowWallClockMs: Long,
        batchSize: Int = LedgerRetention.PRUNE_BATCH_SIZE,
    ): Int = withContext(dispatcher) {
        val db = storeFactory.open() ?: return@withContext 0
        val cutoff = nowWallClockMs - LedgerRetention.RETENTION_DAYS * 24 * 60 * 60 * 1000L
        var deleted = 0
        var batches = 0
        while (batches < LedgerRetention.MAX_BATCHES_PER_RUN) {
            val batch = db.withWriteTransaction {
                db.usageLedgerDao().deleteOldestBatch(cutoff, batchSize)
            }
            deleted += batch
            batches++
            if (batch < batchSize) break
        }
        if (deleted > 0) Log.i(TAG, "ledger prune removed=$deleted cutoff=$cutoff")
        deleted
    }

    private suspend fun appendWith(dao: UsageLedgerDao, draft: LedgerEventDraft): AppendOutcome {
        UsageLedgerMapper.rejectionReason(draft)?.let {
            Log.w(TAG, "ledger reject kind=${UsageLedgerKind.nameOf(draft.kind)} pkg=${draft.packageName}: $it")
            return AppendOutcome.Rejected(it)
        }
        val slices = UsageLedgerMapper.splitAtDayBoundary(draft, ZoneId.systemDefault())
        var collision: String? = null
        var duplicates = 0
        slices.forEach { slice ->
            val row = UsageLedgerMapper.toRow(draft, slice, sourceVersion)
            // Insert first, inspect only on conflict: the conflict case is the rare one, and a
            // read-then-insert would race a second writer of the same observation.
            if (dao.insert(row) != -1L) return@forEach
            val existing = dao.findByEventId(row.eventId)
            if (existing != null && UsageLedgerMapper.samePayload(existing, row)) {
                duplicates++
            } else {
                collision = row.eventId
                // A distinct payload behind an existing id means identity derivation is broken
                // — reported, never treated as a safe retry.
                Log.e(TAG, "ledger id collision event=${row.eventId} pkg=${row.packageName}")
                crashReporting.nonFatal(
                    "ledger_event_id_collision",
                    cause = "differing_payload",
                    "kind" to UsageLedgerKind.nameOf(row.kind),
                    "source" to row.source,
                )
            }
        }
        val closing = slices.last()
        return when {
            collision != null -> AppendOutcome.Collision(collision)
            // Every slice already present: the projection was advanced by the original insert,
            // so the caller must credit nothing again (§8.6).
            duplicates == slices.size -> AppendOutcome.Duplicate
            else -> AppendOutcome.Written(closing.dayKey, closing.creditedMs)
        }
    }

    private companion object {
        const val TAG = "MyExodusLedger"
    }
}

package com.example.myexodus.insights

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.SessionCount
import com.example.myexodus.data.db.dao.TargetOutcomeCount
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.UsageLedgerKind
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One day's row in both strips: attempts from `attempt_log`, usage from the ledger's own
 *  frozen `day_key` totals. */
data class InsightDay(
    val date: LocalDate,
    val blocked: Int,
    val overrides: Int,
    val creditedMs: Long,
)

/** One blocked target in the window, most-blocked first. */
data class InsightTarget(val target: String, val count: Int)

/** One package's credited usage in the window, highest first. */
data class InsightPackage(val packageName: String, val creditedMs: Long)

/** Urge/recovery practice, window counts plus the all-time journal size. */
data class RecoveryInsight(
    val completedTechniques: Int,
    val loggedOnly: Int,
    val abandoned: Int,
    val interrupted: Int,
    val allTime: Long,
)

/** One habit's record of record: best run and relapse count (current run lives in HabitState). */
data class HabitInsight(
    val habitId: String,
    val label: String,
    val bestElapsedMs: Long,
    val relapses: Int,
)

/** Everything the Insights page renders, resolved in one store pass. */
data class InsightsSnapshot(
    val days: List<InsightDay>,
    val blockedTotal: Int,
    val overridesTotal: Int,
    val topTargets: List<InsightTarget>,
    val screenTimeTotalMs: Long,
    val screenTimeTodayMs: Long,
    val topPackages: List<InsightPackage>,
    val recovery: RecoveryInsight,
    val habits: List<HabitInsight>,
) {
    /** True when nothing has ever been recorded in any of the three journals — the page's
     *  one honest empty state, rather than five separate "no data" cards a new user reads as
     *  a broken screen. */
    val isEmpty: Boolean
        get() = blockedTotal == 0 &&
            overridesTotal == 0 &&
            screenTimeTotalMs == 0L &&
            recovery.allTime == 0L &&
            habits.isEmpty()
}

/**
 * Read-only aggregation for the Insights page (owner task #22). Every number is either the
 * usage ledger's authoritative day totals or a SQL aggregate over the local journals; nothing
 * is estimated, transmitted, or inferred from absence. `null` means the encrypted store
 * refused to open — the page says so instead of rendering zeros as facts.
 *
 * Bounds: attempts are aggregated in SQL (`countsSince`), sessions through the existing
 * `countsByStateSince` aggregate, usage through `dayTotals` (bounded by the ledger's 90-day
 * retention). Only the day strip reads attempt rows, and only two small columns of them.
 */
@Singleton
class InsightsRepository @Inject constructor(
    private val storeFactory: AppRulesStoreFactory,
) {
    suspend fun load(): InsightsSnapshot? = load(System.currentTimeMillis(), ZoneId.systemDefault())

    /** Time/zone seams for tests; the production call sites use the two above. */
    internal suspend fun load(nowMs: Long, zone: ZoneId): InsightsSnapshot? {
        val store = storeFactory.open() ?: return null
        val days = InsightsWindow.days(nowMs, zone)
        val since = InsightsWindow.startMs(nowMs, zone)
        val dayKeys = days.map { it.toString() }.toSet()

        val stamps = store.attemptLogDao().stampsSince(since)
        val perDay = InsightsWindow.bucketAttempts(stamps, days, zone)
        val targetCounts = store.attemptLogDao().countsSince(since)

        val ledger = store.usageLedgerDao()
            .dayTotalsSince(UsageLedgerKind.CREDITING, days.first().toString())
            .filter { it.dayKey in dayKeys }
        val usageByDay = ledger.groupBy { it.dayKey }.mapValues { (_, rows) -> rows.sumOf { it.totalMs } }
        val usageByPackage = ledger.groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.sumOf { it.totalMs } }
            .filterValues { it > 0 }

        val sessions = store.recoverySessionDao().countsByStateSince(since)
        val allTimeSessions = store.recoverySessionDao().count()

        val profile = store.userProfileDao().getActive()
        val habits = profile?.let { store.habitProgressDao().getForProfile(it.userId) }.orEmpty()

        val daysOut = days.map { day ->
            val attempts = perDay[day] ?: DayAttempts(blocked = 0, overrides = 0)
            InsightDay(
                date = day,
                blocked = attempts.blocked,
                overrides = attempts.overrides,
                creditedMs = usageByDay[day.toString()] ?: 0L,
            )
        }

        return InsightsSnapshot(
            days = daysOut,
            blockedTotal = daysOut.sumOf { it.blocked },
            overridesTotal = daysOut.sumOf { it.overrides },
            topTargets = topTargets(targetCounts),
            screenTimeTotalMs = daysOut.sumOf { it.creditedMs },
            screenTimeTodayMs = daysOut.lastOrNull()?.creditedMs ?: 0L,
            topPackages = usageByPackage.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(TOP_N)
                .map { InsightPackage(it.key, it.value) },
            recovery = recoveryInsight(sessions, allTimeSessions),
            habits = habits.map {
                HabitInsight(
                    habitId = it.progressId,
                    label = it.targetIdentifier,
                    bestElapsedMs = it.allTimeMaxStreakElapsedMs,
                    relapses = it.totalRelapses,
                )
            },
        )
    }

    private fun topTargets(counts: List<TargetOutcomeCount>): List<InsightTarget> =
        counts.asSequence()
            .filter { it.outcome == AttemptLog.OUTCOME_BLOCKED }
            .groupBy { it.target }
            .mapValues { (_, rows) -> rows.sumOf { it.count } }
            .filterValues { it > 0 }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_N)
            .map { InsightTarget(target = it.key, count = it.value) }

    private fun recoveryInsight(
        counts: List<SessionCount>,
        allTime: Long,
    ): RecoveryInsight {
        fun sumWhere(predicate: (SessionCount) -> Boolean): Int =
            counts.filter(predicate).sumOf { it.count.toInt() }

        // Insights describes technique practice. A bare log and an Urge Assist step episode are
        // both technique-less; neither belongs in "logged only" once an action was named, and a
        // step episode must not read as an abandoned technique.
        val logOnly = sumWhere {
            it.technique == RecoverySession.TECHNIQUE_NONE && it.actionId == null
        }
        return RecoveryInsight(
            completedTechniques = sumWhere {
                it.state == RecoverySession.STATE_COMPLETED &&
                    it.technique != RecoverySession.TECHNIQUE_NONE
            },
            loggedOnly = logOnly,
            abandoned = sumWhere {
                it.state == RecoverySession.STATE_ABANDONED &&
                    it.technique != RecoverySession.TECHNIQUE_NONE
            },
            interrupted = sumWhere {
                it.state == RecoverySession.STATE_INTERRUPTED &&
                    it.technique != RecoverySession.TECHNIQUE_NONE
            },
            allTime = allTime,
        )
    }

    private companion object {
        const val TOP_N = 3
    }
}

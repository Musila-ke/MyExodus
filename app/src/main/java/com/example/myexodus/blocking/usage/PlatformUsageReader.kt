package com.example.myexodus.blocking.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * OPD #28(a), user ruling 2026-09-27 ("Digital Wellbeing is the source of truth"): today's
 * foreground time for one package, read straight from the platform.
 *
 * The accounting engine only counts from the moment a rule starts being observed, so usage
 * from before a rule was created or enabled is invisible to it — the device-caught 2026-09-27
 * Instagram case: DW 5h21m, tracker 0ms, rule paused. The engine's user-action seams (rule
 * save, rule enable) reconcile against this number instead.
 *
 * `queryEvents` over [localMidnight, now), summed by [UsageGapRepair.foregroundMsWithin] —
 * the same technique as the D3 gap repair (ADR #73), because `queryUsageStats(INTERVAL_DAILY)`
 * buckets to UTC and a UTC+ device falls outside its contained buckets.
 */
class PlatformUsageReader(private val context: Context) {

    // Binder calls on this device can wedge indefinitely (2026-08-30 finding, see the
    // enforcement service's onBinderThread); an unbounded call here would hang the save flow
    // that awaits it. Dedicated thread, hard bound, abandoned on timeout.
    private val binderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Foreground milliseconds since local midnight, or null when unreadable (binder timeout,
     * revoked usage access). Null is "skip", never zero: a seed must never fabricate usage.
     * Known sub-minute edge: an app foreground across midnight emitted its RESUMED before the
     * clip start, so that leading segment is skipped — fine for a seed that is a lower bound
     * by construction, and [UsageGapRepair.foregroundMsWithin] must not change (its repair-path
     * semantics are pinned by tests).
     */
    fun todayForegroundMs(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? = try {
        binderExecutor.submit(Callable { readForegroundMs(packageName, nowMillis) })
            .get(BINDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "platform usage read abandoned")
        null
    }

    private fun readForegroundMs(packageName: String, nowMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val marks = readMarksByPackage(midnight, nowMillis) ?: return null
        return UsageGapRepair.foregroundMsWithin(marks[packageName].orEmpty(), midnight, nowMillis)
    }

    /**
     * Debug day-audit seam (task #13 investigation): per-package foreground totals over an
     * arbitrary window — the per-row ground truth a tracker-vs-Digital-Wellbeing comparison
     * reads. Not used by any production path.
     */
    fun foregroundTotalsByPackage(fromMs: Long, toMs: Long): Map<String, Long>? = try {
        binderExecutor.submit(Callable { readTotalsByPackage(fromMs, toMs) })
            .get(BINDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "platform usage read abandoned")
        null
    }

    private fun readTotalsByPackage(fromMs: Long, toMs: Long): Map<String, Long>? =
        readMarksByPackage(fromMs, toMs)?.mapValues { (_, marks) ->
            UsageGapRepair.foregroundMsWithin(marks, fromMs, toMs)
        }

    /** Debug day-audit seam: the clipped foreground intervals for one package, so a missing
     *  or mis-sized interval is identifiable per row rather than as a total. */
    fun foregroundIntervals(
        packageName: String,
        fromMs: Long,
        toMs: Long,
    ): List<LongRange>? = try {
        binderExecutor.submit(Callable { readIntervals(packageName, fromMs, toMs) })
            .get(BINDER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "platform usage read abandoned")
        null
    }

    private fun readIntervals(packageName: String, fromMs: Long, toMs: Long): List<LongRange>? =
        readMarksByPackage(fromMs, toMs)?.let { marks ->
            UsageGapRepair.foregroundIntervalsWithin(marks[packageName].orEmpty(), fromMs, toMs)
        }

    /** One queryEvents pass over [fromMs, toMs), every package's foreground marks. Null when
     *  unreadable (binder timeout, revoked access, empty window) — never an empty map standing
     *  in for a failed read. */
    private fun readMarksByPackage(fromMs: Long, toMs: Long): Map<String, List<UsageMark>>? {
        if (fromMs >= toMs) return null
        val events = context.getSystemService(UsageStatsManager::class.java)
            .queryEvents(fromMs, toMs) ?: return null
        val marksByPackage = mutableMapOf<String, MutableList<UsageMark>>()
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            if (!events.getNextEvent(event)) continue
            val resumed = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED -> false
                else -> continue
            }
            val pkg = usageEventPackage(event) ?: continue
            marksByPackage.getOrPut(pkg) { mutableListOf() }.add(UsageMark(event.timeStamp, resumed))
        }
        return marksByPackage
    }

    private companion object {
        const val TAG = "MyExodusUsage"
        const val BINDER_TIMEOUT_MILLIS = 2_000L
    }
}
